## Summary

Build Veyarc as a native Kotlin Android application for coordinate-based device automation. The first release will be a sideloaded technical beta with local storage, a nested block editor, Android 7+ playback, Android 13+ touch recording, explicit multi-app workflows, pixel/image conditions, and no semantic UI inspection.

Success means a user can record or manually create a workflow containing taps, holds, paths, waits, bounded loops, conditions, app switches, and stops; review it; save it locally; and replay it safely across different resolutions and orientations.

The AI assistant follows the stable core beta. It may generate or edit typed workflows but can never execute them. Windows support follows through a separate native adapter sharing the workflow format.

## Architecture and Interfaces

### Repository and technology

Use a monorepo with:

- `apps/android`: Kotlin, Jetpack Compose, coroutines/Flow, Room, DataStore, Kotlin serialization and dependency injection.
- `services/api`: later TypeScript/Fastify service using Zod and the OpenAI SDK.
- `spec`: versioned workflow JSON Schema and cross-platform fixtures.
- `apps/windows`: later C#/.NET implementation.

Use JDK 17, the Android SDK, an Android emulator, a Gradle wrapper, a version catalog, current stable compatible Android tooling, `minSdk 24`, and the current target/compile SDK. Android Studio is optional for ordinary builds but may be used for IDE, profiler, and device-management workflows.

Inside Android, create separate modules for the application shell, workflow model, execution engine, recording compiler, Android platform adapter, local data, editor, recorder, runner, and later assistant. Pure core modules must not import `android.*`. This follows Android’s separation and modularization guidance. [Android architecture](https://developer.android.com/topic/architecture), [Android modularization](https://developer.android.com/topic/modularization)

### Workflow contract

Define `WorkflowDocument` with:

- Schema version, stable ID, revision and display name.
- Explicit allowed Android packages.
- Viewport policy and recorded diagnostic metrics.
- Run limits.
- Ordered steps with stable IDs.
- References to image-condition assets.

Supported first-beta steps:

- `TapPoint`
- `TapRegion`, resolving to a centre or user-defined anchor point
- `LongPress`
- `SwipePath`, supporting arbitrary single-pointer shapes
- `Wait`
- `WaitUntil`, always with timeout
- `If/Else`
- `Repeat`, always with a finite count
- `While`, always with a maximum iteration count
- `LaunchApp`
- `Stop`

Supported conditions:

- Pixel colour within tolerance
- Image template present or absent within a region
- Foreground package
- Current orientation
- Internal variable comparison
- Prior step success/failure
- Logical `And`, `Or` and `Not`

Do not support arbitrary jumps, executable scripting, unbounded loops, simultaneous multi-touch, semantic selectors, text capture, or physical touch pressure/contact shape in the first beta.

Use normalized full-display coordinates. `(0.10, 0.10)` remains 10% from the current screen’s left and top in portrait or landscape. X and Y scale independently to current logical display dimensions. UI reflow or redesign is treated as a new workflow revision, not automatically adapted. Disallow split-screen and desktop/freeform window modes in the beta.

Persist exportable workflows as `.veyarc` ZIP bundles containing `workflow.json`, `manifest.json`, and encrypted condition-image assets. Maintain an append-only internal JSON Lines journal during recording. Enforce archive entry count, decompressed-size and path-traversal limits during import.

### Validation and execution

Never execute an editable document directly:

```text
WorkflowDocument → Validator → Compiler → Immutable ExecutionPlan → Runner
```

Validation must reject malformed coordinates, empty paths, unsupported steps, excessive durations, missing package restrictions, unbounded control flow, missing assets and plans exceeding configured step/runtime limits.

Implement the runner as a single-coroutine deterministic state machine with:

- An injected monotonic clock.
- One active workflow maximum.
- Cancellation checks before and after every step and loop iteration.
- Typed run events for UI and audit history.
- No automatic restart after process death.
- Immediate failure when Accessibility is disabled, the device locks, screen capture is revoked, or an undeclared package becomes foreground.
- Viewport recalculation between steps.
- Pause-and-fail behavior if orientation changes during an active gesture, preventing accidental duplicate execution.

Define platform-neutral interfaces for:

- Gesture dispatch
- Current viewport
- Foreground package
- Condition observation
- Execution clock
- Touch-event input source

The Android adapter implements them using `AccessibilityService`, `TouchInteractionController`, `dispatchGesture`, application launch intents and MediaProjection. Android framework classes must not escape into the core engine.

### Coordinate-only recording

Recording is event-driven, not fixed-interval polling:

1. The accessibility service owns a recording session that can be started and stopped while a declared target app is foreground through service-owned controls (notification first, with Quick Settings/accessibility shortcuts as follow-up entry points).
2. The recording indicator is non-touchable. Stopping is handled by the service rather than by a draggable input overlay, so the target app and system navigation remain usable throughout the session.
3. For third-party apps, prefer a privileged read-only Linux `evdev` input source: a root companion process (or a verified shell/Shizuku capability) observes the touchscreen device without requesting accessibility touch exploration or injecting/replaying during capture. The public Accessibility API touch-exploration experiment failed this safety test on the vivo V50 and remains disabled. If the privileged source is unavailable or cannot prove unchanged target-app input, recording must refuse to start.
4. Capture only the selected touchscreen device, parse complete single-pointer down/move/up frames, and preserve their monotonic timestamps in the encrypted raw journal. Raw events remain available for exact replay after recording; analysis is a separate view of the same trace.
5. The target package must remain foreground for the entire session. A package change, lock screen, system dialog, or lost helper connection fails the recording, stops the helper, and preserves only a recoverable journal when safe.
6. After recording, simplify paths using a configurable tolerance based on display diagonal while preserving timing and endpoints.
7. Classify gestures using the captured touch thresholds and infer waits from inter-gesture gaps and foreground-package observations.
8. Produce an editable workflow draft; never execute the inferred draft automatically.

Recording controls are excluded from the trace. A failed or unsupported observation source must stop safely, avoid changing accessibility input routing, terminate any privileged helper, preserve the recoverable journal when possible, and report that exact third-party recording is unavailable on that device rather than allowing recorded and actual app state to diverge.

Playback remains available on API 24–32, but exact third-party recording is capability-gated. If the rooted/privileged evdev feasibility spike fails on a device family, mark recording unsupported for that device and retain manual authoring rather than silently producing incomplete traces.

### Visual conditions

MediaProjection is used only when a workflow contains pixel or image conditions:

- Request capture consent at run start.
- Capture temporary frames on demand at the condition’s polling interval.
- Never encode or save an MP4.
- Release frames immediately after evaluation.
- Save only user-selected template crops.
- Stop the workflow if capture consent is denied or revoked.
- Let users capture a region, choose expected colour/template, tolerance, polling interval and timeout through the editor.

### UI and local data

Build a single-activity Compose application with:

- Workflow library and revision history
- Nested, collapsible block editor
- Full-screen point, region and path editor
- Recording screen and timer overlay
- Permission/capability setup
- Pre-run validation and maximum-runtime summary
- Live run status, pause and emergency stop
- Run history and diagnostics
- AI assistant screen added later

Use Room only for workflow metadata, revision indexes and redacted run summaries. Store workflow bodies, raw journals and template assets as AES-GCM encrypted files using a key protected by Android Keystore. Never store screenshots except deliberate template crops.

Every workflow declares allowed packages. `LaunchApp` may switch only to a declared package. Unexpected apps, system dialogs, lock screens or permission screens terminate execution.

## Implementation Phases

1. **Environment and risk spike**
   - Configure the Android SDK/toolchain and scaffold `com.veyarc.app`; install Android Studio only if its IDE, profiler, or device-management workflows are needed.
   - Build a minimal Accessibility Service.
   - Prove tap, hold and arbitrary-path playback.
   - Prove read-only privileged touchscreen observation on the physical target selected for the first beta. The current target is the vivo V50; other OEM families remain future validation targets. Keep the earlier accessibility intercept/replay experiment as a legacy diagnostic only; it is not the production recorder.
   - Validate replay timing and service lifecycle restoration on the selected target, then document unsupported or unverified OEM behavior before building the full UI.

2. **Portable core**
   - Implement the workflow schema, JSON serialization, `.veyarc` bundle format and migration chain.
   - Implement validation, compilation, immutable execution plans, fake driver and deterministic runner.
   - Implement coordinate mapping, cancellation, bounded control flow and package enforcement.

3. **Local Android beta foundation**
   - Add encrypted local persistence, workflow library and nested editor.
   - Add manual point, region and path construction.
   - Add Android playback, permission onboarding, persistent run controls and audit events.
   - Ship no network permission or cloud dependency at this stage.

4. **Coordinate recorder**
   - Implement a service-owned app-scoped recording session, non-touchable indicator, service-owned start/stop controls, append-only event journal and a capability-gated privileged raw-input adapter. The adapter reads only the touchscreen evdev stream and must remain disabled unless it proves that normal third-party-app input is preserved; the current Android accessibility touch-exploration path is not acceptable.
   - Preserve raw events for post-recording exact replay; do not replay gestures while recording.
   - Implement path simplification, gesture classification, wait inference and foreground-package transition suggestions.
   - Always open the inferred workflow in review mode.

5. **Pixel/image conditions**
   - Add MediaProjection session management, ephemeral frame capture, pixel matching and on-device template matching.
   - Add condition authoring, polling, timeouts and template-asset encryption.

6. **Hardening and sideloaded release**
   - Add signed release builds using a keystore stored outside the repository.
   - Add permission diagnostics, device compatibility reporting, import limits, crash-safe journals and privacy documentation.
   - Produce APK release notes identifying recording as Android 13+ and device-dependent.

7. **AI assistant**
   - Add a Dockerized TypeScript/Fastify backend with a provider abstraction and OpenAI as the first provider.
   - Send only the workflow JSON and the user’s text request; do not send raw journals, screenshots or template images by default.
   - Require JSON Schema-constrained patch output, server validation, device validation, a human-readable diff and explicit acceptance.
   - Keep the assistant structurally isolated from the Android automation adapter. Follow official Structured Outputs and human-review guidance. [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs), [OpenAI safety guidance](https://developers.openai.com/api/docs/guides/safety-best-practices)
   - Production hosting, accounts and cross-device sync remain outside this phase; the service must first run locally and as a provider-neutral container.

8. **Later platforms**
   - Add Windows as C#/.NET using Windows UI Automation, `SendInput` and input hooks.
   - Reuse `.veyarc` schema, validation fixtures and execution semantics.
   - Implement a Windows-specific driver rather than attempting to share Android platform code or UI.
   - Consider Play Store submission only after the sideloaded beta proves deterministic recording, disclosure and safety behavior.

## Test and Acceptance Plan

### Core tests

- Serialization round-trip and deterministic schema migrations.
- Malformed and oversized bundle rejection.
- Every loop and wait limit.
- Cancellation at every step boundary.
- Fake-clock timing and timeout behavior.
- Resolution scaling across representative aspect ratios.
- Current-screen-relative orientation behavior.
- Golden raw traces for tap, hold, curved swipe, rectangle path and wait inference.
- Process interruption never resumes a run automatically.

### Android tests

- Playback on API 24 and current Android targets.
- Privileged start/stop and raw recording on multiple API 33+ physical devices.
- The declared target app remains interactive during recording; leaving it fails safely.
- Home, Recents and system navigation remain usable before and after recording, and the helper cannot remain active after cancellation.
- Rapid consecutive gestures remain ordered and complete in the raw journal.
- Exact replay uses the preserved raw trace only after recording has stopped.
- Rotation between steps and during an active gesture.
- Accessibility disabled mid-run.
- Screen-capture consent denial and revocation.
- Unexpected foreground package, lock screen and system dialog.
- Recording-control surfaces excluded from recording.
- No video file or unintended screenshot survives a run.
- Service-owned emergency stop prevents further capture, terminates the privileged helper, restores normal routing and removes recording controls on a best-effort basis within one second.

### End-to-end acceptance scenario

Record:

1. Tap near the current screen’s top-left.
2. Tap near the top-right.
3. Wait.
4. Draw a bottom-left-to-bottom-right path.
5. Stop recording.

The resulting draft must reproduce the actions, allow insertion of a bounded loop and image condition, scale across two resolutions, keep top-left relative to the visible screen after rotation, stop on an undeclared app, and replay only after explicit user approval.

This end-to-end recording scenario remains blocked until the privileged third-party input-source
feasibility gate is passed without altering ordinary one-finger touch behavior. The vivo V50
touch-exploration spike failed that gate; the current build must refuse to start recording rather
than freeze input. The next spike is a read-only evdev observer with package-scope enforcement.

## Locked Assumptions

- Display name: **Veyarc**; package: `com.veyarc.app`.
- Initial audience: technical early adopters.
- Distribution: sideloaded APK first; Play Store later.
- Playback: Android API 24+; exact recording: API 33+.
- Recording: coordinate/timing events only, with a root/privileged read-only touchscreen observer for third-party apps when a device-specific source proves transparent pass-through. Public accessibility touch exploration is never used for production recording. Without the capability, recording is visibly unavailable and manual authoring remains supported. No replay or touch injection occurs during recording.
- No semantic UI recording and no stored video.
- Touch regions and shapes are editor/path abstractions; Android receives point contacts following paths.
- Single-pointer arbitrary paths are included; simultaneous multi-touch is deferred.
- Workflows may span multiple explicitly declared apps.
- Resolution is normalized automatically; orientation remains relative to the current visible screen.
- UI layout changes require a new workflow revision.
- Pixel and image conditions use ephemeral screen frames.
- The core beta is local-only with no accounts or synchronization.
- AI arrives after the core beta, uses OpenAI first behind a provider interface, proposes typed patches only, and never controls the device.
