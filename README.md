# DiPlay Legacy Android

> This project is modified from [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay), with a focus on compatibility with older Android versions and legacy Android-based head units.

> Upstream project: https://github.com/shihabal3amri/DiPlay

**CarPlay for compatible BYD Android head units.** Wired and wireless, with the familiar DiAuto interface. Independent app: `com.shihab.diplay`.

> **BYD support scope:** These projects focus on BYD cars. They may work on other brands, but other brands are unsupported and there are no plans to add support or fix brand-specific incompatibilities.

> **This fork** (`MairoX99/DiPlay-Legacy-Android-Geely`) adds Geely support on top of that. Geely cars run GKUI on ECARX head units, which behave differently from BYD's DiLink. Everything BYD-specific is unchanged and still present.

## Geely compatibility (this fork)

| Compatibility work | What it does |
|---|---|
| **Geely steering-wheel keys** | A Geely wheel does not arrive as a standard `ACTION_MEDIA_BUTTON` broadcast, so it looked dead no matter what priority DiPlay registered at — and the car's window manager can keep the press for itself. DiPlay now tries two independent paths and logs which one works. The generic one is an accessibility service that filters key events, reaching the wheel before the car can keep it and needing no vendor SDK at all; switch it on in the car's accessibility settings. The other asks the head unit's own input service (`com.ecarx.xui.adaptapi.input`) to hand the wheel over, retrying six times, five seconds apart. Both recognise the vendor key codes these wheels send — `200085`/`200087`/`200088`/`200231` plus the `110000`/`210000` seek pairs, all offset from the standard Android codes. If neither works, the media session handles keys exactly as before. |
| **Car hotspot** | The "car hotspot" link used to only check whether the head unit's own hotspot was on and tell the driver to switch it on in the car settings. DiPlay now switches it on itself through the platform's `setWifiApEnabled`, keeping the car's existing hotspot configuration, and releases Wi-Fi station mode only if the first attempt fails — that disconnects the head unit from whatever network it is on. When it does not come on, the message says which failure it was: the firmware withholding the permission (the usual case, and one many grant only over ADB), the car refusing the request, or the hotspot not reporting itself enabled in time. |
| **Android 5 settings page** | The settings switches no longer render their on/off captions, which Android 5 laid out incorrectly. |
| **Wired connection crash** | The runtime config required a manual hotspot SSID whenever manual hotspot mode was configured, wired or not, which aborted wired bring-up. It now applies to wireless only. |

### Upstream alignment

This fork's code base is the Legacy snapshot of upstream DiPlay **v0.2.7**. Upstream has since
reached **v0.2.12 plus unreleased work** — 464 commits in six feature groups. Aligning with it is a
staged program, not a merge: the two share no history, and upstream targets Android 9 while this
fork keeps an Android 4.4 (API 19) floor, so every ported piece has to be checked against the older
platform. The reference car — a Geely Xingrui E01 running GKUI 19 — is itself **Android 5.1
(API 22)**, so the floor is a floor, not the target.

| Feature group | State |
|---|---|
| **Steering-wheel keys** | **Done, and verified on the car.** The wheel goes through the head unit's own input service, which re-emits each press as a standard `ACTION_MEDIA_BUTTON`. The fix that mattered was the media session: it now declares `FLAG_HANDLES_MEDIA_BUTTONS` and a media-button receiver, so the framework picks DiPlay instead of whichever app held the wheel before. The generic accessibility filter and the ECARX key codes stay as fallbacks for other head units — neither was needed here. The map zoom and joystick upstream built on the same service drive a BYD dashboard this car does not have. |
| **Wireless connection** | **Partly aligned.** Switching the car hotspot on, and reporting why it failed, is in. Existing Wi-Fi / Same LAN, Wi-Fi Direct group recovery, preferred-channel selection and the hotspot-join state machine are not. |
| **Wired / USB** | Not started. |
| **Protocol and audio** | Not started. |
| **Interface and settings** | Not started. |
| **Stability fixes** | Not started. |

Features that depend on BYD hardware — the DiLink 3/4/5 cluster projection, BYD HUD navigation, CAN
battery reporting — have no counterpart on a Geely head unit. They are **out of scope rather than
outstanding**, and are not planned. The code stays in the tree so upstream syncs stay cheap, but the
SOME/IP HUD bridge now checks that its gateway is installed before binding, so a head unit without
one no longer retries a service it can never reach (that retry used to run every 300 ms).

**Status:** steering-wheel track skip is **verified on the car** — a Geely Xingrui E01 on Android 5.1
sends next and previous track through to CarPlay. That wheel has no play/pause key; the position is
the head unit's own screen-mirroring button, which never enters the Android key pipeline and so is
not reachable by any app. Every other ported piece is still unverified on a car, and each degrades to
the previous behaviour on a head unit that offers neither path, so non-Geely cars are unaffected.
Check `DiPlay-MediaKeys` for each key forwarded to CarPlay, `DiPlay-WheelKeys` for whether the
accessibility filter was switched on and what it saw, and `DiPlay-EcarxKeys` for whether the ECARX
input service was found and what it granted.

[Download & website](https://shihabal3amri.github.io/DiPlay/) · [Release](https://github.com/shihabal3amri/DiPlay/releases/tag/v0.2.7) · [Report a problem](https://github.com/shihabal3amri/DiPlay/issues/new/choose)

![DiPlay home](site/assets/home.png)

## 0.2.7 — public preview

Install on the **car**, not the iPhone. No jailbreak, dongle, Mac, account or authentication server is required for use. Core CarPlay does not require ADB; the optional dashboard-mode and battery features do. Your head unit must permit APK installation. The mobile APK now supports Android 4.4+ (API 19) for the classic UI and wired transport. Android 4.4–7 use a manually configured car hotspot for wireless mode, Android 8+ may use LocalOnlyHotspot, and Wi-Fi Direct remains available on Android 10+.

- Wired USB and wireless CarPlay with local authentication.
- BYD HUD navigation with arrows, distance and street names on verified firmware.
- Car hotspot support, improved audio buffering and saved receive diagnostics.
- Automatic address discovery, fixed-channel Wi-Fi fallbacks and successful-configuration memory.
- Icon/text size, resolution and frame rate; applying a display change reconnects CarPlay.
- Local diagnostic export. Reports are sent only if you choose to share them.
- Separate installation alongside DiAuto. Run one projection app at a time.
- Legacy Android fallbacks for media keys, notification/services, USB configuration and requests, audio/video codecs, multidex and Java library APIs.

This is **not an Apple-certified product**. The APK bundles an experimental accessory identity recovered from public Carlinkit firmware, not a newly provisioned MFi identity for DiPlay. A bundled private key is extractable. Acceptance after future iOS updates, reliability across head units and suitability of that identity for general distribution are unresolved. This release invites community testing; it is not a guarantee of universal compatibility.

Earlier releases were tested on the development DiLink5.1 car: live windshield guidance and street names work, Car hotspot now starts CarPlay, and Wi-Fi Direct performance is substantially improved. Occasional audio cutouts remain and are deferred to a later update. The newly packaged 0.2.7 APK has not had a separate on-car test. Broader head-unit and iOS compatibility is not guaranteed. The HUD firmware scope and cleanup limits are documented in [BYD navigation](docs/BYD_NAVIGATION.md).

## What’s new in 0.2.7

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.

## Documentation

- [Install and connect](docs/INSTALL.md)
- [Compatibility and troubleshooting](docs/COMPATIBILITY.md)
- [Privacy and diagnostic reports](docs/PRIVACY.md)
- [Build from source](docs/BUILD.md)
- [Validation](docs/VALIDATION.md)
- [Release notes](CHANGELOG.md)
- [Credits and licenses](docs/THIRD_PARTY_NOTICES.md)

The website is available in English, Arabic, Russian, Spanish and Simplified Chinese. The app interface supports those same five languages. Choose the app language in Settings; on Android 13+, it stays synchronized with Android’s per-app language setting.

## Source and credits

Based on [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI and website adapt [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is included in `docs/licenses`. Preserve those notices when distributing modifications. CarPlay and its icon belong to Apple Inc.; no Apple or BYD affiliation or endorsement is implied.

This repository starts with a clean public source snapshot. Local research, tester reports and release-signing secrets are excluded. The complete source corresponding to the APK is provided with every release; experimental runtime identity assets are described separately in the build instructions and notices.

## Local release packaging

The release APK intentionally contains the experimental accessory identity. The Git repository and source archive exclude all accessory and Android signing keys; tests generate synthetic identities at runtime. Source/CI builds omit runtime identity assets by default. Local release builds explicitly select an external asset directory. Publishing the APK makes its bundled identity extractable; building locally does not preserve that identity's confidentiality.

