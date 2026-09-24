# Veyarc Android 1.0.0

Veyarc's first sideloaded release combines single-pointer recording, an editable workflow, and local run history. Device support remains limited; check [compatibility](android-device-compatibility.md) before relying on recording or replay.

## Included

- Record down, move, and up events through an authorized Shizuku helper, then save a valid recording directly as a workflow.
- Edit versioned workflows with gestures, waits, app launches, conditions, loops, stage boundaries, jumps, and nested blocks. Copy blocks between workflows.
- Use pixel and image conditions with encrypted templates. Import and export portable `.veyarc` workflows from Settings.
- Review run outcomes and step events, including measured image-match scores where available.
- Start recording or a chosen workflow from the optional edge latch. Notifications remain an independent stop control.
- Keep workflow bodies, image assets, and recording journals encrypted on the device. Identical image data can be shared across revisions.

## Before installing

Playback needs Android 7.0 or newer and Veyarc Accessibility. Exact recording needs Android 13 or newer, authorized Shizuku, and a compatible direct touchscreen node. Visual conditions ask for fresh Android screen-capture consent on each applicable run. The verified physical device is a vivo V50 running Android 16 and OriginOS 6; other devices are not yet verified.

An update must use the same signing key as the installed app. A release APK usually cannot replace a debug APK without uninstalling first. Export workflows you want to keep before uninstalling, then verify the release APK's published SHA-256 hash and signing certificate.

## Limits

Veyarc records and replays one pointer, not multitouch, pressure, or contact area. Portrait and landscape are separate workflow variants; rotation during a run stops it. Split-screen and freeform windows are unsupported. Replay does not freeze human touch input, so leave the target app untouched while it runs.

For setup, permissions, and recovery, see the [Android guide](android-beta-guide.md).
