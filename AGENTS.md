# Repository safety rules

## Project quick reference

- Android root: `apps/android`; run Gradle commands from there.
- Full gate for releases or justified cross-cutting changes: `.\gradlew.bat verifyLocal`
- Apply formatting: `.\gradlew.bat spotlessApply`
- Build debug APK: `.\gradlew.bat :app:assembleDebug`
- Debug APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
- Reuse an APK already built from the current relevant sources and build inputs. Run `:app:assembleDebug` before installing only when those inputs changed or freshness cannot be established. Never install a stale artifact.
- Device tests require an approved emulator or physical-device test. Select the relevant instrumentation class/method; `verifyDevice` is a broad suite, not the default.
- ADB executable on Windows: `$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe`; use `connect <host:port>` and `install -r <apk>`.
- Close emulators when they are no longer needed.
- Before editing or committing, check `git branch --show-current` and `git status --short`. Keep commits scoped; do not include `My Examples/`, `.codex/`, `graphify-out/`, generated `bin/` directories, or handoff files unless explicitly requested.
- Commit on the current task branch when a significant, cohesive implementation checkpoint is complete and its required checks pass; no additional user permission is needed. Group related changes rather than committing every small edit. Use Conventional Commits with a concise imperative subject, for example `feat(assistant): add reviewed workflow editing`, `fix(assistant): cancel interrupted requests`, or `docs(repo): clarify checkpoint rules`. Keep unrelated work out of the commit. This authorizes local commits only; pushing, merging, and publishing still require user direction.
- Do not commit documentation unless the user explicitly requests or names it for inclusion in commits.
- Do not commit or push tests, test fixtures, benchmarks, or QA artifacts unless the user explicitly authorizes a specific exception. Keep new test files local using `.git/info/exclude`.
- When changing workflow fields, steps, conditions, or serialized enum values, update `spec/workflows/v1/workflow.schema.json` in the same change so the AI schema matches the app. Maintain it directly without adding synchronization checks.
- After changes, review the scoped diff and run `git diff --check` for changed files. Choose further checks using the policy below.

## Minimal implementation, minimal fixes, minimal testing

- Default to the smallest complete solution. Reuse existing code and patterns; avoid speculative features, unnecessary abstractions, dependencies, and unrelated refactoring.
- Fix the confirmed problem at its source and keep the change scoped. Do not expand a small task into a redesign or cleanup campaign.
- Use the fewest checks needed for the actual risk. Reuse passing results and existing tests. Add a small local regression test only when needed; avoid duplicate coverage, elaborate fixtures, and new QA frameworks.
- Stop when the requested behavior and necessary checks are complete. Minimal work must still preserve safety, validation, error handling, and protection against data loss.

## Verification: minimum sufficient checks

Choose checks once based on what the change can break. Reuse passing results while their relevant code, configuration, dependencies, and test inputs are unchanged. Once the selected checks pass, stop; do not add coverage for reassurance.

| Change | Default checks |
| --- | --- |
| Documentation or comments only | Diff review and whitespace check. No Gradle, emulator, or graph rebuild. |
| UI wording, colours, spacing, or icons | Diff review and affected-module compilation. A requested APK build covers compilation; no separate build or UI suite. |
| Local behaviour change | Closest existing test method/class. Add one focused regression test if meaningful coverage is missing. |
| Shared model or serialization | Relevant contract test and affected direct consumer tests. |
| Native matching, storage, security, or Android lifecycle | Focused regression checks for the changed path. Use device tests only for behaviour JVM tests cannot establish; retain the safety requirements below. |

Do not run `verifyLocal`, full device suites, stress tests, or performance matrices for routine edits. Measure performance only when the task or changed path needs it, using one representative before/after comparison.

### Releases

- Run `verifyLocal` at most once for the final candidate, or reuse a passing result with unchanged relevant inputs. A version bump or documentation edit does not invalidate unrelated tests.
- Verify the committed candidate, not results that depend on excluded local tests or fixes. If dirty files would contaminate verification or packaging, isolate the committed source once and reuse that directory and its caches. Do not create fresh snapshots for each check.
- Build/sign once and reuse that APK. Keep automatic packaging/vital checks enabled. Do not append full `lintRelease` after `verifyLocal` by default; add it only for a release-specific risk not already covered.
- Add at most one short device smoke scenario when needed for changed Android/native or release-only behaviour. Existing focused tests and the user's acceptance of the current build count as evidence. Do not follow passing checks with a manual tour of the app.
- Test an old-version upgrade only when persisted data, schema, signing, or upgrade handling changed. Do not reinstall old releases and rebuild sample workflows for every release.
- Once required checks pass, publish. Do not add optional benchmarks, screenshots, stress runs, or unrelated feature checks. State any remaining limitation briefly.

### Execution and stop rules

- Use incremental builds and existing dependencies/caches. No `clean`, forced reruns, or cache deletion without a concrete failure requiring them. Prefer `--offline` when dependencies are already resolved; fetch missing dependencies only when needed. Signed builds must still use `--no-configuration-cache --no-build-cache` to protect signing secrets.
- Run one Gradle build at a time. Build before starting an emulator on this memory-limited host; use `--max-workers=2` for broad builds. Avoid overlapping builds, signing, and emulator work that causes memory pressure.
- Stop optional verification after 5 minutes for routine changes or 10 minutes for releases, excluding the required artifact build/upload. Count failed attempts, test edits, test APK rebuilds and emulator restarts toward that time; changing methods does not reset it. Report incomplete required checks as blockers rather than waiving them.
- Log long command output and inspect summaries/failures. Poll long-running jobs at 30–60 second intervals instead of repeatedly rereading full logs. Keep results and relevant inputs in existing task notes so later turns can reuse them.
- Prefer existing tests and tools. Do not build new QA frameworks, duplicate suites, elaborate fixtures, or test documents for a small change.
- If the user says stop testing, stop optional checks immediately; finish the authorized build/install/release when required evidence is sufficient, or state the specific remaining blocker.

### Failed verification: bounded troubleshooting

- Investigate failures and make reasonable fixes within the verification time budget. Do not give up at the first failure. Usually one or two targeted retries are enough; each must address a concrete cause or test a specific explanation, not merely repeat the attempt. Routine troubleshooting does not require fresh user permission.
- Fix confirmed app bugs within the requested scope and rerun the failing check plus directly affected checks. For test-harness or tooling problems, prefer a small correction over repairing the QA setup. Switch tools or emulators only when evidence points to a specific compatibility problem, within the same time budget.
- Stop when attempts no longer produce useful evidence, the same failure recurs without a new explanation, troubleshooting expands into unrelated work, or the time budget is reached. Do not keep chaining selector edits, rebuilds, emulator restarts or alternative methods to chase a pass. Report the findings instead of letting verification consume the implementation session.
- Report what failed, whether it is a confirmed app defect or a verification problem, what passed, what remains unverified, and the smallest next step. Keep uncertainty explicit. Never call a partial scenario a pass or weaken assertions just to obtain one.
- Stop the failed verification path, not unrelated authorized work. Complete work supported by the available evidence; leave unsafe installs or releases blocked when necessary safety checks remain unresolved. Do not spend an implementation session repairing the QA setup unless the user requested that work.

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
- Update the graph once per cohesive code checkpoint. Reuse a completed post-commit hook rebuild; otherwise run `graphify update .` once. Do not rebuild for documentation-only or version-only changes, or rerun an update against unchanged inputs.
