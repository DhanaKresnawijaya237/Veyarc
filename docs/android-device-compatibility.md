# Android device compatibility findings

Status date: 2026-08-21

This report covers the Phase 0 feasibility spike. It records observed behavior, known limits, and the tests still required before the workflow editor is expanded. It does not claim physical-device support where no device test has been run.

## Verified

| Target | Configuration | Result |
| --- | --- | --- |
| Android emulator | `Veyarc_API_37`, Android 17, 540 × 1196 logical display | Accessibility service binding and gesture dispatch pass. Tap, long-press, and rectangular-path dispatch complete. A real host-mouse capture and replay also completes after the replay-timing fix in `4a3621e`. |

The emulator result proves that the adapter can bind, dispatch, capture, replay, and restore normal pass-through handling in this configuration. It is not a substitute for OEM testing.

## Physical-device matrix

The following devices are required for the first compatibility pass. No physical device was connected during this report, so these entries remain pending.

| Device family | Android/OEM behavior to check | Required result |
| --- | --- | --- |
| Google Pixel | Android 13 or newer; API 33+ touch interception and replay | A real single-pointer gesture reaches `Completed`, with no stuck touch exploration after release. |
| Samsung Galaxy | One UI accessibility-service lifecycle, battery restrictions, and API 33+ replay | Same as Pixel, including after the app is backgrounded and reopened. |
| vivo | Funtouch OS or OriginOS accessibility permissions, background restrictions, and API 33+ replay | Same as Pixel, or recording is marked unsupported for the tested build and family. |
| OPPO | ColorOS accessibility permissions, background restrictions, and API 33+ replay | Same as Pixel, or recording is marked unsupported for the tested build and family. |

For each physical test, record manufacturer, model, Android version, OEM build, display size, orientation, gesture sample count, gesture duration, replay result, and any service disconnect or permission prompt.

## Known behavior and failure modes

- Playback dispatch is available from API 24. Exact coordinate recording uses `TouchInteractionController` and is available only from API 33.
- Accessibility must be enabled by the user. The app cannot silently grant its own accessibility permission.
- The service stays in normal pass-through mode while idle. It requests touch exploration only during a one-gesture capture.
- A previous implementation restored the service flag on a fixed 250 ms timer. Replays longer than about 250 ms could then end with `Replay failed: Cancelled`. Commit `4a3621e` holds the flag until replay completes and uses a duration-based safety timeout.
- `adb shell input` and emulator-console injection can produce `ACTION_CANCEL`. Those inputs are useful for lifecycle checks but do not count as real capture/replay proof.
- No replay-latency measurement has been instrumented yet. The next physical-device pass must timestamp capture completion, replay dispatch, and the final callback.

## Device-gating rules

Recording is enabled only when all of these checks pass:

1. The device runs Android 13 or newer.
2. Veyarc's accessibility service is enabled and connected.
3. The service can request touch exploration and register a `TouchInteractionController` callback.
4. A real single-pointer test reaches `Completed` at least once on the tested device build.
5. Touch exploration returns to its original state after completion, cancellation, service unbind, and process restart.

If a device family fails these checks, Veyarc keeps manual workflow authoring and playback available but marks exact recording unsupported for that device/build. It must not save a partial trace as a valid workflow.

## Remaining Phase 0 work

- Run the same capture/replay test on representative Pixel and Samsung devices.
- Add vivo and OPPO devices when hardware or a reliable remote test device is available.
- Measure replay latency and document per-family failures.
- Keep the editor and later workflow phases gated until the physical-device results are recorded.
