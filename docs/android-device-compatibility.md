# Android device compatibility findings

Status date: 2026-08-22

This report covers the Phase 0 feasibility spike. It records observed behavior, known limits, and the tests still required before the workflow editor is expanded. The first beta is scoped to the vivo V50 target; untested OEM families are not treated as supported.

## Verified

| Target | Configuration | Result |
| --- | --- | --- |
| Android emulator | `Veyarc_API_37`, Android 17, 540 × 1196 logical display | Accessibility service binding and gesture dispatch pass. Tap, long-press, and rectangular-path dispatch complete. A real host-mouse capture and replay also completes after the replay-timing fix in `4a3621e`. |
| vivo V50 | Android 16, OriginOS 6, device name `vivo V50jr`, Snapdragon 7 Gen 3, 12 GB + 12 GB RAM, 256 GB storage | User-verified physical-device test: the Veyarc accessibility service enabled successfully, touch capture and replay completed, replay timing was reported acceptable, and normal operation remained available after the requested lifecycle checks. |

The emulator result proves that the adapter can bind, dispatch, capture, replay, and restore normal pass-through handling in this configuration. It is not a substitute for OEM testing.

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

- Playback dispatch is available from API 24. Exact coordinate recording uses `TouchInteractionController` and is available only from API 33.
- Accessibility must be enabled by the user. The app cannot silently grant its own accessibility permission.
- The service stays in normal pass-through mode while idle. It requests touch exploration only during a one-gesture capture.
- A previous implementation restored the service flag on a fixed 250 ms timer. Replays longer than about 250 ms could then end with `Replay failed: Cancelled`. Commit `4a3621e` holds the flag until replay completes and uses a duration-based safety timeout.
- `adb shell input` and emulator-console injection can produce `ACTION_CANCEL`. Those inputs are useful for lifecycle checks but do not count as real capture/replay proof.
- Numeric replay-latency instrumentation is not included in this feasibility build. The user reported replay timing as acceptable on the V50 during the physical test. Timestamp instrumentation remains future hardening if broader device support is added.

## Device-gating rules

Recording is enabled only when all of these checks pass:

1. The device runs Android 13 or newer.
2. Veyarc's accessibility service is enabled and connected.
3. The service can request touch exploration and register a `TouchInteractionController` callback.
4. A real single-pointer test reaches `Completed` at least once on the tested device build.
5. Touch exploration returns to its original state after completion, cancellation, service unbind, and process restart.

If a tested device family fails these checks, Veyarc keeps manual workflow authoring and playback available but marks exact recording unsupported for that device/build. It must not save a partial trace as a valid workflow. Untested families remain unverified rather than implicitly supported.

## Phase 0 result

For the first beta's scoped vivo V50 target, the feasibility gate is satisfied: the service binds, capture and replay work on a physical device, and replay timing is acceptable in the reported test. Pixel, Samsung, and OPPO validation, along with numeric latency instrumentation, remain future hardening work and are not blockers for starting the workflow core.
