# TetherFuseNet

Share your Android device's Internet connection with other devices without needing Root.

> **This fork adds computer-wide mode (experimental).** Normally every device has to be told about the
> proxy. Here a computer runs **one command** and *everything* on it goes through your phone, with
> **no proxy settings anywhere**: every app, DNS and UDP (QUIC, calls, games), and through your phone's VPN
> if it has one. The Play Store, F-Droid and IzzyOnDroid builds further down are the original project and do
> not have it. Original project: [pyamsoft/tetherfusenet](https://github.com/pyamsoft/tetherfusenet).

## Get computer-wide mode working

You need this fork's build on the phone, a computer (Linux, macOS or Windows), and about ten minutes.
Android does not let an app without root forward other devices' traffic, so the computer needs one small
free helper ([sing-box](https://sing-box.sagernet.org/)). You install it once.

### 1. Install this build on the phone

Build it (needs a JDK 25 and the Android SDK, platform 37; put `sdk.dir=/path/to/Android/Sdk` in a file
called `local.properties` in the project folder), then install it. With the phone connected over USB and
USB debugging on:

```sh
git clone https://github.com/WoofahRayetCode/tetherfusenet.git
cd tetherfusenet
./gradlew :app:assembleFdroidDebug
adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk
```

It installs as **TetherFuseNet (DEV)** next to any normal TetherFuseNet and does not replace it.

If `adb install` says `INSTALL_FAILED_USER_RESTRICTED` (Xiaomi, HyperOS and some other phones), turn on
**Install via USB** in the phone's Developer options and run it again. Or copy the APK across and open it
from the phone's Files app:

```sh
adb push app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk /sdcard/Download/
```

### 2. Set up the phone (once)

1. Open the app and accept its permission prompts. Wi-Fi must be on. Turn Location on if the app asks.
2. **Hotspot tab**, with the hotspot stopped: under **Proxy Mode** switch on
   **SOCKS4A/SOCKS5H Proxy**. Leave HTTP/HTTPS Proxy on. SOCKS is what carries DNS and UDP, and it is off
   by default.
3. **Behavior tab** > **Adjust Socket Timeout**: choose **5 minutes** (or No Timeout). The default of 30
   seconds closes quiet connections such as SSH.
4. **Hotspot tab** > **Start Hotspot**. Wait until the Broadcast, Proxy and Hotspot statuses are running.

### 3. Connect the computer

1. Join the phone's Wi-Fi network. The name starts with `DIRECT-TF-`; the name and password are in the
   Hotspot tab, which can also show a QR code for joining. (Over USB instead: set **Hotspot Type** to USB
   Tethering, plug in the cable, and turn on USB tethering in Android's settings **before** you start the
   hotspot.)
2. Install sing-box **1.14 or newer** on the computer:

   | System | Command |
   |---|---|
   | Arch / CachyOS | `sudo pacman -S sing-box` |
   | macOS | `brew install sing-box` |
   | Windows | `winget install SagerNet.sing-box` |
   | Other Linux | [package instructions](https://sing-box.sagernet.org/installation/package-manager/) |

3. On the phone open the **Info** tab and expand **Or connect the whole computer, with no proxy
   settings**. It shows an address like `http://192.168.49.1:8228/`.
4. On the computer, open that address in a browser. No proxy settings are needed for this, the phone
   serves the page. It shows the exact commands for your system. For Linux and macOS they are:

   ```sh
   curl -fsS http://192.168.49.1:8228/connect.sh -o tfn-connect.sh
   less tfn-connect.sh       # read it, it is short
   sudo sh tfn-connect.sh
   ```

   On Windows, in an **Administrator** PowerShell, the page gives you the `connect.ps1` equivalent.
5. Use the computer normally. Press **Ctrl+C** to disconnect; the network goes back to normal.

### 4. Check that it works

On the computer, `curl https://ifconfig.me` should print the **phone's** public address, not your home
or office address, and `dig example.com` should answer. Everything now goes through the phone. If the phone
is on a VPN, that VPN's address shows instead.

### Share the phone's VPN

Connections are made by the app, so they use the phone's VPN when:

- Hotspot tab > **Preferred Network** is **Active Network** (the default),
- Behavior tab > **Avoid VPN Blocker Dialog** is on (the app otherwise refuses to start with a VPN up),
- TetherFuseNet is not excluded from the VPN, and
- the VPN allows **local network access** ("Allow LAN access" and similar). Otherwise the replies to your
  computer are swallowed by the VPN.

### If something is off

| Problem | Fix |
|---|---|
| The page says **SOCKS is turned off** | Stop the hotspot, switch on SOCKS4A/SOCKS5H Proxy (step 2), start it again |
| Cannot open the address | Is the computer on the phone's network, and is the hotspot started? |
| SSH or other quiet connections drop | Socket Timeout to 5 minutes or No Timeout (step 2) |
| A game, call or QUIC misbehaves | Run the script with `--tcp-only` (`-TcpOnly` on Windows) |
| `ping` fails | Expected. It is neither TCP nor UDP. Web, SSH, git, downloads and calls work |
| Network broken after killing sing-box on Linux | `sudo nft delete table inet sing-box` |

More detail, other tunnel programs and the safety notes: [docs/computer-wide-mode.md](docs/computer-wide-mode.md).

**Status.** The proxy side and the client script are covered by automated tests, including a
no-phone end-to-end test on Linux ([testing/e2e](testing/e2e/README.md)). It has not yet been run on every
phone, on Wi-Fi Direct vs USB tethering in the field, on Windows or on macOS. Please report what you find.

## Get TetherFuseNet

### Google Play (Google Play APK)

#### Official releases are ONLY published to the Google Play Store.

[<img
src="https://play.google.com/intl/en_us/badges/images/generic/en-play-badge.png"
alt="Get it on Google Play"
height="80">](https://play.google.com/store/apps/details?id=com.pyamsoft.tetherfi)

### Unofficial Releases / Community Supported


#### FDroid (FDroid APK) (IzzyOnDroid Repository)

[<img
src="https://gitlab.com/IzzyOnDroid/repo/-/raw/master/assets/IzzyOnDroid.png"
alt="Get it on IzzyOnDroid"
height="80">](https://apt.izzysoft.de/fdroid/index/apk/com.pyamsoft.tetherfi)

#### OpenAPK (FDroid APK)

[<img
src="https://www.openapk.net/images/openapk-badge.png"
alt="Get it on OpenAPK"
height="80">](https://www.openapk.net/tetherfi/com.pyamsoft.tetherfi/)

#### Github Releases (FDroid APK)

or get the APK from the
[Releases Section](https://github.com/pyamsoft/tetherfusenet/releases/latest).

## Screenshots

### Hotspot Status

#### Status Overview

[<img
src="https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/main-light.png"
alt="Light Mode: Hotspot Status"
height="200">](https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/main-light.png)
[<img
src="https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/main-dark.png"
alt="Dark Mode: Hotspot Status"
height="200">](https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/main-dark.png)

### Hotspot Behavior

#### Operation Settings

[<img
src="https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/behavior-light.png"
alt="Light Mode: Operating Settings"
height="200">](https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/behavior-light.png)
[<img
src="https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/behavior-dark.png"
alt="Dark Mode: Operating Settings"
height="200">](https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/behavior-dark.png)

### Hotspot Active

#### In-App

[<img
src="https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/running-light.png"
alt="Light Mode: Hotspot On"
height="200">](https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/running-light.png)
[<img
src="https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/running-dark.png"
alt="Dark Mode: Hotspot On"
height="200">](https://raw.githubusercontent.com/pyamsoft/tetherfi/main/art/screens/phone/running-dark.png)

## What is TetherFuseNet

TetherFuseNet works by creating a Wi-Fi Direct legacy group and an HTTP proxy server. Other
devices can connect to the broadcasted Wi-Fi network, and connect to the Internet by
setting the proxy server settings to the server created by TetherFuseNet. You do not need a
Hotspot data plan to use TetherFuseNet, but the app works best with "unlimited" data plans.

### TetherFuseNet may be for you if:

- You want to share your Android's Wi-Fi or Cellular Data
- You have an Unlimited Data and a Hotspot plan from your Carrier, but Hotspot
  has a data cap
- You have an Unlimited Data and a Hotspot plan from your Carrier, but Hotspot
  has throttling
- You do not have a mobile Hotspot plan
- You wish to create a LAN between devices
- Your home router has reached the device connection limit

## How

TetherFuseNet uses a Foreground Service to create a long-running Wi-Fi Direct Network that
other devices can connect to. Connected devices can exchange network data between each other.
The user is in full control of this Foreground Service and can explicitly choose when to
turn it on and off.

TetherFuseNet is still a work in progress and not everything will work. For example, using the
app to get an open NAT type on consoles is currently not possible. Using TetherFuseNet for certain
online apps, chat apps, video apps, and gaming apps is currently not possible. Some services
such as email may be unavailable. General "normal" internet browsing should work fine - however,
it is dependent on the speed and availability of your Android device's internet connection.

To see a list of apps that are known to not work currently, see the
[Wiki](https://github.com/pyamsoft/tetherfusenet/wiki/Known-Not-Working)

To avoid proxy settings on a computer altogether, see
[computer-wide mode](#get-computer-wide-mode-working) at the top of this page.

## Privacy

TetherFuseNet respects your privacy. TetherFuseNet is open source, and always will be. TetherFuseNet
will never track you, or sell or share your data. TetherFuseNet offers in-app purchases,
which you may purchase to support the developer. These purchases are never
required to use the application or any features.

## Development

TetherFuseNet is developed in the open on GitHub at:

[https://github.com/pyamsoft/tetherfusenet](https://github.com/pyamsoft/tetherfusenet)

If you know a few things about Android programming and want to help out with
development, you can do so by creating issue tickets to squash bugs, and
proposing feature requests.

## License

Apache 2

```
Copyright 2026 pyamsoft

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
