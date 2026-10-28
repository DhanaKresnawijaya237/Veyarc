# Physical-device acceptance

Copy `physical-device-result.template.json` for each device and OS build. Fill in every field after the run. Keep screenshots, raw coordinates, screen content, and other personal data out of the result file.

## Before testing

1. Confirm the device is responsive and ordinary system navigation works.
2. Record the Android API level and OEM build.
3. Confirm Veyarc has no active recording, replay, screen-capture session, notification, or open control panel.
4. Keep the lock button and Veyarc emergency notification available. Stop immediately if touch or navigation behaves abnormally.

## Scenarios

### Playback short tap

Run the relevant instrumentation tests first. On API 24 or 25, test a short tap workflow only. Long presses and swipe paths must be rejected before replay because those versions cannot interrupt a continued gesture safely. On API 26 or newer, run tap, long-press, and path workflows.

Expected result: the supported gesture completes once, the target receives no extra input, cancellation ends the run, and a second run works without restarting Veyarc.

### Recording pass-through

This scenario requires API 33 or newer and a user-started Shizuku service. Start recording from Veyarc, use one non-sensitive target app, stop through the independent control, and repeat once.

Expected result: the target remains interactive, ordered touch frames are saved, the notification stays visible, and both runs clean up their observer and foreground service.

### MediaProjection revocation

Use a workflow with one visual condition. Grant Android's screen-capture prompt for that run, then test normal completion. Repeat and stop capture from its notification while the run is active.

Expected result: normal completion releases the session. Revocation fails the run closed, clears the in-memory frame source, and does not reuse consent on the next run.

## Required cleanup check

After every scenario, verify ordinary touch and system navigation, then confirm there is no Veyarc notification, overlay, active capture, or Shizuku observer. Veyarc must not have changed the device's accessibility setting. Mark the result failed if any cleanup field cannot be confirmed.
