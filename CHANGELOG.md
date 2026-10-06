# Changelog

This file records **this fork's** releases only. Upstream DiPlay maintains its own changelog in
[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay).

**Base:** upstream DiPlay **v0.2.7**, via `programmerguohuajing/DiPlay-Legacy-Android`.
**Upstream alignment:** every entry below states the upstream DiPlay version it was checked
against. The base never moves — it is what makes this fork installable on old head units — so the
alignment line, not the base line, is what says how current a release is.
**Target:** Geely Xingrui E01 (ECARX E01 / MediaTek MT6735, GKUI 19, **Android 5.1 / API 22**).

---

## 0.3.1-geely-rc — 2026-10-06

**Upstream alignment:** checked against DiPlay **v0.2.13** (483 commits ahead of the v0.2.7 base).
That release added 19 commits over the previously screened point, of which exactly one is portable
here; the rest is Traditional Chinese translations and upstream-only documentation.

### Wireless

- **Fix wireless CarPlay failing on head units with no NSD service.** The Bonjour advertiser looked
  Android's NSD service up eagerly while constructing, even though this fork advertises over
  interface-bound mDNS (`useInterfaceMdns = true`) and never touches NSD. Where the service is
  absent the lookup returned null and the whole wireless stack failed before it started. The lookup
  is now deferred and nullable. Ported from upstream `32550b2`. Not yet verified on the car — the
  change can only remove a failure, it adds no behaviour to observe.

### Build

- **`gradlew` regains its executable bit.** Both workflows invoke `./gradlew`, which had been
  failing with `Permission denied` on every push; local runs used `sh gradlew`, so it stayed hidden
  and `Android checks` was red throughout.
- **Releases are now automatic.** Pushing to `main` derives the tag from `versionName` and publishes
  the APK with `SHA256SUMS.txt`; a version that already has a Release is skipped. Publishing is
  gated on the tag matching `versionName`, the release notes existing, the source tree being free of
  credentials, and the APK carrying a valid v1+v2 signature and the accessory identity.

---

## 0.3.0-geely-rc — 2026-10-06

**Upstream alignment:** checked against DiPlay **v0.2.12 + unreleased** (464 commits ahead of base).

### Diagnostics

- **New: wireless startup diagnostics.** When a wireless connection stalls, the report now says which
  step it stalled at, alongside interface state (up/down, multicast, usable address families),
  Bonjour discovery counters and kernel receive/UDP counters. Observation only — it does not change
  connection deadlines, address selection or retry behaviour. The watchdog that would have armed a
  timeout after the start-session request is deliberately **not** included, since that changes
  behaviour rather than observing it.

### Documentation

- README, README.zh-CN and this changelog now describe **this fork only**. Upstream's BYD feature
  lists, its website links and its preview copy are gone; the GPL-3.0 / AGPL-3.0 attribution and the
  accessory-identity disclosure are retained.
- The compatibility, install, privacy, connection-setup and testing docs no longer carry BYD-only
  sections.

---

## 0.2.9.1-geely-rc — 2026-10-06

**Upstream alignment:** checked against DiPlay **v0.2.7 + unreleased**.

The fork's first published release. Earlier `-geely-rc` tags were withdrawn before release, so
everything the fork added up to that point is listed here.

### Steering wheel — verified on the car

- Fix the steering wheel doing nothing on a Geely head unit. The wheel goes through the car's own
  input service, which re-emits each press as a standard `ACTION_MEDIA_BUTTON`, but DiPlay's media
  session declared `flags=0` and had no media-button receiver, so the framework gave every press to
  whichever app held the wheel before. Declaring `FLAG_HANDLES_MEDIA_BUTTONS` and setting a
  media-button receiver fixed it. Confirmed on the car: `dumpsys media_session` went from
  `flags=0 / mediaButtonReceiver=null` to `flags=3 / PendingIntent{...}`, and next/previous track now
  reach CarPlay. This wheel has no play/pause key — that position is the head unit's screen-mirroring
  button, which never enters the Android key pipeline.
- The generic accessibility key filter and the ECARX vendor key codes remain as fallbacks for other
  head units. Neither is needed here; GKUI 19 on this car exposes no accessibility settings entry.
- Complete the missing Arabic, Russian and Spanish translations for the wheel-key strings.

### Wireless

- Switch the car's own hotspot on instead of leaving the driver to do it in the car settings. The
  head unit's existing hotspot configuration is kept, and Wi-Fi station mode is released only on a
  retry, since that disconnects the car from whatever network it is on. Failures are now reported by
  kind: firmware withholding the permission, the car refusing the request, or the hotspot not
  reporting itself enabled in time.

### USB and wired

- Fix the USB attach filter missing the Apple vendor id, so plugging in an iPhone cold did not launch
  DiPlay.
- Tolerate the 4-byte padding after a USBMUX control reply, which used to abort the connection with a
  protocol error.
- Add USB permission auto-confirm (an accessibility service) to remove the system's authorisation
  prompt. It answers only the system dialog, never on behalf of another app.
- Fix wired connections being rejected whenever manual hotspot mode was configured: the runtime
  config demanded a hotspot SSID even when the connection was wired. The check now applies to
  wireless only.

### Audio and touch

- Raise the UDP receive buffer to 512 KB and add a stall timeline (largest inter-arrival gap,
  sequence gaps).
- Disable Nagle's algorithm on the touch event channel so gestures no longer arrive in batches.

### Diagnostics

- Export reports on head units with no file picker, with three levels of fallback and an on-screen
  view that can be copied by long-press.

### Head unit compatibility

- Stop the SOME/IP HUD bridge retrying a service a Geely head unit can never reach: it now checks its
  gateway package is installed before binding. The retry used to run every 300 ms.
- Fix the Android 5 settings page, where switches rendered their on/off captions incorrectly.
- Recognise short-form component names when reading whether an accessibility service is enabled.

---

## Base — upstream DiPlay v0.2.7

Everything not listed above is upstream's work, carried over unchanged. That includes features
specific to BYD head units (DiLink cluster projection, BYD HUD navigation, CAN battery reporting),
which are **out of scope for this fork** rather than pending — this head unit has no counterpart
hardware. The code stays in the tree so upstream syncs remain cheap.
