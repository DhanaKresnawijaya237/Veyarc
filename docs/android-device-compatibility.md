# Android device compatibility findings

Status date: 2026-08-24

This report covers the Phase 0 feasibility spike. It records observed behavior, known limits, and the tests still required before the workflow editor is expanded. The first beta is scoped to the vivo V50 target; untested OEM families are not treated as supported.

## Verified

| Target | Configuration | Result |
| --- | --- | --- |
| Android emulator | `Veyarc_API_37`, Android 17, 540 × 1196 logical display | Accessibility service binding and gesture dispatch pass. Tap, long-press, and rectangular-path dispatch complete. A real host-mouse capture and replay also completes after the replay-timing fix in `4a3621e`. |
| vivo V50 | Android 16, OriginOS 6, device name `vivo V50jr`, Snapdragon 7 Gen 3, 12 GB + 12 GB RAM, 256 GB storage | User-verified physical-device test: the Veyarc accessibility service enabled successfully, touch capture and replay completed, replay timing was reported acceptable, and normal operation remained available after the requested lifecycle checks. |

The emulator result proves that the adapter can bind, dispatch, capture, replay, and restore normal pass-through handling in this configuration. It is not a substitute for OEM testing.

## Global recording rework (safety result)

The Phase 3 recorder is being changed from the one-gesture intercept/replay experiment to a
service-owned app-scoped session. The current recorder entry point is a fail-closed placeholder;
no global input source, notification action, or touch overlay is registered. The actual global
input source is currently disabled.

The first passive approach requested Android 13+ `FLAG_REQUEST_TOUCH_EXPLORATION_MODE` and
`FLAG_SEND_MOTION_EVENTS` and consumed completed `AccessibilityGestureEvent` motion batches. On
the Vivo V50 this changed ordinary one-finger input into accessibility touch exploration: the
screen remained visually alive and foreground observations continued, but normal app touches and
even the floating stop control could not be used. That is an implementation bug, not acceptable
device behavior.

The current build therefore fails closed before changing any accessibility flags. It reports that
transparent global recording is unavailable rather than risking another input freeze. Re-enabling
recording requires a source that can expose raw coordinates while passing the original touch stream
through unchanged; the public Accessibility API path used by this spike has not proven that
property.

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
multi-touch protocol, and stream encrypted records to Veyarc while the selected third-party
package remains foreground. This is a proposed spike, not a support claim: root or a shell-level
capability may be required, device SELinux policy can deny access, and coordinate calibration and
raw replay must be proven on the vivo V50 before the adapter is enabled.

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

- Playback dispatch is available from API 24. The original one-gesture intercept/replay experiment uses `TouchInteractionController` on API 33+, while the proposed third-party recorder uses a privileged read-only evdev path instead of accessibility touch exploration.
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
2. A start action can be issued while the selected third-party app is foreground and no Veyarc
   activity is visible.
3. The target app remains interactive while recording; the helper never requests touch exploration,
   consumes Android touch callbacks, or replays input during capture.
4. Every supported rapid single-pointer down/move/up sequence arrives as a complete ordered raw
   frame and is written with a monotonic timestamp without reading keyboard or power devices.
5. Leaving the target package, Home, Recents, a lock screen or a system dialog fails the recording.
6. Notification stop, emergency stop, service disconnect, helper death and process restart leave no
   stale observer, overlay, notification action, open device node or modified accessibility flag.

Until those checks pass with a genuinely pass-through source, exact third-party recording remains
capability-gated and unavailable. Manual workflow authoring and post-recording playback remain
available.

If a tested device family fails these checks, Veyarc keeps manual workflow authoring and playback available but marks exact recording unsupported for that device/build. It must not save a partial trace as a valid workflow. Untested families remain unverified rather than implicitly supported.

## Phase 0 result

For the first beta's scoped vivo V50 target, the original accessibility playback/intercept diagnostic
gate is satisfied: the service binds, capture and replay work on a physical device, and replay timing
is acceptable in the reported test. The production third-party recorder is not yet supported: the
touch-exploration path failed the pass-through safety test, and the privileged evdev spike remains
to be implemented and verified. Pixel, Samsung, and OPPO validation, along with numeric latency
instrumentation, remain future hardening work.
