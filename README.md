# Veyarc

Record taps and swipes on Android, turn them into editable workflows, and replay them on your device.

## Features

- Record actions and app switches. Pause to add screen checks, then continue recording where you choose.
- Add waits, image checks, variables, conditions, and loops. Reuse templates and keep saved revisions.
- Save block sequences as linked ranges or independent copies. Choose starting values or preset options before a run.
- Organize workflows and runs with folders and search. Review step activity, errors, and image-match scores.
- Ask AI to explain, create, or edit workflows. Attach photos or files; crop, rotate, and annotate images.
- View run statistics and AI usage, including tokens and estimated API costs.
- Import/export workflows and runs, and export statistics as CSV.

## Get started

1. Open **Settings → Guide** for permissions, Shizuku, and edge latch setup.
2. Record a short example or create a workflow in the editor.
3. Edit the blocks yourself or ask AI to help.
4. Review, save, and test. Use the edge latch or stop notification to end a run.

## Recording and replay

- **Recording.** Shizuku reads your touch positions and timing. Veyarc tracks app switches and converts the recording into editable blocks. System screens and Veyarc's own controls are skipped.
- **Replay.** Veyarc launches apps and follows the workflow's waits, conditions, and loops. Android Accessibility sends taps, holds, and swipes at the saved coordinates. Image checks use saved templates or images captured earlier in the run.

## AI and local data

Recording, editing, and playback work offline. Saved workflows, images, recordings, and chats are encrypted on your device. No Veyarc account, cloud sync, or analytics.

Use your own API key from a supported provider or a compatible custom endpoint. Messages, workflow context, and attachments go directly to that provider.

Review AI changes, then choose **Apply and save** to create a revision. Running is separate; AI cannot run workflows or change device permissions for now.

## Requirements and limits

- Playback needs Android 7+ and Accessibility. Android 7.0–7.1 support short taps only.
- Recording needs Android 13+, Shizuku, and a compatible phone.
- Image checks require Android screen-sharing approval.
- Workflows use coordinates and images, with one finger at a time. Split-screen and floating windows are unsupported.
- Portrait and landscape use separate workflow variants. Rotating between them stops recording or playback.
- Only allowed apps receive workflow touches. Leave the app untouched during playback to avoid replay complications.

Physical testing currently covers vivo V50 on Android 16. See [device compatibility](docs/android-device-compatibility.md) for details.

## Build

The Kotlin project is in [`apps/android`](apps/android). With JDK 17 and the Android SDK configured, run there:

```powershell
.\gradlew.bat :app:assembleDebug
```

APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`.

See the [Android developer README](apps/android/README.md) for device tests and release signing.
