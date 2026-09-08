## Purpose

This phase turns the working Android beta into an app that is easier to understand, organize, and use every day. It sits after beta hardening and before the AI workflow assistant.

The phase changes presentation, navigation, organization metadata, and authoring interactions. It does not change workflow execution semantics, raw recording fidelity, replay injection, package safety, orientation rules, encryption, or emergency controls.

## Design references

The design should borrow proven interaction patterns without copying another product's appearance:

- Android's adaptive navigation keeps top-level destinations in a bottom bar on compact windows and uses a rail when a larger window benefits from it. Veyarc will preserve the same five destinations in either form. [Android adaptive navigation](https://developer.android.com/develop/adaptive-apps/guides/build-adaptive-navigation)
- Android window-size guidance favors flexible regions rather than layouts hard-coded for individual devices. [Material adaptive layout guidance](https://developer.android.com/codelabs/adaptive-material-guidance)
- Apple Shortcuts treats All Shortcuts as a collection, supports custom folders, and lets users move shortcuts without duplicating them. Veyarc will use the same clear storage rule for workflows. [Apple Shortcuts folders](https://support.apple.com/guide/shortcuts/organize-shortcuts-in-folders-apd113493874/ios)
- Apple Shortcuts widgets show a small set of chosen shortcuts and run them directly with visible progress. Veyarc's Home favorites should be similarly immediate. [Apple Shortcuts widgets](https://support.apple.com/guide/shortcuts/set-up-and-run-shortcuts-from-a-widget-apd029b36d05/ios)
- LlamaLab Automate makes execution order visible through blocks and paths. Veyarc will keep that readability while using a constrained vertical structure that matches its ordered and nested workflow model. [Automate flow model](https://www.llamalab.com/automate/doc/flow.html)
- MacroDroid exposes common automation actions, favorites, and macro management from a dashboard. Veyarc will use the useful quick-access idea but avoid its dense tile wall. [MacroDroid on Google Play](https://play.google.com/store/apps/details?id=com.arlosoft.macrodroid)
- Every interactive target must remain at least 48 dp even when the visible icon or handle is smaller. [Compose accessibility guidance](https://developer.android.com/codelabs/jetpack-compose-accessibility)

## Information architecture

The app has exactly five top-level destinations, ordered left to right:

| Position | Destination | Icon concept | Main job |
| --- | --- | --- | --- |
| 1 | Home | Home | Statistics, recording, and favorites |
| 2 | Workflow Manager | Folder | Organize, find, edit, and play workflows |
| 3 | Workflow Editor | Connected blocks | Create and edit the selected workflow |
| 4 | Runs Manager | History | Organize and inspect run history |
| 5 | Settings | Gear | Permissions, connections, appearance, controls, and privacy |

On phones, these destinations appear in a persistent bottom navigation bar in portrait and landscape. On larger windows, the same destinations may move to a leading navigation rail. Icons always have accessibility labels. Visible text labels may use the selected-only style on very narrow phones if all five full labels do not fit.

Top-level destination changes do not build an endless Back stack. Back first closes a dialog, sheet, detail view, or nested editor. At a destination root, Back follows normal Android app behavior.

The bottom bar or rail may be hidden for:

- Active replay and its pre-run safety review.
- Full-screen point, region, path, and image-region editing.
- Recording setup or review screens when the navigation would compete with the active control.
- Destructive confirmations that must be resolved before navigation.

The existing notification and Quick Settings emergency controls remain independent of app navigation.

The later AI assistant opens inside Workflow Editor as an editing tool. It does not add a sixth top-level destination.

## Visual direction

Veyarc should look like an automation tool, not a collection of default gray cards.

- Keep the dark graphite base and violet identity, then define a complete light theme rather than mechanically inverting colors.
- Use one strong accent per block category: input, timing, app control, condition, flow control, and stop. Colors must also differ through icons and shapes so color is never the only signal.
- Prefer clear page regions, dividers, spacing, and typography over wrapping every item in a large rounded rectangle.
- Use compact cards only for workflows, favorite actions, run summaries, and statistics that genuinely form separate objects.
- Use tabular numerals for durations, percentages, coordinates, and counts.
- Keep animation short and functional. Navigation selection, block expansion, folder movement, and chart updates may animate. Recording and replay status must update immediately.
- Respect reduced-motion settings. Do not animate decorative backgrounds.

The initial implementation should use Material 3 and Compose drawing APIs already available in the project. Do not add a chart library or custom rendering framework unless the built-in Canvas implementation becomes measurably insufficient.

## Home

Home answers three questions: what has been happening, what can I run now, and how do I start recording?

### Primary actions

- A prominent Record button starts the existing recording preparation flow.
- If recording is unavailable, the same position shows the exact missing capability and a direct setup action.
- Favorited workflows appear in a compact horizontal row or grid with name, last result, last-run time, and a direct Play button.
- Playing a favorite still passes through the existing recovery, validation, orientation, notification, and safety checks.

### Statistics

Use only facts already stored in run records. The first version includes:

- Runs in the selected period.
- Completion rate.
- Completed, failed, cancelled, stopped, and interrupted counts.
- Median completed-run duration.
- Runs per day, stacked or colored by result.
- Completion-rate trend.
- Most-run workflows.
- Recent failures with the stored user-facing reason.

The default period is seven days, with 30-day and 90-day choices. Empty states explain that statistics appear after the first run. A chart always has a text summary and accessible data description. The app must not derive fake trends from too little data.

## Workflow Manager

Workflow Manager owns workflow organization. It does not edit workflow steps inline.

### Collections and folders

- `All workflows` is a permanent system view containing every saved workflow.
- `Favorites` is a permanent filtered view.
- `Unfiled` contains workflows without a user folder.
- Users may create, rename, reorder, and delete custom folders.
- A workflow may belong to one custom folder and always remains visible in All workflows.
- Deleting a folder moves its workflows to Unfiled. It never deletes workflows.

This uses one nullable `folderId` on workflow metadata. It avoids duplicate workflow records and ambiguous multi-folder membership.

### Manager interactions

- Search by workflow name.
- Sort by recently updated, name, recently run, or most run.
- Filter by favorite, recorded/manual origin when that fact is available, and orientation.
- Toggle favorite without opening the editor.
- Move one or several workflows to a folder.
- Open revision history.
- Duplicate, export, or delete through a clear overflow menu.
- Tap the workflow body to open it in Workflow Editor.
- Keep Play as a distinct button so editing and execution cannot be confused.

New manual and recorded workflows have no custom `folderId` unless the user selected a destination folder before creation or recording. They therefore appear in All workflows and Unfiled. If recording starts from a folder-specific action, the saved workflow inherits that folder.

## Workflow Editor

Workflow Editor is a top-level destination and also accepts a workflow ID from Workflow Manager, Home, or a run detail.

If no workflow is selected, show New workflow, Continue editing, and Choose workflow actions. Do not show a blank technical form without context.

### Veyarc block language

The workflow model is ordered and nested, so the editor should not pretend it is a free-form node graph. Use a vertical execution rail with connected blocks:

- Action blocks have a solid body with a top input socket and bottom continuation socket.
- Conditions use a clipped or angled leading edge and show the evaluated question in their summary.
- `If/Else` blocks contain two labeled branch trays connected to the parent rail.
- `Repeat` and `While` blocks wrap their child rail in a visible loop container with the bound always shown.
- `Stop` closes the rail visually and has no continuation socket.
- Block category color, icon, title, and one-line summary remain visible when collapsed.
- Validation errors attach to the exact block and field. A page-level issue button jumps to the first error.

Blocks expand inline for common fields. Point, region, path, image, and complex condition editing open focused editors. Long press starts reordering. Cross-branch moves use an explicit Move action when drag behavior would be ambiguous.

The editor toolbar provides workflow name, orientation badge, revision state, Undo, Redo, Validate, Save revision, and Run. Save creates a revision through the existing repository. Switching tabs preserves the in-memory draft; it does not silently create revisions. Leaving or replacing a dirty draft requires Save or Discard.

On a wide screen, show blocks and the selected block inspector side by side. On a narrow screen, the inspector is inline or in a bottom sheet. The execution order must remain the same in both layouts.

## Runs Manager

Runs Manager owns run history, organization, filtering, and inspection.

### Default organization

- `All runs` contains every retained run.
- `By workflow` groups each run using its immutable `workflowId` and recorded revision.
- Status views cover completed, failed, cancelled, stopped, interrupted, and currently running records.
- Users may create, rename, reorder, and delete custom run folders.
- A run may belong to one custom folder. If it has none, it remains grouped by workflow.
- Deleting a custom folder removes only the assignment. It does not delete runs.

This requires one nullable custom-folder assignment per run. The workflow grouping remains computed data, so Veyarc does not create or synchronize duplicate folders for every workflow.

### Manager interactions

- Search by workflow name or run ID.
- Filter by status, workflow, date range, and revision.
- Sort newest first by default, with duration and status alternatives.
- Select multiple finished runs for move or delete.
- Never allow deletion or movement of an active run.
- Tap a run to open the existing detailed run view with event timing, action details, condition similarity, thresholds, and interruption reason.
- Link back to the exact workflow revision when it still exists.

Deleting a workflow keeps the current confirmed behavior unless changed in a separate storage decision: its revisions and run history are removed together. The confirmation must state this plainly.

## Settings

Move configuration out of Workflow Manager and group it by purpose:

### Connections and permissions

- Accessibility service status and setup.
- Shizuku binder, permission, server identity, reconnect guidance, and supported recording/replay capabilities.
- Notification permission and emergency-control readiness.
- Screen-capture explanation and current run-scoped consent state.

### Appearance

- System, light, or dark theme.
- Optional reduced visual density for users who want more workflow blocks on screen.
- Follow Android font scaling and contrast settings.

### Controls

- Enable or disable the edge latch.
- Reset latch position.
- Explain what the latch can control and why replay remains touch pass-through.
- Show Quick Settings and notification emergency-control setup.

### Data and privacy

- Workflow import and export.
- Local storage summary.
- Clear run history with confirmation.
- Explain encrypted workflow, journal, and template storage.
- State that Veyarc stores no video and sends no workflow data to a server in the core beta.

### Diagnostics

- App version and build information.
- Android/API and device capability report.
- Accessibility test and scoped feasibility diagnostics.
- Links to the beta guide and compatibility notes.

Settings may open Android system pages, but returning to Veyarc must refresh status. It must never enable Accessibility, Shizuku, notifications, or screen capture without the user's system confirmation.

## Storage changes

Add only the metadata required by the requested organization:

- Workflow folder table: stable ID, name, sort order, created time, updated time.
- Nullable workflow `folderId` and `isFavorite` fields.
- Run folder table: stable ID, name, sort order, created time, updated time.
- Nullable run `customFolderId` field.
- Theme preference and any UI-only choices in DataStore.

Use Room migrations that preserve all existing workflows, revisions, assets, and runs. Existing rows migrate as unfiled and not favorited. Do not modify encrypted workflow documents merely to change their folder or favorite state.

Dashboard statistics should be computed with bounded aggregate Room queries. Do not load and scan every stored event payload on the main thread.

## Responsive and orientation behavior

The UI responds to available window size rather than a list of phone models:

- Compact phones use one content column and bottom navigation.
- Tall and narrow screens keep primary actions reachable without clipping under system bars.
- Phone landscape keeps the bottom bar and uses wider rows, two-column summaries, or list-detail panes where height permits.
- Tablets and other large windows may use a navigation rail and two-pane manager/editor layouts.
- Fold, resize, and orientation changes preserve selected destination, folder, workflow, run, filters, scroll position where practical, and unsaved editor state.
- Content uses system insets and scroll containers. No primary screen relies on fixed pixel coordinates.
- Charts, block rails, dialogs, and sheets must remain usable at 200 percent font scale.

Recording and replay orientation rules do not change. Rotating the Veyarc UI may rearrange its controls, but it cannot transform a portrait workflow into a landscape workflow or bypass active-run safety checks.

## Accessibility and safety

- Interactive targets are at least 48 dp.
- Every icon has a concise content description. Decorative icons are hidden from accessibility services.
- Focus order follows visible execution and reading order.
- Block categories have text and shape cues in addition to color.
- Charts have text alternatives and do not require precise dragging to reveal values.
- Destructive actions require confirmation and name the affected workflow, folder, or run count.
- Opening editor, manager, or settings screens during active automation must use the existing safe cancellation path where required.
- UI refactoring must not change accessibility touch routing, raw-input capture, replay injection, service teardown, lock-screen behavior, or persisted system settings.

## Implementation sequence

This plan maps to Phase 6 in `Veyarc Android-First Implementation and Commit Plan.md`.

1. Add the Veyarc visual tokens and five-destination adaptive app shell.
2. Add safe Room migrations for workflow folders, favorites, run folders, and assignments.
3. Build Home statistics, recording entry, and favorite workflow actions.
4. Build Workflow Manager and move workflow-library responsibilities out of the monolithic editor screen.
5. Redesign Workflow Editor around the connected vertical block language without changing the workflow model.
6. Build Runs Manager and reuse the existing stored run-detail screen.
7. Build Settings and move current capability and latch controls into it.
8. Complete adaptive, orientation, large-font, accessibility, navigation-state, migration, and regression tests.

Each step must leave recording, replay, emergency controls, encrypted storage, and existing workflow validation working. Do not combine the data migration with the editor redesign in one commit.

## Acceptance checklist

- Five correctly ordered top-level destinations are always reachable from normal app screens.
- Home records and plays through existing safety gates and shows accurate local statistics.
- Favorites update immediately and never duplicate a workflow.
- New workflows appear in All workflows and Unfiled unless a destination folder was chosen.
- Folder deletion preserves its workflows or runs.
- Tapping a workflow opens the same workflow in Workflow Editor.
- The block editor clearly represents actions, conditions, branches, loops, and Stop at a glance.
- Every editable workflow operation still produces a valid revision or a specific validation error.
- Runs appear under their workflow by default and can be moved to custom folders.
- Run details remain available after filtering, sorting, moving, rotating, and returning from another destination.
- Settings reports live Accessibility, Shizuku, notification, and capture status without claiming permissions Veyarc does not have.
- Compact, tall, wide, portrait, landscape, tablet, 200 percent font-scale, dark-theme, and light-theme layouts have no clipped primary actions or unreachable controls.
- TalkBack can identify and operate navigation, workflow actions, block controls, charts, folders, run cards, and settings.
- Full local verification, API 24 acceptance, current-API emulator tests, and scoped Vivo V50 UI acceptance pass before the phase reaches `main`.

## Explicitly deferred

- Cloud accounts, synchronization, and sharing services.
- Community workflow discovery.
- AI-generated workflows or edits.
- Free-form graph editing that permits arbitrary jumps.
- A third-party chart framework.
- Strict replay input freezing without a proven privileged backend.
