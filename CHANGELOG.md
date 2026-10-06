# Changelog

This file records **this fork's** releases only. Upstream DiPlay maintains its own changelog in
[shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay).

**Base:** upstream DiPlay **v0.2.7**, via `programmerguohuajing/DiPlay-Legacy-Android`.
**Target:** Geely Xingrui E01 (ECARX E01 / MediaTek MT6735, GKUI 19, **Android 5.1 / API 22**).

This is the fork's first published release. Earlier `-geely-rc` tags were withdrawn before release,
so everything the fork adds is listed under it.

---

## 0.2.9.1-geely-rc — 2026-10-06

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

- **New: wireless startup diagnostics.** When a wireless connection stalls, the report now says which
  step it stalled at, alongside interface state (up/down, multicast, usable address families),
  Bonjour discovery counters and kernel receive/UDP counters. Observation only — it does not change
  connection deadlines, address selection or retry behaviour.
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
