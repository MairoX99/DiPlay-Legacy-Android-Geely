# DiPlay — Geely E01

CarPlay for the **Geely Xingrui (吉利星瑞) 2021 E01** head unit — an ECARX E01
(MediaTek MT6735) unit running GKUI 19 on **Android 5.1 (API 22)**.

This is a fork of [programmerguohuajing/DiPlay-Legacy-Android](https://github.com/programmerguohuajing/DiPlay-Legacy-Android),
which is itself a fork of [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay).
Everything described here is either Geely-specific work or brand-neutral work ported from upstream.
**Upstream's BYD-specific features are out of scope for this fork and are not documented here.**

**Version:** `0.3.3-geely-rc` (versionCode 33) · App id `com.shihab.diplay`

## Verified on the car

| | |
|---|---|
| **Steering-wheel next / previous track** | ✅ **Works on the car.** `DiPlay-MediaKeys: media key -> CarPlay 4/5 sent=true` |
| **Geely wheel has no play/pause key** | The position is the head unit's own screen-mirroring (飞屏) button. It is routed only through the vendor AIDL layer to `PopView` and never enters the Android key pipeline, so no app can reach it. |

The wheel was the hard part, and the fix that mattered was **not** what upstream does. On this head
unit the wheel goes through the car's own input service, which re-emits each press as a standard
`ACTION_MEDIA_BUTTON`. DiPlay's media session declared `flags=0` and had no media-button receiver, so
the framework handed every press to whichever app held the wheel before. Declaring
`FLAG_HANDLES_MEDIA_BUTTONS` plus a media-button receiver fixed it — `dumpsys media_session` went
from `flags=0 / mediaButtonReceiver=null` to `flags=3 / PendingIntent{...}`.

The generic accessibility filter and the ECARX vendor key codes upstream built are **still in the
tree as fallbacks for other head units**. Neither was needed here: GKUI 19 does not expose an
accessibility settings entry on this car, so that path cannot be switched on without ADB.

Not yet verified on a car: USB permission auto-confirm, the diagnostic-export fallback, the audio
stall timeline, and the wireless startup diagnostics.

## What this fork adds

| | |
|---|---|
| **Geely steering-wheel keys** | See above. Media session flags + receiver; the accessibility filter and ECARX key codes stay as fallbacks. |
| **Car hotspot** | DiPlay now switches the head unit's own hotspot on through `setWifiApEnabled`, keeping the car's existing hotspot configuration, instead of telling the driver to go and enable it. Station mode is released only on a retry, since that disconnects the car from whatever network it is on. Failures are reported by kind: firmware withholding the permission, the car refusing, or the hotspot not reporting itself enabled in time. |
| **Wireless startup diagnostics** | When a wireless connection stalls, the report now says **which step it stalled at**, plus interface state, Bonjour discovery counters and kernel receive/UDP counters. Observation only — it does not change connection deadlines, address selection or retry behaviour. |
| **USB attach filter** | Added the Apple vendor id; plugging in an iPhone cold no longer fails to launch DiPlay. |
| **USB permission auto-confirm** | Removes the system's USB authorisation prompt. It answers only the system dialog, never on behalf of another app. |
| **USBMUX framing** | Tolerates the 4-byte padding after a control reply, which used to abort the connection with a protocol error. |
| **Audio** | UDP receive buffer raised to 512 KB, plus a stall timeline (largest inter-arrival gap, sequence gaps). |
| **Touch latency** | Nagle's algorithm disabled on the touch event channel, so gestures no longer arrive in batches. |
| **Diagnostic export** | Falls back three ways on head units with no file picker, and can show the report on screen for copy-by-long-press. |
| **Android 5 settings page** | Switches no longer render their on/off captions, which Android 5 laid out incorrectly. |
| **Wired connection crash** | The runtime config required a manual hotspot SSID whenever manual hotspot mode was configured, wired or not, which aborted wired bring-up. It now applies to wireless only. |
| **BYD code gated off** | The SOME/IP HUD bridge now checks its gateway is installed before binding, so a Geely head unit no longer retries a service it can never reach — that retry used to run every 300 ms. |

## Install

Install on the **car**, not the iPhone. No jailbreak, dongle or Mac is required.

⚠️ **This APK is signed with a different key from upstream's, so it cannot be installed over it.**
Uninstall first:

```sh
adb uninstall com.shihab.diplay
adb install -r DiPlay-Legacy-Geely-V033.apk
```

Uninstalling loses the app's settings (resolution, audio buffer, wheel-key roles, saved Wi-Fi
credentials). Without ADB, copy the APK to a USB stick and install it from the head unit's file
manager with "unknown sources" allowed.

The head unit must permit APK installation.

## Platform notes

The reference car is **Android 5.1 / API 22**, not 4.4 — measured, not assumed
(`ro.build.version.release=5.1`, `ro.build.version.sdk=22`). This fork keeps an
**API 19 floor** so it still builds for older head units, but 5.1 is the target, and every ported
piece is checked against both.

E01 is memory-starved: `MemFree` sits at **26–33 MB**, with `AnonPages` around 1.05 GB. That is the
binding constraint on what is worth porting, not the API level alone.

## Upstream alignment

This fork's base is upstream DiPlay **v0.2.7**. The latest check is upstream `main` `5e58b4b`
(2026-10-07): 19 non-merge commits after `e2fd8ea`, still on the **v0.2.13** tag, and none of them
were ported. Aligning is a staged program, not a merge: the two share no history, and upstream
targets Android 9 (minSdk 28) while this fork keeps an API 19 floor, so every ported piece has to
be checked against the older platform.

| Feature group | State |
|---|---|
| **Steering-wheel keys** | **Done, verified on the car.** See above. Upstream's map zoom and joystick on the same service drive a BYD dashboard this car does not have. |
| **Wireless connection** | **Partly aligned.** Car hotspot takeover, and the wireless startup diagnostics, are in. Same LAN / existing Wi-Fi is not yet ported but is feasible here. Wi-Fi Direct group recovery and preferred-channel selection are **not planned**: upstream marks them `@RequiresApi(Q)` (API 29) and this car is API 22. |
| **Wired / USB** | Partly aligned — attach filter, USBMUX framing, permission auto-confirm. |
| **Protocol and audio** | Partly aligned — UDP receive buffer and stall diagnostics. Buffered audio is **not planned** (see below). |
| **Interface and settings** | Not started. |
| **Stability fixes** | Not started. |

Deliberately out of scope:

- **BYD hardware** — DiLink 3/4/5 cluster projection, BYD HUD navigation, CAN battery reporting.
  This head unit has no counterpart. The code stays in the tree so upstream syncs stay cheap.
- **Buffered audio** (`BufferedAudioStream`) — a large new subsystem whose whole point is trading
  memory for smoothness. On a unit with 26–33 MB free that is the wrong trade, and upstream ships it
  disabled by default.
- **Android multi-window / split screen** — GKUI has no split screen, so CarPlay is always full
  screen here. (CarPlay *view areas* are an iAP2 protocol feature and would work; the head unit just
  never gives DiPlay a smaller window to use them for.)

## Documentation

- [Release notes](CHANGELOG.md)
- [Build from source](docs/BUILD.md)
- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Validation](docs/VALIDATION.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website
adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in
`docs/licenses`. Preserve those notices when distributing modifications. CarPlay and its icon belong
to Apple Inc.; no Apple, Geely or ECARX affiliation or endorsement is implied.

This repository starts with a clean public source snapshot. Local research, tester reports and
release-signing secrets are excluded. The complete source corresponding to the APK is provided with
every release; experimental runtime identity assets are described separately in the build
instructions and notices.

This is **not an Apple-certified product**. The APK bundles an experimental accessory identity
recovered from public Carlinkit firmware, not a newly provisioned MFi identity. A bundled private key
is extractable. Acceptance after future iOS updates, reliability across head units and suitability of
that identity for general distribution are unresolved.

## Local release packaging

The release APK intentionally contains the experimental accessory identity. The Git repository and
source archive exclude all accessory and Android signing keys; tests generate synthetic identities at
runtime. Source/CI builds omit runtime identity assets by default. Local release builds explicitly
select an external asset directory. Publishing the APK makes its bundled identity extractable;
building locally does not preserve that identity's confidentiality.
