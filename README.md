<p align="center">
  <img src="apps/android/app/src/main/res/drawable-nodpi/veyarc_logo.png" alt="Veyarc logo" width="112">
</p>

<h1 align="center">Veyarc</h1>

<p align="center">Record, edit, and replay Android workflows.</p>

<p align="center">
  <a href="https://github.com/DhanaKresnawijaya237/Veyarc/releases/latest"><strong>Download APK</strong></a>
  · <a href="#get-started">Get started</a>
</p>

## Problem and solution

**Problem.** Repeating taps is tedious. Replaying a fixed recording isn't enough when a task is not so directly repeatable and needs to wait for something on screen or take different paths.

**Solution.** Record your actions, then add image checks, conditions, and loops in Veyarc. Edit the workflow yourself or ask AI to help, without writing code.

## Features

- **Record.** Capture touches and app switches. Pause to add screen checks, then continue where you choose.
- **Edit.** Add waits, image checks, variables, and loops. Reuse block sequences and templates, keep revisions, and choose starting values or preset options.
- **AI.** Explain, create, or edit workflows with your own API key. Attach files and photos; crop, rotate, and annotate images.
- **Review.** Organize workflows and runs with folders and search. Inspect step activity, errors, match scores, and AI usage costs.
- **Export.** Share workflows and runs, or export statistics as CSV.

> **Example:** Record a tap sequence → add an image check → repeat while the image is visible.

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
- Image checks ask for screen sharing on the first visual run. Sharing ends when you turn off the edge latch, lock the phone, or stop capture.
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

## License

[MIT](LICENSE). Third-party dependencies retain their own licenses.
