# Android device compatibility

Status date: 2026-10-09

This document records what Veyarc has actually verified. An unlisted device may work, but it is not a supported claim.

## Verified configurations

| Target | Configuration | Verified result |
| --- | --- | --- |
| vivo V50 | Android 16, OriginOS 6, model reported as vivo V50jr | Shizuku direct-touch discovery, cross-app single-pointer recording, encrypted recording journals, workflow conversion, same-orientation raw replay, portrait and landscape coordinate mapping, 180-degree replay, visual conditions, stop controls, repeated replay, and ordinary input after cleanup were manually verified. |
| Android emulator | `Veyarc_API_37`, Android 17, 540 x 1196 logical display | Accessibility binding and tap, long-press, sampled-path dispatch pass. Host-mouse capture/replay and the current API 37 instrumentation suite pass. |

Recent API 37 checks also cover editor/chat layouts, run details, recording-to-editor navigation, and service cleanup. The refreshed foreground-app recording path still needs a physical check for OEM event timing. The emulator result does not prove Shizuku or OEM input-device behavior. The vivo result does not imply support for other vivo builds.

## Android version support

| Android version | Playback | Exact recording | Visual conditions |
| --- | --- | --- | --- |
| Android 7.0 to 7.1, API 24 to 25 | Short taps up to 100 ms only | Unsupported recording target | Available when Android supplies MediaProjection, but not part of current device acceptance |
| Android 8.0 to 12, API 26 to 32 | Taps, long presses, and swipe paths | Unsupported recording target | Available when Android supplies MediaProjection, but not part of current device acceptance |
| Android 13 and newer, API 33+ | Taps, long presses, and swipe paths | Device dependent; requires Shizuku access to a direct touchscreen event node | Supported with fresh consent for each run |

The APK has `minSdk 24`. The recording support policy begins at API 33 because foreground-service, notification, OEM input, and lifecycle behavior below that level have not passed the acceptance matrix.

The API 24 and 25 restriction has automated policy coverage. The full API 24 emulator instrumentation suite passed; this does not verify physical recording on an API 24 device.

## Device-dependent recording gate

Recording starts only when all runtime checks pass:

1. Notifications and the recording channel are enabled.
2. The display uses a full-screen window mode.
3. Shizuku is running and Veyarc is authorized.
4. The helper identifies one direct touchscreen event node with valid X and Y ranges.
5. The foreground service and independent stop control become ready before raw observation starts.

The recorder does not install a touch-intercepting overlay. It reads one pointer's direct touchscreen stream without changing the original route to the foreground app. It stops on lock screen, rotation, helper/service loss, timeout, or malformed input. A normal stop converts the trace to a saved workflow. Control-surface touches are discarded.

## Replay and display rules

- Workflows use normalized full-display coordinates.
- Portrait and landscape recordings are separate variants. Cross-orientation replay is refused.
- A settled 180-degree rotation in the same orientation family is supported.
- Rotation during a recording or replay stops the operation.
- Split-screen, freeform, and desktop window modes are unsupported.
- The edge latch is pass-through and does not freeze the target app. A recorded point that collides with the latch is refused before replay.
- API 24 and 25 expose short taps only. API 26 added continued strokes, which Veyarc needs to interrupt longer gestures safely.

## Unsupported fidelity

Veyarc records and replays ordered down, move, and up coordinates with timing. It does not reproduce simultaneous multitouch, pressure, touch radius/contact area, tool type, or the physical shape of a finger. Fast input and small touch targets can therefore be less forgiving than slow input with generous margins.

## Untested device families

Google Pixel, Samsung Galaxy, OPPO/ColorOS, Xiaomi/MIUI or HyperOS, and other vivo builds have not completed the physical acceptance checklist. No support claim is made for them. OEMs may expose different input nodes, coordinate ranges, Shizuku restrictions, background limits, or accessibility behavior.

Use the [physical-device acceptance fixture](../apps/android/acceptance/README.md) for each device and OS build. Record the model, Android version, OEM build, display and orientation, scenario results, cleanup result, and any service or permission failure without including screenshots or raw touch data.

## Removed unsafe approach

An early Android 13 accessibility experiment requested touch exploration and motion-event reporting to capture touches. On the vivo V50 it changed normal one-finger input and could leave the screen effectively unresponsive. That path is disabled and is not the recorder.

Strict display freeze was also removed. A normal APK cannot safely block human input while allowing its own injected input through the same Android window route. Replay remains touch pass-through and relies on visible stop controls.
