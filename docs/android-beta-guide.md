# Android setup and safety guide

Status date: 2026-09-25

Veyarc `1.0.0` is a sideloaded release with limited verified device support. Playback installs on Android 7.0 and newer. Exact touch recording is supported only on tested Android 13 or newer devices where Shizuku can read the direct touchscreen input device. The verified physical target is the vivo V50 running Android 16 and OriginOS 6.

## Install and update

Install a signed APK from a trusted build. Future updates must use the same signing key. Android will reject an APK signed with a different key unless you uninstall the existing app first, which removes Veyarc's local data. Export workflows you need to keep before uninstalling. A debug build and a release build usually use different keys and cannot update each other in place.

Android or Play Protect may warn about a sideloaded build because it did not come from Google Play. Do not bypass a warning unless you trust the APK source and have verified its SHA-256 hash and signing certificate.

## Required setup

1. Allow Veyarc notifications. Recording, replay, and screen capture refuse to start when their emergency notification cannot be shown.
2. Open Android Accessibility settings and enable **Veyarc gesture control**. Android requires this for workflow gesture playback and active-app safety checks.
3. Install and start Shizuku, then grant Veyarc access when Shizuku asks. Exact recording and raw-trace replay need this shell-identity helper.
4. Optional: add **Veyarc recording** to Quick Settings. The tile starts or stops a global recording without opening Veyarc.
5. Optional: enable the edge-latch control center in Veyarc. It stays on the left or right edge and provides recording and workflow-play controls.

A Shizuku service started through wireless debugging needs to be started again after a phone reboot. On Android 11 and newer, pairing is normally one-time and startup does not require a computer. Follow the [official Shizuku setup guide](https://shizuku.rikka.app/guide/setup/) because OEM behavior differs.

## Why each permission exists

| Access | Used for | What Veyarc does not do |
| --- | --- | --- |
| Notifications | Visible foreground-service status and independent stop controls | Veyarc refuses the related operation when notifications are unavailable. |
| Accessibility | Dispatching approved workflow gestures and reading the foreground package/window type for safety checks | The implementation does not traverse child views, read text, or use semantic selectors. |
| Shizuku authorization | Reading the direct touchscreen event stream for recording and running the bounded raw-input helper | Veyarc does not write to `/dev/input`, use `sendevent`, or enable accessibility touch exploration for recording. |
| Per-run screen-capture consent | Taking temporary frames for pixel and image conditions | Veyarc does not encode video or save captured frames. Android asks again for each run that uses a visual condition. |
| Launcher-app visibility | Finding installed launchable packages for **Launch app** workflow steps | This does not grant access to another app's private data. |

## Optional AI chat in debug builds

The development branch has a DeepSeek chat preview. Published v1 release builds do not include this network feature. Debug builds declare internet permission; ordinary recording, editing, and replay still work offline.

In Settings, add your own DeepSeek API key and choose **Enable AI**. Read the disclosure and confirm **Enable for this session**. Saving a key or enabling AI sends nothing by itself. **Test connection** asks again before sending a small fixed prompt with no workflow or chat history. DeepSeek charges your API account. No account-wide spending cap is enforced by Veyarc.

Open chat from the Editor. Send shares your message, completed DeepSeek exchanges from that chat, and a snapshot of the selected draft, including unsaved text. Use **Workflow context** to inspect shared blocks, packages, coordinates, labels, variables, and image reference names. Images, recordings, run history, earlier candidate JSON, local sample replies, and unrelated workflows are excluded. Review each proposal before **Apply to draft**. Apply does not Save or Run; use the editor controls explicitly afterward.

Stop, leaving chat, backgrounding, disabling AI, or replacing/removing the key cancels active work. Rotation retains the existing request without sending another. Restart requires enabling AI again and restores unfinished turns as Interrupted; Retry is always explicit. Stopping may not prevent provider charges.

Keys and chats are encrypted locally and excluded from workflow exports and Android backup. Removing the key does not revoke it at DeepSeek. Deleting local chats does not delete provider records. DeepSeek's retention and training policies apply; no zero-retention guarantee is made. Read the provider policy and API terms linked in Settings. Live provider acceptance is still pending.

## Record a workflow

1. Start recording from Veyarc, the edge latch, or the Quick Settings tile.
2. Use apps normally. Veyarc records one pointer's raw down, move, and up timeline plus foreground-package transitions.
3. Pause or resume from the edge-latch control center when needed.
4. Stop from the notification, Quick Settings tile, or edge latch. A valid recording saves directly as a workflow and inserts the first observed third-party target as its **Launch app** step.
5. Review the generated steps before replay. Multi-app recordings remain review material because raw replay currently requires one third-party target app.

Control-surface touches are excluded. Recording stops safely on lock screen, physical rotation, helper or foreground-service loss, timeout, or an unsupported window mode. One recording can run for at most five minutes.

## Replay safely

Review the workflow and its allowed packages before starting. Keep the emergency notification reachable. The edge latch can start the workflow selected in its settings; opening it during an active replay stops that replay before showing controls.

Veyarc does not freeze the whole display. The earlier full-screen interception experiment was removed because it could block normal device input. Human touches can therefore conflict with replay, so avoid touching the target app while a workflow runs.

Portrait and landscape are separate workflow variants. Start replay in the same orientation family used for recording. A settled 180-degree rotation is allowed, but rotating the device during recording or replay stops the operation. Split-screen, freeform, and desktop window modes are unsupported.

## Emergency stop and recovery

- Recording notification: **Stop recording**.
- Raw replay notification: **Pause/Resume replay** and **Stop replay**.
- Workflow notification: **Pause/Resume** and **Stop**.
- Quick Settings tile: start or stop recording.
- Edge latch: recording controls and the configured workflow-play action. Touching it during replay stops replay before opening the panel.

If touch or navigation behaves abnormally, lock the phone first. Do not keep testing. Restart the phone if ordinary input does not return, then disable Veyarc Accessibility or uninstall Veyarc before another test. Shizuku and Veyarc do not need to remain enabled for normal phone operation.

## Visual conditions

Pixel and image conditions use Android MediaProjection. Android shows **Record or share your screen with Veyarc** for every run that contains a visual condition. Veyarc cannot safely retain or silently reuse this consent.

Captured frames stay in memory only while the run evaluates its conditions. Image templates selected by the user are encrypted before storage. Full-screen coarse matching is intended for exact screenshots from the same device state. Use a cropped template or smaller search region when the surrounding screen can change.

## Local data and privacy

- The app manifest does not request internet access. Veyarc has no account, cloud sync, analytics, or assistant backend.
- Workflow bodies, image-template assets, and recording journals are encrypted with keys held by Android Keystore.
- Workflow names, revision metadata, and redacted run history are stored in the app-private local database. Run history does not store captured screen pixels or variable values.
- Android cloud backup and device-to-device transfer are disabled for all Veyarc app data.
- Deleting a workflow removes its revisions, encrypted workflow files, and associated run records. Uninstalling the app removes its local data and Android Keystore keys.

## Import and export

Settings offers workflow import and export controls. Exported `.veyarc` bundles contain a validated workflow revision and can be imported through Settings. Image templates can also be selected separately for visual conditions. Do not treat internal files copied from app storage as portable backups.

## Current limits

- Android 7.0 and 7.1: short taps only. Gestures longer than 100 ms are rejected because Android cannot safely interrupt continued accessibility strokes on these releases.
- Android 8.0 and newer: bounded taps, long presses, and swipe paths.
- Exact recording: supported claim begins at Android 13 and remains device/build dependent. It needs a readable direct touchscreen node through Shizuku.
- One pointer only. No simultaneous multitouch, finger pressure, contact-area reproduction, text capture, semantic UI selection, or arbitrary scripts.
- Tested physical support is limited to the vivo V50 configuration in the compatibility report. Other OEMs remain unverified.

See [Android device compatibility](android-device-compatibility.md) for the current evidence and [the acceptance checklist](../apps/android/acceptance/README.md) before testing a new device.
