# Getting started on Android

## Set up once

- In Settings, allow **Notifications** so you can stop recording or playback at any time.
- Open **Accessibility** and turn on **Veyarc gesture control** for workflow playback.
- Install [Shizuku](https://shizuku.rikka.app/guide/setup/), start it, and give Veyarc access. Recording and raw-touch playback need it.
- Start Shizuku again after restarting your phone.
- Optional: enable the **Edge latch** or add **Veyarc recording** to Quick Settings for quick controls.

## Make your first workflow

- Open the Editor to create a workflow or choose one you already have.
- Set its allowed apps, add blocks, then save a revision.
- To record instead, start recording and use the target app normally. Stop from the notification, edge latch, or Quick Settings.
- Review recorded steps before running them. Recording supports one finger and stops after five minutes.

## Run and stop

- Start in the workflow's saved orientation. Rotating the phone during recording or playback stops it.
- Avoid touching the target app while a workflow runs. Your touches can interfere with playback.
- Use **Stop** in the notification to end a run. Opening the edge latch also stops playback.
- Keep the stop notification reachable. Split-screen and desktop window modes are not supported.
- If touch or navigation stops working normally, lock the phone and stop testing. Restart if needed, then disable Veyarc in Accessibility before trying again.

## Images and screen access

- Image and pixel conditions ask to share your screen for each run. This lets Veyarc check what is visible.
- Captured frames stay in memory and are not saved as screenshots or video.
- Use a cropped image template when the surrounding screen may change.

## Optional AI chat

- In **AI connection**, add your provider and API key and enable AI for the session.
- Messages and connection tests use your API balance. Saving a key alone sends nothing.
- Chat shares your message, recent conversation, workflow text, and the photos or files you attach. Check **Workflow context** to see the shared workflow data.
- Review proposed changes before applying them. Applying changes does not save or run the workflow.
- **Stop** cancels an active reply, but the provider may still charge for work already done.
- Keys and chats are encrypted on your phone. Deleting them locally does not delete the provider's records. Keep passwords and other secrets out of shared content.
- Replies can continue while you use another page or app. Recording, editing, and playback work offline.

## Keep your work

- Use **Local data** in Settings to import or export `.veyarc` workflows and `.veyarc-run` run history.
- Export important workflows and runs before uninstalling. Uninstalling removes local data, including images and chats.
- Android cloud backup is disabled. Veyarc has no cloud sync or analytics.
- Install updates from a source you trust. Updates need the same signing key as your installed version.

## Device support

- Playback requires Android 7 or newer. Android 7 only supports short taps.
- Exact touch recording needs Android 13 or newer and a compatible device with Shizuku access.
- Recording supports one finger, not simultaneous multitouch.
- The verified phone is the vivo V50 with Android 16 and OriginOS 6. Other devices may behave differently.

See [device compatibility](android-device-compatibility.md) for tested devices.
