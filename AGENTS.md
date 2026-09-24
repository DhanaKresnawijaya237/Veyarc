# Repository safety rules

## Project quick reference

- Android root: `apps/android`; run Gradle commands from there.
- Local gate: `.\gradlew.bat verifyLocal`
- Apply formatting: `.\gradlew.bat spotlessApply`
- Build debug APK: `.\gradlew.bat :app:assembleDebug`
- Debug APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
- Before every install, run `:app:assembleDebug` unless the APK is proven newer than all relevant source changes; never install a stale build artifact.
- Device tests: `.\gradlew.bat verifyDevice` only with an approved emulator or physical-device test.
- ADB executable on Windows: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`; use `connect <host:port>` and `install -r <apk>`.
- Close emulators when they are no longer needed.
- Before editing or committing, check `git branch --show-current` and `git status --short`. Keep commits scoped; do not include `My Examples/`, `.codex/`, `graphify-out/`, generated `bin/` directories, or handoff files unless explicitly requested.
- Do not create a Git commit unless the user explicitly requests one.
- After code changes, run the smallest relevant tests, `git diff --check`, and `graphify update .`.

## Device and accessibility safety

Accessibility, touch-input routing, screen interception, replay injection, boot/recovery behavior,
and device health are safety-critical. Before changing any code that can affect them:

- inspect the complete control path from service startup through teardown and process death;
- identify whether the change can alter global touch handling, accessibility mode, navigation, lock
  screen behavior, boot stability, battery/thermal load, or persisted device settings;
- prefer a capability-gated, opt-in, fail-closed design with an independent emergency stop;
- prove that every completion, cancellation, timeout, interruption, unbind, disconnect, crash, and
  restart path restores ordinary device input and accessibility state;
- do not make persistent accessibility or input-mode changes merely to fix a replay lifecycle
  issue; any temporary mode change must have an explicit restoration path and a recovery fallback;
- review the relevant Android/OEM behavior and tests before building or installing an APK;
- do not install on a physical device until the risk, rollback steps, and recovery procedure are
  documented and the user has explicitly approved the device test;
- after installation, verify device responsiveness and ordinary system navigation before testing
  replay behavior; stop immediately if touch, navigation, or accessibility behaves abnormally.

If a change could affect device input or health and its lifecycle cannot be demonstrated safe,
leave the feature disabled and ask for direction rather than shipping or installing it.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
