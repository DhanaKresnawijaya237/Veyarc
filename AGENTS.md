# Repository safety rules

## Device and accessibility safety

Accessibility, touch-input routing, screen interception, replay injection, boot/recovery behavior,
and device health are safety-critical. Before changing any code that can affect them:

- inspect the complete control path from service startup through teardown and process death;
- identify whether the change can alter global touch handling, accessibility mode, navigation, lock
  screen behavior, boot stability, battery/thermal load, or persisted device settings;
- prefer a capability-gated, opt-in, fail-closed design with an independent emergency stop;
- prove that every completion, cancellation, timeout, interruption, unbind, disconnect, crash, and
  restart path restores ordinary device input and accessibility state;
- do not make persistent accessibility or input-mode changes merely to fix a replay lifecycle
  issue; any temporary mode change must have an explicit restoration path and a recovery fallback;
- review the relevant Android/OEM behavior and tests before building or installing an APK;
- do not install on a physical device until the risk, rollback steps, and recovery procedure are
  documented and the user has explicitly approved the device test;
- after installation, verify device responsiveness and ordinary system navigation before testing
  replay behavior; stop immediately if touch, navigation, or accessibility behaves abnormally.

If a change could affect device input or health and its lifecycle cannot be demonstrated safe,
leave the feature disabled and ask for direction rather than shipping or installing it.
