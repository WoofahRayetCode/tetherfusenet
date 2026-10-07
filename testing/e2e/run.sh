#!/bin/bash

# Copyright (C) 2026 pyamsoft
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may not
# use this file except in compliance with the License.  You may obtain a copy
# of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
# License for the specific language governing permissions and limitations
# under the License.

# End-to-end test of computer-wide mode on Linux, with no phone.
#
# It runs the REAL proxy from this repository, the REAL connect.sh exactly as the phone serves it, and
# the REAL sing-box, in throw-away network namespaces:
#
#   "computer" namespace   sing-box + curl + dig. Optionally a "home Wi-Fi" with its own default route.
#   "phone" namespace      the TetherFuseNet proxy on 192.168.49.1:8228 and a fake internet
#
# Needs: Linux with unprivileged user namespaces, sing-box 1.14+ (SING_BOX=/path or on the PATH), curl, dig,
# python3, ip, a JDK and the Android SDK for Gradle (the same as ./gradlew test), and nft if you want to
# test auto_redirect. Run ./gradlew :server:testDebugUnitTest once first, the proxy is started offline.
#
# Run it as yourself, not as root:   testing/e2e/run.sh
#
# Safe for the machine it runs on: everything happens inside `unshare -Urmn`, so the network, the mounts
# and the root user are private to the run. /run is hidden so sing-box can never reach the real
# systemd-resolved. Output goes to build/e2e (override with TFN_E2E_OUT). The exit code is the number of
# failed checks, 0 if all passed.

set -u

HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)

if [ "${TFN_E2E_INNER:-}" != 1 ]; then
  # Gradle's lock files are guarded by a daemon that lives in the real network namespace, which the
  # isolated run cannot reach, so no daemon may be holding them
  "$REPO/gradlew" --stop > /dev/null 2>&1 || true
  exec unshare -Urmn env TFN_E2E_INNER=1 TFN_E2E_REAL_HOME="$HOME" bash "$0" "$@"
fi

# From here on we are "root" inside the private namespaces, whose home is not ours
export HOME=$TFN_E2E_REAL_HOME
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$HOME/.gradle}
export ANDROID_USER_HOME=${ANDROID_USER_HOME:-$HOME/.android}
export JAVA_TOOL_OPTIONS="-Duser.home=$HOME"

SB=${SING_BOX:-$(command -v sing-box || true)}
if [ -z "$SB" ] || [ ! -x "$SB" ]; then
  echo "sing-box was not found. Install it (1.14 or newer) or set SING_BOX=/path/to/sing-box" >&2
  exit 100
fi

OUT=${TFN_E2E_OUT:-$REPO/build/e2e}
RES=$REPO/server/src/main/resources/tfn
rm -rf "$OUT"
mkdir -p "$OUT"
RESULTS=$OUT/results.txt
: > "$RESULTS"

say() { echo "[$(date +%T)] $*" | tee -a "$OUT/log.txt"; }

check() {
  local name="$1"
  shift
  if "$@" > "$OUT/check-$name.log" 2>&1; then
    echo "PASS  $name" | tee -a "$RESULTS"
  else
    echo "FAIL  $name" | tee -a "$RESULTS"
  fi
}

PIDS=()
cleanup() {
  touch "$OUT/stop"
  for pid in "${PIDS[@]}"; do
    kill "$pid" 2> /dev/null
  done
}
trap cleanup EXIT

# The real systemd-resolved must never hear from sing-box: hide /run (this mount namespace only)
mount -t tmpfs tmpfs /run || {
  say "cannot hide /run, refusing to continue"
  exit 101
}

say "creating the phone namespace"
unshare -n sleep 3600 &
PHONE=$!
PIDS+=("$PHONE")
sleep 0.5
ip link add veth-c type veth peer name veth-p || exit 102
ip link set veth-p netns "$PHONE"
ip link set lo up
ip addr add 192.168.49.2/24 dev veth-c
ip link set veth-c up
nsenter -t "$PHONE" -n bash -c '
  ip link set lo up
  ip addr add 1.1.1.1/32 dev lo
  ip addr add 198.51.100.10/32 dev lo
  ip addr add 192.168.49.1/24 dev veth-p
  ip link set veth-p up' || exit 103

say "starting the fake internet in the phone namespace"
nsenter -t "$PHONE" -n python3 "$HERE/servers.py" > "$OUT/servers.log" 2>&1 &
PIDS+=("$!")
sleep 1

say "starting the REAL TetherFuseNet proxy in the phone namespace (gradle, offline)"
cd "$REPO" || exit 104
nsenter -t "$PHONE" -n env TFN_E2E_HOST=192.168.49.1 TFN_E2E_PORT=8228 TFN_E2E_STOP="$OUT/stop" \
  ./gradlew --offline --no-daemon --console=plain :server:testDebugUnitTest --rerun \
  --tests '*E2eProxyHolderTest' > "$OUT/gradle.log" 2>&1 &
PIDS+=("$!")

for _ in $(seq 1 120); do
  curl -s --max-time 2 http://192.168.49.1:8228/tfn.json > "$OUT/tfn.json" 2> /dev/null && break
  sleep 2
done
if ! grep -q socks5 "$OUT/tfn.json" 2> /dev/null; then
  say "proxy never came up, see $OUT/gradle.log"
  tail -30 "$OUT/gradle.log"
  exit 105
fi
say "proxy is up: $(cat "$OUT/tfn.json")"
check page_without_proxy_settings sh -c "curl -sS --max-time 5 http://192.168.49.1:8228/ | grep -q 'connection assistant'"

# The script exactly as the phone serves it
sed -e 's/@@HOST@@/192.168.49.1/' -e 's/@@PORT@@/8228/' -e 's/@@VERSION@@/e2e/' "$RES/connect.sh" > "$OUT/tfn-connect.sh"

start_tunnel() {
  : > "$OUT/connect.log"
  setsid env SING_BOX="$SB" sh "$OUT/tfn-connect.sh" 192.168.49.1:8228 "$@" > "$OUT/connect.log" 2>&1 &
  TUNNEL=$!
  for _ in $(seq 1 40); do
    ip -o link show type tun 2> /dev/null | grep -q . && break
    sleep 0.5
  done
  sleep 3
}

stop_tunnel() {
  # Like Ctrl+C: the whole process group, so sing-box hears it too
  kill -INT -- "-$TUNNEL" 2> /dev/null
  for _ in $(seq 1 30); do
    kill -0 "$TUNNEL" 2> /dev/null || break
    sleep 0.5
  done
}

say "=== run 1: full tunnel ==="
start_tunnel
check tcp_through_phone sh -c "curl -sS --max-time 15 http://198.51.100.10/ | grep -q hello-from-the-internet"
check dns_udp_hijacked sh -c "dig +short +time=5 +tries=1 @9.9.9.9 host.test A | grep -qx 198.51.100.10"
check dns_tcp_hijacked sh -c "dig +short +tcp +time=5 +tries=1 @9.9.9.9 host.test A | grep -qx 198.51.100.10"
check udp_through_phone python3 -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(6)
for i in range(5):
    s.sendto(b'ping-%d' % i, ('198.51.100.10', 5353))
got = sorted(s.recvfrom(100)[0] for _ in range(5))
assert got == sorted(b'ping-%d' % i for i in range(5)), got"
check ipv6_refused_fast sh -c "! timeout 8 curl -g -6 --max-time 6 'http://[2001:db8::1]/'"
check phone_still_reachable_directly sh -c "curl -sS --max-time 8 http://192.168.49.1:8228/tfn.json | grep -q socks5"
stop_tunnel
check tun_removed sh -c "! ip -o link show type tun | grep -q ."
check rules_back_to_normal sh -c "test \$(ip rule | wc -l) -le 3"
check phone_reachable_after sh -c "curl -sS --max-time 8 http://192.168.49.1:8228/tfn.json | grep -q socks5"
if command -v nft > /dev/null 2>&1; then
  check nft_table_removed sh -c "! nft list tables | grep -q sing-box"
fi

say "=== run 2: --tcp-only ==="
start_tunnel --tcp-only
check tcponly_tcp_works sh -c "curl -sS --max-time 15 http://198.51.100.10/ | grep -q hello-from-the-internet"
check tcponly_dns_works sh -c "dig +short +time=5 +tries=1 @9.9.9.9 host.test A | grep -qx 198.51.100.10"
check tcponly_udp_refused sh -c "! python3 -c \"
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(3)
s.sendto(b'x', ('198.51.100.10', 5353)); s.recvfrom(100)\""
stop_tunnel

say "=== run 3: the computer ALSO has a home Wi-Fi with its own default route ==="
unshare -n sleep 3600 &
HOMENS=$!
PIDS+=("$HOMENS")
sleep 0.5
ip link add veth-h type veth peer name veth-hh
ip link set veth-hh netns "$HOMENS"
ip addr add 10.0.0.2/24 dev veth-h
ip link set veth-h up
nsenter -t "$HOMENS" -n bash -c '
  ip link set lo up
  ip addr add 198.51.100.10/32 dev lo
  ip addr add 10.0.0.1/24 dev veth-hh
  ip link set veth-hh up'
nsenter -t "$HOMENS" -n python3 "$HERE/home.py" > "$OUT/home.log" 2>&1 &
PIDS+=("$!")
ip route add default via 10.0.0.1 dev veth-h
sleep 1
check home_before_tunnel sh -c "curl -sS --max-time 8 http://198.51.100.10/ | grep -q hello-from-HOME"
start_tunnel
check home_tunnel_takes_over sh -c "curl -sS --max-time 15 http://198.51.100.10/ | grep -q hello-from-the-internet"
check home_dns_through_phone sh -c "dig +short +time=5 +tries=1 @9.9.9.9 host.test A | grep -qx 198.51.100.10"
check home_udp_through_phone python3 -c "
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM); s.settimeout(5)
s.sendto(b'via-phone', ('198.51.100.10', 5353)); assert s.recvfrom(100)[0] == b'via-phone'"
stop_tunnel
check home_restored_after sh -c "curl -sS --max-time 8 http://198.51.100.10/ | grep -q hello-from-HOME"

say "=== run 4: the interface pinned by hand (TFN_INTERFACE) ==="
TFN_INTERFACE=veth-c start_tunnel
check pinned_variant_works sh -c "curl -sS --max-time 15 http://198.51.100.10/ | grep -q hello-from-the-internet"
stop_tunnel

say "done"
echo
echo "=== RESULTS ==="
cat "$RESULTS"

exit "$(grep -c '^FAIL' "$RESULTS")"
