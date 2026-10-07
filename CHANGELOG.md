# Changelog

This file records **this fork's** releases only. Upstream DiPlay maintains its own changelog in
[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay).

**Base:** upstream DiPlay **v0.2.7**, via `programmerguohuajing/DiPlay-Legacy-Android`.
**Upstream alignment:** every entry below states the upstream DiPlay version it was checked
against. The base never moves — it is what makes this fork installable on old head units — so the
alignment line, not the base line, is what says how current a release is.
**Target:** Geely Xingrui E01 (ECARX E01 / MediaTek MT6735, GKUI 19, **Android 5.1 / API 22**).

---

## 0.3.2-geely-rc — 2026-10-07

**Upstream alignment:** checked against DiPlay `main` `e2fd8ea` (2026-10-07), 51 commits after
**v0.2.13**. Four of those commits were ported. The rest stay upstream.

### Head unit compatibility

- **Wireless CarPlay is gone from the UI on the E01, because this head unit cannot run it.**
  Wireless CarPlay carries its iAP2 leg over a Bluetooth RFCOMM socket. This head unit's Bluetooth
  stack offers third-party apps no such socket, and the omission is deliberate rather than
  unfinished — three independent removals agree: the AOSP-derived stack in `XCBTService` has the
  socket classes taken out (`createRfcommSocketToServiceRecord`, `listenUsingRfcommWithServiceRecord`
  and `fetchRemoteUuids` are all absent while `getBondedDevices` and `startDiscovery` remain), the
  ECARX facade declares `bt/spp/ISpp` and `ISppCallback` on the boot classpath without ever writing
  a `SppProxy`, and the GOC SDK's `CommandSppImp` returns a hardcoded `false` from every entry
  point. The vendor daemon `/system/bin/gocsdk` does implement SPP, but its 119-command vocabulary
  has no SDP query, no custom UUID and no raw RFCOMM, so it cannot reach the iPhone's iAP2 service
  either — and iOS opens no standard SPP to a non-MFi accessory. On the car, `reqSppConnect` to the
  paired iPhone returns `false` and the SPP service reports `isSppServiceReady() = 0`.

  **Bluetooth itself is unaffected.** Hands-free calls and music keep working, and a third-party
  app can still read the adapter state, the connected device and the local address. What is
  unavailable is a byte pipe over Bluetooth, and on this head unit the only thing that needs one is
  wireless CarPlay. The home screen, Connection setup and the in-session settings now offer USB
  alone, and `AirPlayPersistence.loadWirelessEnabled` is clamped to `false` so a value stored by an
  earlier build cannot start a connection the hardware has no way to complete. Other head units are
  untouched: the wireless switch and the hotspot controls still appear wherever the stack supports
  them. Wired USB CarPlay is unchanged and remains the supported path here.

### Audio

- **Navigation guidance reaches the right stream on the E01.** The fork defaulted the navigation
  stream to 14, BYD's driver-speaker stream. The E01 renumbers the vendor streams: there 14 is
  `STREAM_FM`, the FM tuner, and spoken guidance belongs on `STREAM_NAVI_TTS` at 10, which its own
  `AudioManager` declares. The default now follows the head unit. An installation that already
  stored a value keeps it — `getInt` only falls back to the default when the key is absent — so
  re-select the stream once in settings after updating.

- **Music yields while another app holds audio focus for a call.** A transient focus loss sets the
  media track gain to 0; ducking sets it to 0.2; a later gain restores 1. Phone and navigation
  tracks are left alone, and a permanent loss does not change the gain. The fork still requests
  focus with the API 8 call, because `AudioFocusRequest` is API 26. There is no settings switch;
  upstream's default is on. Adapted from `#339` (`5a05b2e` and the follow-up focus fixes). The
  BYD hang-up broadcast was not ported.

### Wireless

- **RFCOMM reports a missing stream and a stall instead of failing inside the platform getter.**
  The duplex stream now owns the connected socket, so a failed getter cannot leave a second owner.
  Ported from `5fc7a7a`. Not yet verified on the car.

### USB

- **A four-byte USBMUX trailer is also accepted before a captured protocol-1 diagnostic frame,**
  in addition to the TCP replies already handled. A malformed diagnostic is still a protocol
  error. Ported from `5fc7a7a`.

### Diagnostics

- **Video receive logs now include decrypt time** (`decryptAvgUs`, `decryptMaxUs`, `decryptMBps`).
  Ported from `5b2fb6e`. The platform ChaCha20-Poly1305 path was not ported: that cipher is API 28,
  so on this head unit it would only fall back to the BouncyCastle implementation already in use.

### Not ported from this grandfather range

- Scheduled day/night mode, because `CarPlayNightMode` is not on this branch yet.
- Assignable Siri wheel key, because it depends on the wheel-key learning UI this fork does not carry.
- Custom image picker, because that settings surface is not on this branch.
- Software video windows, because they depend on view-area layout this fork does not carry.
- USB read-queue size fallback, because it targets `UsbRequest.queue(ByteBuffer)` (API 26) and this
  fork's supported range stops at Android 7.
- Traditional Chinese, website pages, and release-site commits.

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
