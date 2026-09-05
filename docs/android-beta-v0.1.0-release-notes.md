# Veyarc Android technical beta 0.1.0

Release status: candidate, not tagged

## Included

- Local, versioned workflow library and nested block editor.
- Coordinate taps, long presses, sampled swipe paths, waits, bounded loops, variables, app launch, and stop steps.
- Pixel, image-template, orientation, foreground-package, variable, step-result, and logical conditions.
- Global single-pointer recording through a Shizuku read-only touchscreen helper. A completed recording is saved as a workflow.
- Workflow and raw-trace replay with foreground-package, window-mode, lock-screen, notification, service, timeout, and orientation checks.
- Detailed local run history with redacted event messages and actual visual-match scores.
- Optional left/right edge-latch controls and independent foreground-service notifications.
- Signed sideload release packaging with an external keystore and no debug-signing fallback.

## Requirements

- Android 7.0 or newer for installation and playback.
- Android 13 or newer on a verified device/build for exact recording.
- User-enabled Veyarc Accessibility for gesture playback and foreground safety checks.
- A running, authorized Shizuku service for exact recording and raw-trace replay.
- Notifications enabled for recording, replay, and visual-condition emergency controls.
- Fresh Android screen-capture consent for every run that uses a visual condition.

## Safety and privacy

The recorder does not change accessibility input routing or install a full-screen touch interceptor. Recording and replay fail closed when a required service, stop control, orientation, or safe device state is lost. Workflow bodies, image templates, and recording journals are encrypted locally. The app requests no internet permission, has no account or analytics, and disables Android backup and device transfer.

## Known limits

- The physical beta target is the vivo V50 on Android 16 and OriginOS 6. Other OEMs are unverified.
- Android 7.0 and 7.1 support short taps only. Android 8.0 and newer support bounded long gestures and paths.
- Portrait and landscape are separate variants. Rotation during recording or replay stops the operation.
- No split-screen, freeform, desktop mode, multitouch, pressure, contact-area fidelity, semantic UI selectors, text capture, or strict screen freeze.
- Raw replay requires one recorded third-party target app. Multi-app recordings must be reviewed as editable workflows.
- External `.veyarc` workflow import/export UI is not included.
- Pixel and image conditions require Android's screen-capture prompt on every applicable run.

## Installation note

This is a sideloaded build. Verify the APK's SHA-256 hash and signing certificate before installation. Updates must use the same signing key. See the [beta guide](android-beta-guide.md) for setup and recovery steps and [device compatibility](android-device-compatibility.md) for the evidence behind the support claims.
