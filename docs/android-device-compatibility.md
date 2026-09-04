# Android device compatibility findings

Status date: 2026-09-05

This report covers the Phase 0 feasibility spike. It records observed behavior, known limits, and the tests still required before the workflow editor is expanded. The first beta is scoped to the vivo V50 target; untested OEM families are not treated as supported.

## Verified

| Target | Configuration | Result |
| --- | --- | --- |
| Android emulator | `Veyarc_API_37`, Android 17, 540 × 1196 logical display | Accessibility service binding and gesture dispatch pass. Tap, long-press, and rectangular-path dispatch complete. A real host-mouse capture and replay also completes after the replay-timing fix in `4a3621e`. |
| vivo V50 | Android 16, OriginOS 6, device name `vivo V50jr`, Snapdragon 7 Gen 3, 12 GB + 12 GB RAM, 256 GB storage | User-verified physical-device test: the Veyarc accessibility service enabled successfully, touch capture and replay completed, replay timing was reported acceptable, and normal operation remained available after the requested lifecycle checks. |

The emulator result proves that the adapter can bind, dispatch, capture, replay, and restore normal pass-through handling in this configuration. It is not a substitute for OEM testing.

## Global recording rework (safety result)

The Phase 3 recorder is being changed from the one-gesture intercept/replay experiment to a
service-owned global session. The implementation uses a visible
foreground-service notification plus a Quick Settings start/stop control, a bounded Shizuku
observer callback, and the existing encrypted journal/analyzer. It does not register a touch
overlay or accessibility touch exploration, and it never replays while recording. The Vivo V50
cross-app capture and independent Quick Settings stop path have been physically verified. Draft
conversion and exact replay from the preserved trace remain future work. The item 19 cleanup path
was also smoke-tested on the V50: Quick Settings stop removed the controls, review returned, and
a new recording could start. Helper-death, five-minute timeout, and wider OEM lifecycle cases
remain unit-covered or future hardening rather than physically verified.

The first passive approach requested Android 13+ `FLAG_REQUEST_TOUCH_EXPLORATION_MODE` and
`FLAG_SEND_MOTION_EVENTS` and consumed completed `AccessibilityGestureEvent` motion batches. On
the Vivo V50 this changed ordinary one-finger input into accessibility touch exploration: the
screen remained visually alive and foreground observations continued, but normal app touches and
even the floating stop control could not be used. That is an implementation bug, not acceptable
device behavior.

The old public Accessibility API path remains disabled before it can change any accessibility
flags. The new recorder is capability-gated on the read-only Shizuku source and fails closed if
that source, the viewport, or an independent stop control is unavailable. Foreground-package
changes are recorded as metadata rather than terminating a global session; control-surface touches
are excluded from the trace. The source must expose raw coordinates while passing the original
touch stream through unchanged; the public Accessibility API path used by this spike has not
proven that property.

## Shizuku replay feasibility

The current debug build includes a deliberately bounded replay helper for the next feasibility
question: can a Shizuku shell-identity process inject fixed coordinates without using the unsafe
accessibility interception path? `ShizukuInputReplayUserService` accepts only validated taps and
swipes, invokes `/system/bin/input` through an argument list, and rejects arbitrary shell text,
`/dev/input` writes, `sendevent`, `uinput`, and workflow execution. The Veyarc accessibility test
surface exposes single-tap, single-swipe, two-taps-plus-swipe, status, and cancellation controls.

The API-24-safe implementation passes formatting, lint, unit tests, the full `verifyLocal` gate, and
debug APK assembly. The APK is installed and launches on the target vivo V50 (Android 16 / OriginOS
6). The user manually confirmed tap, swipe, quick-sequence, and cancellation behavior on the Veyarc
surface without freezing or leaving touch stuck. This spike does not yet prove third-party-app
orchestration, global recording, raw-trace fidelity, or production workflow replay.

The next feasibility path is a privileged, read-only Linux `evdev` observer. Android's input
pipeline is fed from these kernel event devices, and AOSP documents `getevent` with `su` as a way
to inspect the live stream. A production helper would open only the touchscreen device, parse its
multi-touch protocol, and stream encrypted records to Veyarc while foreground-package transitions
are sampled alongside the raw frames. This is a proposed spike, not a support claim: root or a
shell-level capability may be required, device SELinux policy can deny access, and coordinate
calibration and raw replay must be proven on the vivo V50 before the adapter is enabled.

The first read-only diagnostic for that spike is now implemented in the debug build. It uses only
fixed `getevent -lp` and `getevent -lt <selected-device>` operations through the Shizuku
UserService, selects a node with multi-touch X/Y ranges and `INPUT_PROP_DIRECT`, and reports the
node metadata plus transient single-pointer frame counters. This direct-input requirement avoids
Vivo's fingerprint nodes, which also expose coordinate-like ABS axes but do not receive screen
touches. It deliberately does not persist coordinates, inject input, change accessibility routing,
or enable the production recorder. The user has physically confirmed this corrected diagnostic on
the V50; a
discovery denial, missing ranges, malformed frames, or incomplete gestures keeps recording
unavailable.

## Physical-device matrix

The following OEM families are future validation targets and are not required to close the first-beta feasibility gate. No support claim is made for them.

| Device family | Android/OEM behavior to check | Required result |
| --- | --- | --- |
| Google Pixel | Android 13 or newer; API 33+ touch interception and replay | Future validation; not tested and not claimed as supported. |
| Samsung Galaxy | One UI accessibility-service lifecycle, battery restrictions, and API 33+ replay | Future validation; not tested and not claimed as supported. |
| vivo V50 | OriginOS 6, Android 16, API 33+ touch interception and replay | Verified for the first-beta target above. |
| OPPO | ColorOS accessibility permissions, background restrictions, and API 33+ replay | Future validation; not tested and not claimed as supported. |

For each physical test, record manufacturer, model, Android version, OEM build, display size, orientation, gesture sample count, gesture duration, replay result, and any service disconnect or permission prompt. The V50 report above records the available device and OS details; exact sample count, gesture duration, OEM build, and numeric latency were not captured.

## Known behavior and failure modes

- Short-tap playback is available from API 24. API 24 and 25 do not advertise long presses or swipe paths because Android cannot safely interrupt a gesture longer than 100 ms before API 26. API 26 and newer advertise the full bounded gesture set. The recorder uses a privileged read-only evdev path instead of accessibility touch exploration.
- Accessibility must be enabled by the user. The app cannot silently grant its own accessibility permission.
- The service stays in normal pass-through mode. The global recorder safety gate currently refuses
  to request touch exploration or motion-event reporting, because doing so can make one-finger input
  behave as hover input instead of reaching the target app.
- A previous implementation restored the service flag on a fixed 250 ms timer. Replays longer than about 250 ms could then end with `Replay failed: Cancelled`. Commit `4a3621e` holds the flag until replay completes and uses a duration-based safety timeout.
- `adb shell input` and emulator-console injection can produce `ACTION_CANCEL`. Those inputs are useful for lifecycle checks but do not count as real capture/replay proof.
- Numeric replay-latency instrumentation is not included in this feasibility build. The user reported replay timing as acceptable on the V50 during the physical test. Timestamp instrumentation remains future hardening if broader device support is added.

## Device-gating rules

The original intercept/replay experiment was enabled only when all of these checks passed:

1. The device runs Android 13 or newer.
2. Veyarc's accessibility service is enabled and connected.
3. The service can request touch exploration and register a `TouchInteractionController` callback.
4. A real single-pointer test reaches `Completed` at least once on the tested device build.
5. Touch exploration returns to its original state after completion, cancellation, service unbind, and process restart.

The privileged third-party recorder adds these stricter checks before it can be called supported on
a device build:

1. A root or shell-level capability can enumerate and read only the device's touchscreen evdev
   stream; the helper refuses to start if it cannot identify a direct multi-touch device and its
   coordinate ranges.
2. A start action can be issued from Veyarc or a service-owned Quick Settings/notification control,
   and the session can continue while no Veyarc activity is visible.
3. The target app remains interactive while recording; the helper never requests touch exploration,
   consumes Android touch callbacks, or replays input during capture.
4. Every supported rapid single-pointer down/move/up sequence arrives as a complete ordered raw
   frame and is written with a monotonic timestamp without reading keyboard or power devices.
5. Switching between third-party apps remains in the same trace; lock screen or unsafe system
   surfaces stop the recording, while control-surface touches are excluded.
6. Notification stop, emergency stop, service disconnect, helper death and process restart leave no
   stale observer, overlay, notification action, open device node or modified accessibility flag.

Until those checks pass with a genuinely pass-through source, exact third-party recording remains
capability-gated and unavailable. Manual workflow authoring and post-recording playback remain
available.

If a tested device family fails these checks, Veyarc keeps manual workflow authoring and playback available but marks exact recording unsupported for that device/build. It must not save a partial trace as a valid workflow. Untested families remain unverified rather than implicitly supported.

## Phase 0 result

For the first beta's scoped vivo V50 target, the original accessibility playback/intercept diagnostic
gate is satisfied: the service binds, capture and replay work on a physical device, and replay timing
is acceptable in the reported test. The read-only privileged evdev diagnostic is also physically
confirmed. The production global recorder's cross-app session and independent stop controls are
also physically verified on the V50. The touch-exploration path remains disabled because it failed
the pass-through safety test. Converting the preserved trace into an editable workflow and replaying
that trace exactly are still future work.
Pixel, Samsung, and OPPO validation, along with numeric latency instrumentation, remain future
hardening work.
