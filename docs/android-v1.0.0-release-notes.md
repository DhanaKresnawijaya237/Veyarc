# Veyarc Android release notes

## 1.1.0

### New and improved

- Optional AI chat in release builds using your own API keys. Configure multiple providers and models, default effort, and compatible custom HTTPS endpoints.
- Streamed replies and supported thinking, background replies, named chats, pinned history, and reviewed workflow proposals. Apply to the draft, then save separately.
- Camera, photo, PDF, DOCX, Markdown, and text attachments, with previews and image crop, rotation, and annotation. AI can propose image templates and saved backgrounds.
- Refreshed Home, Settings, Editor, workflow review, and run details, with smoother page transitions and expandable activity history.
- Import and export both workflows and runs. Image matching reports measured scores; copied blocks reuse identical templates. AI block numbers follow the editor's visible numbering.
- Recording opens in the current editor and retains app switches, including after pause. System navigation touches are excluded. Playback uses the current run details throughout.

### Before updating

- Version code: **3**. Application ID: **com.veyarc.app**. Release updates use the same signing certificate as 1.0.0.
- Export important workflows and runs before uninstalling. A release APK cannot normally replace a debug APK because their signatures differ; uninstalling removes local data, including chats and keys.
- Recording, editing, and playback work offline. This release adds internet access for optional AI requests. AI starts disabled, requires your API key, and turns off on process restart.
- Your selected provider receives messages, workflow text, recent chat context, and attachments. API charges may apply even when a reply fails or is stopped. See [AI chat help](ai-chat-guide.md).

### Support and limits

- Physical acceptance remains limited to the vivo V50 on Android 16 / OriginOS 6. Emulator checks do not prove other phones' recording behavior or every provider/model combination.
- Playback needs Android 7+ and Accessibility; exact recording needs Android 13+, authorized Shizuku, and a compatible touchscreen node. Visual conditions require fresh screen-capture consent per run.
- Recording/replay uses one pointer. Portrait and landscape are separate variants; rotation stops a run. Split-screen and freeform windows are unsupported.
- Human touches can interfere with playback. Keep the stop notification available; stop testing if ordinary input or navigation behaves abnormally.

See the [Android guide](android-beta-guide.md) and [device compatibility](android-device-compatibility.md) for setup and recovery.

---

## 1.0.0

Version 1.0.0 combines single-pointer recording, an editable workflow, and local run history. Device support remains limited; check [compatibility](android-device-compatibility.md) before relying on recording or replay.

### Included

- Record down, move, and up events through an authorized Shizuku helper, then save a valid recording directly as a workflow.
- Edit versioned workflows with gestures, waits, app launches, conditions, loops, stage boundaries, jumps, and nested blocks. Copy blocks between workflows.
- Use pixel and image conditions with encrypted templates. Import and export portable `.veyarc` workflows from Settings.
- Review run outcomes and step events, including measured image-match scores where available.
- Start recording or a chosen workflow from the optional edge latch. Notifications remain an independent stop control.
- Keep workflow bodies, image assets, and recording journals encrypted on the device. Identical image data can be shared across revisions.

### Before installing

Playback needs Android 7.0 or newer and Veyarc Accessibility. Exact recording needs Android 13 or newer, authorized Shizuku, and a compatible direct touchscreen node. Visual conditions ask for fresh Android screen-capture consent on each applicable run. The verified physical device is a vivo V50 running Android 16 and OriginOS 6; other devices are not yet verified.

An update must use the same signing key as the installed app. A release APK usually cannot replace a debug APK without uninstalling first. Export workflows you want to keep before uninstalling, then verify the release APK's published SHA-256 hash and signing certificate.

### Limits

Veyarc records and replays one pointer, not multitouch, pressure, or contact area. Portrait and landscape are separate workflow variants; rotation during a run stops it. Split-screen and freeform windows are unsupported. Replay does not freeze human touch input, so leave the target app untouched while it runs.

For setup, permissions, and recovery, see the [Android guide](android-beta-guide.md).
