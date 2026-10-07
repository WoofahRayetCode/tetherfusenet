# End-to-end test of computer-wide mode (Linux, no phone)

`run.sh` proves the whole path works: the **real** TetherFuseNet proxy from this repository, the **real**
`connect.sh` exactly as the phone serves it, and the **real** sing-box, in throw-away network namespaces.

```
 "computer" namespace                      "phone" namespace
 sing-box + connect.sh + curl/dig   <--->  TetherFuseNet proxy on 192.168.49.1:8228
 (optionally a second "home Wi-Fi")        fake internet: HTTP, UDP echo and DNS
```

## Run

```sh
./gradlew :server:testDebugUnitTest      # once, the proxy is started offline
SING_BOX=/path/to/sing-box testing/e2e/run.sh
```

Needs Linux with unprivileged user namespaces, sing-box 1.14 or newer, `curl`, `dig`, `python3`, `ip`, a JDK
and the Android SDK (the same as for `./gradlew test`), and `nft` if you want `auto_redirect` covered. Run it
as yourself, not as root. Output is in `build/e2e`. The exit code is the number of failed checks.

## What it checks

- the setup page and documents are served to a client with no proxy settings
- TCP, DNS (over UDP and TCP) and UDP all go through the phone, IPv6 is refused
- the phone itself stays reachable, and routes, rules, the TUN device and the nftables table are removed when
  the tunnel is stopped
- `--tcp-only` turns UDP away and keeps DNS working
- a computer with a second internet connection (its own default route) still sends everything through the
  phone, and gets its own route back afterwards
- pinning the interface by hand (`TFN_INTERFACE`) works

## Safe to run

Everything happens inside `unshare -Urmn`: the network, the mounts and "root" are private to the run, so
nothing here can change the machine's real routes, DNS or firewall. `/run` is hidden inside the run so
sing-box can never reach the real systemd-resolved. It stops any running Gradle daemon first, because a
daemon in the real network namespace cannot hand its lock files to a process in the private one.

This does **not** test Android, Wi-Fi Direct, USB tethering, Windows or macOS.
