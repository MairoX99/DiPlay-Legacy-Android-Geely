# Geely compatibility — unreleased

- Geely (ECARX) steering-wheel media keys. A Geely wheel is not delivered as a standard
  `ACTION_MEDIA_BUTTON` broadcast, so it never reached DiPlay and no priority change could help.
  DiPlay now tries two independent paths. The generic one is an accessibility service that filters
  key events, which sees the wheel before the car's window manager can keep it, and needs no vendor
  SDK at all; the driver switches it on in the accessibility settings. The other claims the wheel
  through the head unit's own input service (`com.ecarx.xui.adaptapi.input`), retrying six times
  five seconds apart. Both recognise the vendor key codes these wheels send
  (`200085`/`200087`/`200088`/`200231`, plus the `110000`/`210000` seek pairs), and one press
  reported on both paths still produces one CarPlay press. Whichever path logs the press is the one
  this car uses; a head unit with neither behaves exactly as before.
- Switch the car's own hotspot on instead of leaving the driver to do it in the car settings. The
  head unit's existing hotspot configuration is kept, and Wi-Fi station mode is released only on a
  retry, since that disconnects the car from whatever network it is on. When it does not come on,
  the message now says which failure it was: the firmware withholding the permission, the car
  refusing the request, or the hotspot not reporting itself enabled in time.
- Fix the Android 5 settings page: switches no longer render their on/off captions.
- Fix wired connections being rejected when manual hotspot mode was configured; the check now
  applies to wireless only.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
