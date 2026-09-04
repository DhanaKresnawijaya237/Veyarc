## Summary

Build Veyarc as a native Kotlin Android application for coordinate-based device automation. The first release will be a sideloaded technical beta with local storage, a nested block editor, Android 7+ playback, Android 13+ touch recording, explicit multi-app workflows, pixel/image conditions, and no semantic UI inspection.

Success means a user can record or manually create a workflow containing taps, holds, paths, waits, bounded loops, conditions, app switches, and stops; review it; save it locally; and replay it safely across different resolutions within its recorded portrait or landscape orientation.

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

Use normalized full-display coordinates. `(0.10, 0.10)` remains 10% from the current screen’s left and top when replaying in the workflow's recorded portrait or landscape orientation. X and Y scale independently to current logical display dimensions. Portrait and landscape are separate workflow variants because app layouts can reflow; Veyarc must refuse cross-orientation replay rather than transform it. A settled 180-degree starting rotation is allowed within the same orientation family. Any physical rotation during recording or replay stops safely. Disallow split-screen and desktop/freeform window modes in the beta.

Persist exportable workflows as `.veyarc` ZIP bundles containing `workflow.json`, `manifest.json`, and encrypted condition-image assets. Maintain an append-only internal JSON Lines journal during recording. Import uses strict UTF-8 and JSON, rejects excessive nesting and document collections, and enforces archive entry count, decompressed-size, declared-size, and path-traversal limits. Manifest hashes and per-asset AES-GCM authentication must pass before a stored workflow can run.

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
- Viewport recalculation between steps without crossing the workflow's recorded orientation family.
- Fail-before-input behavior when the starting orientation differs from the recording, and fail-closed behavior after any physical rotation during recording or replay.

Replay safety controls are separate from the workflow engine and must be service-owned:

- Treat strict replay freezing (the user-facing "freeze input while replaying" mode) as a
  privileged capability, not as an ordinary accessibility overlay. Android touchable overlays
  become the input target, so they cannot safely consume physical touches while also routing the
  same synthetic gesture to the third-party app. Until a lifecycle-tested root/system input-filter
  backend exists, the freeze toggle is disabled and replay refuses to start if requested; it must
  never fall back to touch exploration, a full-screen overlay, or an unscoped input filter.
- Keep the persistent notification's Pause/Resume/Stop actions and Quick Settings emergency stop
  as independent service-owned fallbacks, even when the controller is the primary user-facing
  control. The controller is an opt-in, small edge-latch accessibility overlay that is persisted
  on the left or right screen edge with a normalized along-edge position. It remains collapsed during recording and replay
  and expands into a compact control-center panel. Opening
  the panel while replay is active must cancel replay before any panel action is handled. It is
  never a freeze surface. The recorder excludes its user-selected bounds from the raw journal, and replay
  preflight refuses a trace whose remapped points intersect those bounds, preventing the overlay
  from stealing or misdirecting synthetic input. Its square replay-latest action requests a direct
  replay; the remaining state, latest-replay, library, and hide actions stay in one compact
  horizontal row, and the separate library action remains available for editing.
- A normal APK must not claim to convert the system Back gesture into a replay pause. Notification
  Pause/Resume/Stop and the controller's explicit panel actions are the reliable controls; ordinary
  Back remains system navigation and may cause the existing foreground-safety cancellation.
- If the future privileged guard or any replay control cannot be created, is removed unexpectedly,
  or cannot be cleaned up, cancel the replay, clear active touches, remove all controls, and leave
  the target app in normal pass-through mode.
- Keep the input guard opt-in and capability-gated until it is physically verified across the
  supported OEM/device matrix; the default beta behavior remains normal pass-through.

Define platform-neutral interfaces for:

- Gesture dispatch
- Current viewport
- Foreground package
- Condition observation
- Execution clock
- Touch-event input source

The Android adapter implements them using `AccessibilityService`, its gesture dispatcher,
application launch intents and MediaProjection. The rejected touch-interception prototype has been
removed; Android framework classes must not escape into the core engine.

### Coordinate-only recording

Recording is event-driven, not fixed-interval polling:

1. The service owns a global recording session that can be started, paused, resumed and saved from
the fixed service-owned control center or Veyarc, and stopped from any foreground app (Quick
Settings tile and the notification remain independent emergency fallbacks).
2. The controller is a small edge latch rather than a circular or full-screen input surface. The
   user can drag it continuously along the left or right edge; it switches only between those two
   edges on release and persists a normalized position along the selected edge, and any drag closes the expanded panel. Its current touch bounds are explicitly excluded
   from the raw journal; outside those bounds the target app and system navigation remain usable
   throughout the session.
3. For third-party apps, prefer a privileged read-only Linux `evdev` input source: a root companion process (or a verified shell/Shizuku capability) observes the touchscreen device without requesting accessibility touch exploration or injecting/replaying during capture. The public Accessibility API touch-exploration experiment failed this safety test on the vivo V50 and remains disabled. If the privileged source is unavailable or cannot prove unchanged target-app input, recording must refuse to start.
4. Capture only the selected touchscreen device, parse complete single-pointer down/move/up frames, and preserve their monotonic timestamps in the encrypted raw journal. Raw events remain available for exact replay after recording; analysis is a separate view of the same trace.
5. Foreground-package observations are journal data, not a stop condition: normal app switches remain in one global trace. Veyarc, the unlocked notification shade, the launcher, and unknown/non-application windows are control surfaces whose touch frames are ignored. The lock screen, a lost helper connection, or an invalid touchscreen stream stops the recording and preserves only a recoverable journal when safe.
6. After recording, simplify paths using a configurable tolerance based on display diagonal while preserving timing and endpoints.
7. Classify gestures using the captured touch thresholds and infer waits from inter-gesture gaps and foreground-package observations.
8. Produce an editable workflow draft; prepend one validated `LaunchApp` step for the first observed third-party package so a recorded workflow can start from Veyarc or another control surface, but never execute the inferred draft automatically. Later foreground transitions remain review suggestions; loops, conditions, and additional app launches are not inferred.

Recording controls are excluded from the trace, while their foreground transitions may be retained as review metadata. A failed or unsupported observation source must stop safely, avoid changing accessibility input routing, terminate any privileged helper, preserve the recoverable journal when possible, and report that exact global recording is unavailable on that device rather than allowing recorded and actual app state to diverge.

Short-tap playback remains available on API 24 and 25. Long presses and swipe paths require API 26 because safe interruption depends on continued strokes. Exact third-party recording is capability-gated. If the rooted/privileged evdev feasibility spike fails on a device family, mark recording unsupported for that device and retain manual authoring rather than silently producing incomplete traces.

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
- Explicit strict-freeze capability status (disabled until a privileged input filter exists)
- Opt-in left/right edge-latch control-center toggle, continuous along-edge placement, and a direct replay-latest play action in one compact horizontal row
- Recording start/pause/resume/save plus replay status and stop actions from the controller
- Replay pause/cancel status with notification Pause/Resume/Stop and Quick Settings emergency stop
- Run history and diagnostics
- AI assistant screen added later

Use Room only for workflow metadata, revision indexes and bounded local run diagnostics. Those diagnostics may include action coordinates and declared package names requested by the user, but never screen pixels, variable values or raw exception text. Store workflow bodies, raw journals and template assets as AES-GCM encrypted files using a key protected by Android Keystore. Never store screenshots except deliberate template crops, and never emit workflow identifiers or throwable details to Logcat.

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
   - Implement a service-owned global recording session, non-touchable indicator, Quick Settings/notification start-stop controls, append-only event journal and a capability-gated privileged raw-input adapter. The adapter reads only the touchscreen evdev stream and must remain disabled unless it proves that normal third-party-app input is preserved; the current Android accessibility touch-exploration path is not acceptable.
   - Preserve raw events for post-recording exact replay; do not replay gestures while recording.
   - Implement path simplification, gesture classification, wait inference and foreground-package transition review data across app switches.
   - Convert the reviewable recording into a validated workflow, prepend one validated `LaunchApp`
     step for the first observed third-party package, and persist it immediately as a new revision.
     Keep the encrypted journal and review screen available for diagnostics; opening the saved
     workflow in the editor creates the next revision rather than replacing the automatic save.
     - Add the replay follow-up controls: a capability-gated strict-freeze slot for a future
       privileged input-filter backend, notification Pause/Resume/Stop and Quick Settings emergency
       stop, in-app replay status, and an opt-in fixed edge-latch control-center overlay. The collapsed
      handle remains present during recording and replay; opening it while replaying cancels the
       replay before panel actions run. The panel provides recording start/pause/resume/save and
       workflow/replay actions. Square play actions auto-start only after the normal recovery,
       service, compilation, and emergency-notification checks. It never intercepts or freezes target input, recording excludes its
       user-selected bounds, and replay refuses colliding traces. Keep normal replay explicitly pass-through while
      strict freeze is unavailable.

5. **Pixel/image conditions**
   - Add MediaProjection session management, ephemeral frame capture, pixel matching and on-device template matching.
   - Add condition authoring, polling, timeouts and template-asset encryption.

6. **Hardening and sideloaded release**
   - Add signed release builds using a keystore stored outside the repository. Release packaging must fail closed when external signing inputs are missing, invalid, stored inside the repository, or invoked with secret-retaining Gradle caches enabled; it must never fall back to debug signing.
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
- Resolution scaling within the workflow's recorded portrait or landscape orientation.
- Golden raw traces for tap, hold, curved swipe, rectangle path and wait inference.
- Process interruption never resumes a run automatically.

### Android tests

- Playback on API 24 and current Android targets.
- Privileged start/stop and raw recording on multiple API 33+ physical devices.
- Third-party apps remain interactive during recording; switching between them does not interrupt the global trace.
- Home, Recents and system navigation remain usable before and after recording, and the helper cannot remain active after cancellation.
- Rapid consecutive gestures remain ordered and complete in the raw journal.
- Exact replay uses the preserved raw trace only after recording has stopped.
- Strict freeze is enabled only when a privileged input-filter capability has passed device-specific
  lifecycle checks; otherwise the request is refused before any input is sent.
- The opt-in edge-latch controller remains collapsed during recording and replay, can be dragged continuously along the left or right edge and switches
  between those two edges only on release, opens to an adjacent control-center panel, cancels active replay before
  panel actions run, excludes its current bounds from recording, and refuses replay traces that
  intersect those bounds; controller/service teardown is idempotent.
- Notification Pause/Resume/Stop reaches the active process-owned run controller, and ordinary Back
  is not treated as a guaranteed pause signal.
- Any future privileged filter teardown is idempotent after completion, cancellation, service
  disconnect, timeout, process death, or permission loss; no input blocker remains installed.
- Rotation between steps and during an active gesture.
- Accessibility disabled mid-run.
- Screen-capture consent denial and revocation.
- Foreground-package transitions, unknown windows, lock screen and system dialog behavior.
- Recording-control surfaces excluded from recording.
- No video file or unintended screenshot survives a run.
- Quick Settings/notification emergency stop prevents further capture, terminates the privileged helper, restores normal routing and removes recording controls on a best-effort basis within one second.
- Replay input-guard tests remain deferred until a privileged backend exists; they must verify that
  conflicting target-app touches are consumed while system navigation and emergency controls
  remain usable.

### End-to-end acceptance scenario

Record:

1. Tap near the current screen’s top-left.
2. Tap near the top-right.
3. Wait.
4. Draw a bottom-left-to-bottom-right path.
5. Stop recording.

The resulting draft must reproduce the actions, allow insertion of a bounded loop and image condition, scale across two resolutions in the recorded orientation, refuse the opposite orientation before input, stop on an undeclared app, and replay only after explicit user approval.

The privileged input-source feasibility gate is now passed on the scoped vivo V50 without altering
ordinary one-finger touch behavior. The vivo V50 touch-exploration spike remains disabled because it
failed that gate. The production trace records foreground transitions across multiple third-party
apps and stops only through the explicit service-owned control or a safety failure. A completed
trace is converted into an editable workflow with a launch-app step and can be replayed through the
bounded Shizuku helper; multi-touch and pressure/contact-area fidelity remain follow-up work.

## Locked Assumptions

- Display name: **Veyarc**; package: `com.veyarc.app`.
- Initial audience: technical early adopters.
- Distribution: sideloaded APK first; Play Store later.
- Playback: short taps on Android API 24 and 25, full bounded gestures on API 26+; exact recording: API 33+.
- Recording: coordinate/timing events only, with a root/privileged read-only touchscreen observer for third-party apps when a device-specific source proves transparent pass-through. Public accessibility touch exploration is never used for production recording. Without the capability, recording is visibly unavailable and manual authoring remains supported. No replay or touch injection occurs during recording.
- No semantic UI recording and no stored video.
- Touch regions and shapes are editor/path abstractions; Android receives point contacts following paths.
- Single-pointer arbitrary paths are included; simultaneous multi-touch is deferred.
- Workflows may span multiple explicitly declared apps.
- Resolution is normalized automatically within the recorded orientation; portrait and landscape require separate workflow variants.
- UI layout changes require a new workflow revision.
- Pixel and image conditions use ephemeral screen frames.
- The core beta is local-only with no accounts or synchronization.
- AI arrives after the core beta, uses OpenAI first behind a provider interface, proposes typed patches only, and never controls the device.
