# Connect a whole computer, with no proxy settings (experimental)

TetherFuseNet runs a proxy on your phone. Normally every application on the other device has to be told
about it. **Computer-wide mode** removes that step: the computer runs one command, and *everything* on it
goes through your phone, including DNS and UDP (QUIC, voice calls, games).

Because your phone makes the connections, they leave from your phone like its own traffic, and they follow
the VPN on your phone if it has one.

> Status: experimental. The pieces are covered by unit tests and real-socket tests, but it has not been
> exercised on every phone, carrier and operating system. Please report what you find.

## What you need

- TetherFuseNet running with **SOCKS turned on**: with the Hotspot stopped, switch on *SOCKS4A/SOCKS5H Proxy*
  under *Proxy Mode* in the Hotspot tab. *HTTP/HTTPS Proxy* can stay on.
- The computer joined to the TetherFuseNet Wi-Fi network, or plugged in with USB tethering turned on.
- [sing-box](https://sing-box.sagernet.org/) **1.14 or newer** on the computer, installed once:

  | System | Install |
  |---|---|
  | Arch / CachyOS | `sudo pacman -S sing-box` |
  | macOS | `brew install sing-box` |
  | Windows | `winget install SagerNet.sing-box` |
  | Other Linux | <https://sing-box.sagernet.org/installation/package-manager/> |

- Administrator rights (`sudo`, or an Administrator PowerShell). Creating a network tunnel needs them.

## Connect

1. On the phone, open the **Info** tab and expand *Or connect the whole computer*. It shows an address such
   as `http://192.168.49.1:8228/`. (It is the same address and port the proxy settings use.)
2. On the computer, open that address in a browser. No proxy settings are needed for this: the page is
   served by your phone. It shows the commands for your system.
3. Run them. For Linux and macOS:

   ```sh
   curl -fsS http://192.168.49.1:8228/connect.sh -o tfn-connect.sh
   less tfn-connect.sh         # read it, it is short
   sudo sh tfn-connect.sh
   ```

   For Windows, in an **Administrator** PowerShell:

   ```powershell
   Invoke-WebRequest -UseBasicParsing http://192.168.49.1:8228/connect.ps1 -OutFile tfn-connect.ps1
   notepad tfn-connect.ps1
   powershell -ExecutionPolicy Bypass -File .\tfn-connect.ps1
   ```

4. Use the computer normally. Press **Ctrl+C** to disconnect; routes and DNS are restored.

If something misbehaves with UDP (a game, a call, QUIC), try `--tcp-only` (`-TcpOnly` on Windows). UDP is
turned away, applications fall back to TCP, and DNS keeps working.

## What the script does

It asks the phone for a ready-made sing-box configuration (`/sing-box.json`) and starts sing-box with it:

- a tunnel device that takes over the computer's default route (`auto_route`, `strict_route`),
- DNS is answered by sing-box and resolved *through the phone* (DNS over TCP via SOCKS5),
- every other connection goes to the phone as SOCKS5 (TCP `CONNECT` and UDP `ASSOCIATE`),
- private network addresses stay direct, IPv6 is refused so nothing leaks around the tunnel,
- the connection to the phone itself stays on the network the phone is on (sing-box binds anything on a
  local subnet to that subnet's interface), so it is never sent into the tunnel, even when the computer
  has another internet connection.

While it runs, everything except local network traffic stops if the phone goes away. That is on purpose:
nothing quietly takes another route.

## Share your phone's VPN

Connections are made by TetherFuseNet, so they use the VPN that is active on the phone, as long as:

- **Preferred Network** (Hotspot tab) is *Active Network*, the default. Choosing Wi-Fi or Cellular Data
  skips the VPN.
- The **Avoid VPN Blocker Dialog** tweak (Behavior tab) is on, otherwise TetherFuseNet refuses to start
  while a VPN is up.
- TetherFuseNet is **not excluded** from the VPN (split tunneling).
- The VPN allows **local network access**. Replies to your computer travel over the hotspot's local
  network (for example `192.168.49.0/24`), and a VPN that tunnels *everything* swallows them. Look for
  "Allow LAN access" / "Local network sharing" in the VPN app, or exclude that range.

Android's own tethering does **not** pass your VPN on to connected devices; this is why the proxy exists.

## Troubleshooting

| Problem | Try |
|---|---|
| "SOCKS is turned off" | Stop the Hotspot, switch on *SOCKS4A/SOCKS5H Proxy* under *Proxy Mode* (Hotspot tab), start it again |
| "cannot reach TetherFuseNet" | Is the computer on the phone's network, and is the Hotspot started? |
| SSH or other idle connections drop after about 30 s | Behavior tab, *Adjust Socket Timeout*: 5 minutes or No Timeout |
| `ping` does not work | Expected. Ping is neither TCP nor UDP. Web, SSH, git, downloads and calls work |
| Network broken after sing-box was killed (not Ctrl+C) on Linux | `sudo nft delete table inet sing-box` |
| Windows: Hyper-V, VirtualBox or WSL networking breaks while connected | Strict routing does this. Disconnect while you use them |
| sudo cannot find sing-box (macOS) | `sudo env "PATH=$PATH" sh tfn-connect.sh` or set `SING_BOX` to its path |

## Safer by reading

The page and scripts are served over plain HTTP, which is fine on a private link (a Wi-Fi network only
people with the password can join, or a USB cable) but is why you are asked to read the script before you
run it. The same files are in this repository under `server/src/main/resources/tfn/`.

The page never shows the Wi-Fi name or password, and it only ever answers about the device it is served by.

## Other ways to the same result

- **Only some applications**: set the proxy yourself, or use the automatic configuration script at
  `http://<address>/proxy.pac` (applications that read the system proxy settings use it).
- **A different tunnel program**: any SOCKS5 client with UDP support works, for example
  [tun2socks](https://github.com/xjasonlyu/tun2socks), [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel)
  or [ProxyBridge](https://github.com/InterceptSuite/ProxyBridge). Point them at the address and port above.
- **Android's own USB or hotspot tethering** needs no proxy and no helper. It cannot use your phone's VPN,
  and some carriers see it as tethering. Setting the computer's TTL to 65 hides the extra hop from carriers
  that only look at that.

## Not covered

- ICMP (`ping`, `traceroute`).
- Game consoles and other devices that cannot run a program or change network settings. A small router
  running the same tunnel can serve them.
- Needing no software at all on the computer: Android does not let an app without root forward other
  devices' traffic, so some program on the computer is unavoidable.
