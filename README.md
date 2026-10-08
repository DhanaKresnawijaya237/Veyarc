# Veyarc

Veyarc is an Android app for recording, editing, and replaying touch-based workflows on your own device. It is distributed as a sideloaded APK, not as a general-purpose device automation service.

## Why it exists

Repeating a sequence of taps and swipes by hand is tedious. Basic gesture recorders can replay a path, but they offer little control when an app takes time to respond or the screen changes. Veyarc combines a touch recording with an editable workflow: record the actions, adjust them, add waits or conditions, then inspect what happened after each run.

It works with coordinates and screen images. It does not read an app's buttons, text, or view hierarchy.

## What it does

1. **Record.** On a compatible device, Veyarc reads one finger's down, move, and up events through an authorized Shizuku helper. It also notes foreground-app changes. Stopping a valid recording saves it as a workflow.
2. **Edit.** Arrange blocks for taps, regions, long presses, sampled swipe paths, waits, app launch, variables, conditions, loops, stage boundaries, jumps, and stops. Each save creates a workflow revision.
3. **Run and review.** Accessibility dispatches workflow gestures. Visual conditions compare temporary screen captures with pixel colors or saved image templates. Runs record their outcome and detailed step events, including measured image-match scores when available.

The app has five main areas: **Home** for run statistics, recording, and favorite workflows; **Workflows** for folders and saved workflows; **Editor** for blocks and revisions; **Runs** for history and details; and **Settings** for permissions, appearance, the edge latch, and local data. The left or right edge latch offers quick recording and replay controls without opening the app.

Workflow revisions, image templates, recording journals, and AI chats are encrypted locally. Identical image data can be shared across revisions. Veyarc has no account, cloud sync, or analytics. Recording, editing, and replay work offline. Settings supports `.veyarc` workflow and `.veyarc-run` run import and export.

## Optional AI chat

- Connect your own API key for DeepSeek, OpenAI, Anthropic, Google, or another listed provider. Custom connections support HTTPS endpoints using Chat Completions, Responses, Anthropic Messages, or Google Generative AI.
- Configure multiple models, choose a default model and effort, then enable AI for the app session. Saving a key or enabling AI sends nothing. Sending messages, testing connections, or fetching models contacts your chosen endpoint.
- Ask questions, create workflows, or propose edits. Replies and supported thinking stream into the chat; active replies can continue while you use another page or app.
- Attach camera photos, images, PDFs, DOCX, Markdown, or text files. Images support cropping, rotation, and annotation. Image and PDF input requires a model configured for vision.
- Review changes before applying them to the draft. Saving and running remain separate actions. AI cannot start recording, change device permissions, or run workflows.
- Requests include your message, recent completed chat exchanges, the current workflow text, attachments, and Veyarc's workflow schema and instructions. They go directly to your configured provider, without a Veyarc server.
- API usage is billed by the provider; a ChatGPT Plus or Claude Pro subscription is not an API key. Stop cancels the local request, but charges may still apply. Keys and chats stay out of workflow exports and Android backup; local deletion does not delete provider records.

See [AI chat help](docs/ai-chat-guide.md), also available in **Settings → AI connection**, for setup, privacy, and troubleshooting. Provider presets describe available integrations; not every provider/model has been tested with a funded account.

## Device support and limits

| Capability | Current support |
| --- | --- |
| Workflow playback | Android 7.0+. Android 7.0–7.1 allow short taps only; Android 8.0+ also allow bounded holds and swipe paths. Requires Veyarc Accessibility. |
| Exact touch recording and raw-trace replay | Android 13+ only where Shizuku can access a suitable direct touchscreen device. Support is device- and build-dependent. |
| Visual conditions | Require a fresh Android screen-capture approval for each applicable run. Captured frames are not saved as video. |
| Verified physical device | vivo V50 on Android 16 / OriginOS 6. An Android 17 emulator covers additional automated tests. |

Recording and replay use one pointer. Multitouch, pressure, and finger contact area are not reproduced. Portrait and landscape are separate workflow variants; rotating during a run stops it. Split-screen and freeform windows are unsupported. **Replay does not freeze the screen**: human touches can interfere, so leave the target app untouched while it runs. Notifications provide an independent stop control.

Other devices may work, but they are not yet verified. See [device compatibility](docs/android-device-compatibility.md) for the evidence, [the Android guide](docs/android-beta-guide.md) for setup and recovery, and [release notes](docs/android-v1.0.0-release-notes.md) for version 1.1.0.

## Repository and build

The native Kotlin project is in [`apps/android`](apps/android). It separates the app UI, workflow model and engine, recording, Android automation adapters, editor, runner, and encrypted storage into modules. With JDK 17 and the Android SDK configured, run from that directory:

```powershell
.\gradlew.bat verifyLocal
.\gradlew.bat :app:assembleDebug
```

The debug APK is written to `apps/android/app/build/outputs/apk/debug/app-debug.apk`. See the [Android developer README](apps/android/README.md) for device tests and signed release builds.
