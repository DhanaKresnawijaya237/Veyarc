# Veyarc Workflow Asset Storage and Runtime Loading Plan

## Status

Implementation progress:

- **Checkpoint 1 — Storage contracts: complete.** The Android data layer now defines a canonical
  manifest-only revision format, logical asset descriptors, template roles, and device-local keyed
  blob identities. Focused contract tests cover deterministic manifests, descriptor validation,
  strict decoding, and content deduplication identity. External `.veyarc` behavior is unchanged.
- **Checkpoint 2 — Shared encrypted blob store: complete.** Database version 10 adds shared blob,
  encrypted preview, and revision-asset mapping tables while marking existing revisions as legacy
  bundles. The new store deduplicates canonical image content, authenticates each blob with
  blob-specific associated data, rolls back orphan files after failed metadata commits, and removes
  blobs only after their final revision reference is deleted. Existing revision consumers remain on
  the legacy path.
- **Checkpoint 3 — Split repository loading APIs: complete.** Repository callers now choose
  metadata, document, descriptor, preview, single full-asset, editor, or explicit complete-bundle
  loading. Manager screens use metadata only. The editor opens stored revisions from documents and
  descriptors, loads previews only for visible items, and requests full assets only for editing,
  saving, or running. Legacy archives support selective inspection and single-asset import.
- **Checkpoint 4 — Legacy migration and portable bundles: complete.** New revisions store a small
  encrypted manifest plus shared blob mappings. Opening, running, or exporting a legacy revision
  converts it lazily, with the legacy row and file kept authoritative until the replacement is
  committed and verified. Portable export reconstructs the existing self-contained `.veyarc`
  bundle from the manifest and shared blobs. Legacy and manifest revisions can coexist.
- **Checkpoint 5 — Run preparation boundary: complete.** Visual runs now compile and collect their
  nested referenced image set while in an explicit `Preparing` phase, obtain capture capability,
  preload each unique encrypted blob under a device-relative memory budget, and precompute reusable
  template matcher data before the run record and execution clock begin. Runtime template lookup is
  a frozen in-memory map, repeated polling reuses the same decoded template, and preparation time is
  reported separately.
- **Checkpoint 6 — Cleanup and performance verification: complete.** The run controller releases
  prepared templates on every shared terminal path, and cancellation during preparation cannot
  enter `Running`. Bounded reconciliation now removes unreferenced blobs, invalid previews, orphaned
  blob/preview/revision files, and stale temporary files while preserving metadata for referenced
  blobs whose files are missing. Workflow copy and rename reuse shared mappings without loading or
  rewriting image pixels; the complete-bundle path remains only for portable export and explicit
  compatibility loading. Focused JVM tests and the API 37 data integration suite cover preparation,
  memory-only lookup, deduplication, migration, cleanup, and mapping reuse.

It supplements the existing Android implementation and commit plans. Its scope is deliberately
limited to workflow image assets, revision storage, asset loading, migration, cleanup, and the
timing boundary between run preparation and workflow execution.

## 1. Objectives

The new design must:

1. Store identical image content only once across workflows and revisions.
2. Prevent workflow revisions from embedding another complete copy of every image.
3. Let Home, Workflow Manager, Runs Manager, and other metadata-only screens operate without
   reading, decrypting, verifying, or decoding image payloads.
4. Load full-resolution images only in UI surfaces that display or edit them.
5. Keep disk access, decryption, image decoding, and template setup outside the timed portion of a
   workflow run.
6. Preserve immutable workflow revisions and portable `.veyarc` import/export.
7. Preserve encryption, integrity checks, Android Keystore protection, and secure in-memory cleanup.
8. Migrate existing revisions without losing workflows or making an interrupted migration
   destructive.
9. Make failures explicit and recoverable rather than allowing missing assets, corrupted blobs, or
   memory pressure to cause partial execution.

## 2. Current State and Problems

### 2.1 Current revision storage

Each revision is currently exported as a complete `WorkflowBundle`, encrypted as one revision file,
and indexed by `WorkflowRevisionEntity`. The bundle contains the workflow document and every image
asset used by that revision.

Consequences:

- An unchanged image is copied into every revision that contains it.
- Loading a revision imports the complete bundle.
- Asset integrity preparation traverses the bundle assets even when the caller only needs the
  workflow document.
- UI code can accidentally make metadata-only pages pay the cost of complete revision loading.
- Saving a revision becomes slower as the number and size of images grows.

### 2.2 Current runtime loading

`BundleImageTemplateSource` currently decrypts and decodes a template when `templateFor(assetId)`
is called for the first time. It caches that decoded template for the rest of the run.

This avoids repeated decoding during polling, but the first evaluation of each image condition can
incur storage, cryptographic, allocation, and decoding work after the run has already entered its
running phase. That delay can distort a time-sensitive workflow.

### 2.3 Required correction to the earlier lazy-loading proposal

Images must be lazy relative to browsing and editing, but they must not be loaded lazily in a
condition's timing-critical path.

The intended rule is:

> Load only assets needed by the current operation, but preload every unique image referenced by a
> run before the workflow clock starts.

## 3. Target Architecture

The target separates four concerns that are currently combined in a complete revision bundle:

1. **Workflow metadata** — name, latest revision, folders, favourite state, timestamps, and
   orientation. Room remains the authoritative index.
2. **Revision manifest** — immutable workflow document plus logical asset declarations, but no image
   payloads.
3. **Asset blobs** — independently encrypted, immutable, content-addressed image payloads shared by
   all revisions that contain identical content.
4. **Derived previews** — encrypted, replaceable thumbnails used only by image-displaying editor
   surfaces.

```text
Workflow summary
      |
      +---- Revision manifest ---- logical AssetId ----+
                                                    revision-asset mapping
                                                           |
                                                           v
                                                encrypted shared blob
                                                           |
                                                   optional preview
```

The manifest owns workflow meaning. The blob store owns bytes. A revision never owns another copy
of a blob already present in the store.

## 4. Identity Model

### 4.1 Logical asset identity

`AssetId` remains part of the portable workflow contract. Conditions continue to reference an
`AssetId`.

Logical metadata belongs to the revision and may include:

- asset name;
- intended role, such as full-screen background or cropped target;
- width and height;
- MIME type;
- the shared blob key.

Two logical assets may have different names or roles while sharing identical image bytes.

### 4.2 Physical blob identity

Introduce an internal, device-local `BlobKey` derived from canonical image content:

```text
canonical bytes = format version + width + height + tightly packed RGBA bytes
BlobKey = HMAC-SHA-256(device asset-identity key, canonical bytes)
```

Why use a keyed digest instead of a public plaintext hash:

- identical images still deduplicate locally;
- the database does not expose a reusable public fingerprint of a private screenshot;
- the blob key remains separate from portable `AssetId` values;
- AES-GCM can continue using random nonces rather than deterministic encryption.

The HMAC key and blob-encryption key must be Android Keystore protected and have separate aliases.

## 5. Storage Model

Exact Room names may follow existing project conventions, but the data relationships should be:

### 5.1 Asset blob table

`asset_blobs`

| Field | Purpose |
| --- | --- |
| `blobKey` | Primary key; device-local keyed content identifier |
| `encryptedFileName` | Unique file containing the encrypted canonical payload |
| `encryptedByteLength` | Diagnostics and bounded reads |
| `widthPx`, `heightPx` | Validation and memory estimation without decoding |
| `mimeType` | Stored representation metadata |
| `formatVersion` | Blob codec migration |
| `createdAtMs` | Local maintenance metadata |

Do not store a mutable reference count. The revision-to-blob mapping is the source of truth, which
avoids count drift after failures or migrations.

### 5.2 Revision asset mapping table

`revision_assets`

| Field | Purpose |
| --- | --- |
| `workflowId`, `revision`, `assetId` | Composite logical identity |
| `blobKey` | Shared physical blob reference |
| `name` | User-facing name unique within the revision |
| `role` | Full-screen, cropped target, or other supported role |
| `widthPx`, `heightPx` | Selection validation without loading pixels |
| `mimeType` | Logical descriptor |

Deleting a revision cascades its mapping rows. A blob becomes collectible when no mapping row
references its `blobKey`.

### 5.3 Revision manifest

The encrypted revision manifest contains:

- the immutable `WorkflowDocument`;
- logical asset descriptors and references;
- producer and format versions;
- no image payload bytes.

The current `WorkflowRevisionEntity` continues to point to an encrypted revision file, but the
revision format must distinguish legacy complete bundles from new manifest-only revisions.

### 5.4 Derived previews

Editor thumbnails should not require decoding the full-resolution asset every time they become
visible.

- Generate a bounded preview when importing or migrating an asset.
- Encrypt it independently and associate it with the blob key.
- Treat it as a rebuildable cache, not workflow truth.
- Load previews only for visible rows or grid cells.
- Keep a bounded in-memory preview cache.
- Never use preview pixels for workflow condition evaluation.

If preview generation fails, the full asset remains valid and the UI may show a placeholder until
the preview is rebuilt.

## 6. Repository APIs and Loading Boundaries

Replace the single broad “load the complete revision bundle” path with explicit operations. Names
are illustrative; the important requirement is that each call reveals its cost.

### 6.1 Metadata-only operations

- list workflow summaries;
- list revision summaries;
- list logical asset descriptors;
- list run summaries;
- inspect folder and favourite metadata.

These operations must never open asset blob files.

### 6.2 Document operations

- load a revision manifest and workflow document;
- validate logical asset references against descriptor rows;
- do not decrypt or decode image blobs.

The compiler should be able to validate document structure and collect referenced `AssetId` values
from this representation.

### 6.3 Preview operations

- request one preview by logical asset or blob key;
- cancel the request when its UI item leaves composition;
- cache only a bounded number of decoded previews;
- never cause unrelated workflows or images to load.

### 6.4 Full-resolution operations

- request one full-resolution asset for crop, background, geometry, or condition editing;
- decrypt and decode it off the UI thread;
- hold it only while the owning editor surface needs it;
- clear sensitive byte buffers when released.

### 6.5 Run operations

- resolve the compiled plan's unique referenced assets;
- preload them as a run-scoped set;
- expose only memory-resident templates to the evaluator;
- release the complete run cache during every terminal path.

### 6.6 Export operation

Portable `.veyarc` export remains self-contained:

1. Load the requested immutable manifest.
2. Resolve its mapping rows.
3. Read each required shared blob once.
4. Reconstruct the portable bundle in a bounded streaming operation.
5. Preserve existing manifest, archive, integrity, and size validation.

Internal deduplication must not change the external bundle contract unless a separate schema change
is explicitly approved.

## 7. Revision Save Flow

Saving a revision must avoid rewriting unchanged images.

### 7.1 Preparation

1. Validate the workflow document and logical asset names.
2. Determine which assets are existing references and which contain newly imported pixels.
3. Canonicalize every new image once.
4. Calculate its `BlobKey`.
5. Reuse an existing matching blob row when one exists.
6. Encrypt and write only missing blobs.
7. Generate encrypted previews only for missing or invalid preview entries.
8. Write the small encrypted revision manifest.

### 7.2 Commit and rollback

Room and the filesystem cannot share one atomic transaction, so saving must use the existing
write-first/commit-metadata/cleanup pattern carefully:

1. Write missing data to temporary or uniquely named files.
2. Verify the completed files.
3. In one Room transaction, insert the revision row, blob rows, and revision-asset mappings.
4. Publish any temporary files using atomic rename where the platform permits it.
5. On failure, remove files created by this attempt only when they are still unreferenced.
6. Never delete or overwrite a previously committed blob during rollback.

Repository save operations remain process-serialized unless measurements demonstrate a need for
more concurrency.

## 8. Workflow Run Lifecycle and Timing

### 8.1 Required phases

A visual workflow run has two distinct phases:

```text
Start requested
    -> Preparing
        -> compile and validate
        -> resolve referenced assets
        -> obtain required screen-capture capability
        -> decrypt/decode templates
        -> prepare template-side matcher data
    -> Running
        -> start workflow clock
        -> execute steps and evaluate conditions
    -> Completed / Failed / Cancelled / Stopped
        -> release frames, templates, and sensitive buffers
```

The UI should say **Preparing workflow** while assets are being prepared. Preparation duration must
not be reported as workflow execution duration.

### 8.2 Referenced-asset discovery

After compilation, traverse the executable plan and collect every unique `AssetId` referenced by:

- screen-state conditions;
- image-present conditions;
- image-absent conditions;
- nested `All`, `Any`, and `Not` conditions;
- conditions inside `If`, `While`, and `Wait Until` structures.

Declared but unused assets are not part of the run set and must not be loaded.

### 8.3 Preloading guarantee

Before entering `Running`:

1. Resolve every referenced `AssetId` to a revision mapping.
2. Confirm the referenced blob exists and its descriptor is compatible.
3. Read, authenticate, decrypt, and decode every unique referenced blob.
4. Deduplicate templates that share the same blob key.
5. Precompute reusable template-side matching data where the evaluator supports it.
6. Freeze the resulting run-scoped template map.

Once `Running` begins, `templateFor(assetId)` must perform an in-memory lookup only. It must not do
file I/O, Keystore operations, decryption, image decoding, or first-use matcher initialization.

### 8.4 Polling behavior

`Wait Until` and loop polling must reuse the same decoded template and reusable matcher data on every
poll. Only the live screen frame and the comparison work should repeat.

Polling intervals and timeouts begin after preparation. They must not include template loading.

### 8.5 What remains timing-dependent

Preloading removes avoidable asset-loading jitter, but it cannot make Android hard real-time.
Execution still includes:

- OS thread scheduling;
- MediaProjection frame availability;
- live screen capture and copying;
- comparison cost, which depends on the condition mode and search region;
- accessibility or privileged input-dispatch latency;
- target-app rendering and response time.

Run events should record preparation duration separately from execution duration so these costs can
be diagnosed honestly.

### 8.6 Memory safety

Before decoding, estimate run memory from descriptor dimensions:

```text
decoded RGBA bytes = width * height * 4
estimated run bytes = sum(unique referenced blobs) + matcher overhead
```

For the first implementation:

- preload all unique referenced templates;
- use a conservative device-relative memory budget rather than a universal arbitrary image count;
- if the complete run set cannot be held safely, fail during **Preparing** with an actionable error;
- never start a time-sensitive run and then evict/reload required templates mid-execution;
- never risk an out-of-memory crash merely to avoid a preparation failure.

A future opt-in streaming/prefetch mode may be considered only if real workflows exceed the
preload budget. It is not part of the first implementation because it weakens timing consistency and
adds branch-prediction and eviction complexity.

## 9. Runtime Cache Ownership and Cleanup

The run controller owns one run-scoped template cache.

It must be released on:

- successful completion;
- validation or compilation failure after partial preparation;
- preparation failure;
- user cancellation;
- timeout;
- explicit stop;
- MediaProjection revocation;
- accessibility disconnect;
- controller close or unbind;
- coroutine cancellation;
- unexpected exception.

Cleanup must:

- clear decoded pixel buffers where practical;
- release matcher-side arrays;
- close asset handles;
- release ephemeral screen frames;
- leave no cache reachable by a later run.

The existing fail-closed replay and accessibility teardown rules remain unchanged.

## 10. Garbage Collection

Blob deletion must be reference-safe.

### 10.1 When a revision is deleted

1. Delete the revision and its mapping rows transactionally.
2. Query for blob rows with no remaining mappings.
3. Delete their encrypted blobs and previews.
4. Delete their blob rows after file deletion succeeds.

If file deletion fails, retain enough metadata for cleanup to be retried. Do not remove a blob row
first and leave an undiscoverable encrypted file behind.

### 10.2 Maintenance reconciliation

Provide a bounded maintenance operation that can detect:

- database rows whose blob files are missing;
- unreferenced blob rows;
- orphaned blob or preview files;
- stale temporary files from interrupted saves;
- invalid or outdated preview cache entries.

It must never delete a referenced blob solely because one revision failed to load.

## 11. Legacy Revision Migration

Migration must be incremental and recoverable.

### 11.1 Schema migration

Add the blob and mapping tables plus a revision storage-format marker. Existing revision rows become
`LEGACY_BUNDLE`; new revisions use `ASSET_MANIFEST`.

### 11.2 Lazy migration path

Migrate a legacy revision when it is opened for editing, prepared for a run, or exported:

1. Read and authenticate the existing revision file using the current codec.
2. Import its complete bundle using existing safety limits.
3. Canonicalize and deduplicate its assets into the shared blob store.
4. Write and verify the new manifest.
5. Insert mappings and switch the revision's format marker in one Room transaction.
6. Delete the legacy revision file only after the new representation is committed and reloadable.

If migration is interrupted, the legacy row and file remain authoritative and the operation can be
retried.

### 11.3 Optional background migration

Do not add background migration initially. Lazy migration covers real access without making app
startup expensive. Add scheduled migration only if measurements show that repeated legacy access or
storage reclamation warrants it.

## 12. UI Loading Rules

### Home, Workflow Manager, and Runs Manager

- Use Room summaries only.
- Do not load revision manifests merely to render cards.
- Do not read asset descriptors or previews unless a visible feature explicitly needs them.

### Workflow Editor

- Load the workflow document and asset descriptors when opening a revision.
- Load previews only for currently visible template items.
- Load full-resolution pixels only for the selected crop, background, geometry, or condition editor.
- Cancel obsolete loads when the user changes selection or leaves the screen.
- Show stable placeholders or compact progress indicators without shifting unrelated layout.

### Save feedback

- Saving many logical asset references should remain fast because unchanged blobs are reused.
- Show one save-progress state for actual persistence work.
- Do not show per-image loading indicators across unrelated pages.

## 13. Security and Privacy Requirements

- All full-resolution blobs and previews remain encrypted at rest.
- Use authenticated encryption with blob identity and format version included in associated data.
- Use bounded reads before allocation.
- Verify dimensions, decoded length, and format before exposing a template.
- Never write raw screenshots or decoded templates to logs, Room, or temporary external storage.
- Keep portable asset IDs separate from internal device-local blob keys.
- Clear plaintext buffers after import, save preparation, preview generation, and run completion.
- Preserve the rule that MediaProjection frames are ephemeral and are never saved unless the user
  explicitly creates a template.

## 14. Failure Behavior

The system must fail before execution when:

- a referenced logical asset has no mapping;
- a blob file is missing or fails authentication;
- metadata dimensions do not match decoded data;
- the asset role is incompatible with its condition;
- the complete timing-critical run set cannot be prepared safely;
- MediaProjection is unavailable for a visual workflow.

Errors should identify the affected template by user-facing name when available, without exposing
private pixels, raw cryptographic details, or internal file paths.

No run may enter `Running` with a partially prepared image set.

## 15. Implementation Checkpoints

These are implementation checkpoints, not automatic authorization to create commits.

### Checkpoint 1 — Storage contracts

- Define internal blob identity, descriptors, mappings, and manifest format.
- Add codec and repository tests before changing consumers.
- Keep external `.veyarc` bundle behavior unchanged.

### Checkpoint 2 — Shared encrypted blob store

- Add Room entities and DAO operations.
- Add encrypted blob and preview file operations.
- Implement deduplicated save, rollback, reference queries, and garbage collection.

### Checkpoint 3 — Split repository loading APIs

- Add metadata-only, document-only, descriptor, preview, and full-asset operations.
- Move manager screens to metadata-only calls.
- Move editor surfaces to visible-item preview loading.

### Checkpoint 4 — Legacy migration and portable bundles

- Add lazy legacy migration.
- Reconstruct exports from manifests plus shared blobs.
- Verify old and new revisions can coexist during rollout.

### Checkpoint 5 — Run preparation boundary

- Add an explicit `Preparing` phase.
- Collect unique referenced assets from the compiled plan.
- Preload and freeze the run template cache before starting execution time.
- Make evaluator lookups memory-only.
- Record preparation duration separately.

### Checkpoint 6 — Cleanup and performance verification

- Exercise every run termination path.
- Reconcile orphaned files and rows.
- Measure manager load, revision save, editor preview, preparation, and condition polling behavior.
- Remove the legacy complete-bundle path only after all stored revisions can migrate safely.

## 16. Test Plan

### Unit tests

- Identical canonical pixels produce the same blob key.
- Different dimensions or pixels produce different blob keys.
- One blob can back multiple logical assets and revisions.
- Saving a new revision with unchanged assets writes no new blob.
- Deleting one revision does not delete a blob referenced elsewhere.
- Deleting the final reference makes the blob collectible.
- Failed saves do not remove previously committed blobs.
- Corrupted or substituted ciphertext fails authentication.
- Metadata-only and document-only loads never open blob files.
- Preview loading never returns full-resolution evaluation data.
- Referenced-asset discovery includes nested and composite conditions and excludes unused assets.
- Run template lookup performs no read, decryption, or decode after preparation.
- Repeated polling reuses the same decoded template and matcher data.

### Migration tests

- A legacy bundle migrates to a manifest plus mappings without changing document or asset meaning.
- Two legacy revisions sharing an image converge on one blob.
- Interrupted migration leaves the legacy revision readable.
- New and legacy revisions can be listed, opened, run, and exported together.
- Database upgrade preserves every workflow, revision, folder, favourite, and run record.

### Android integration tests

- Workflow Manager remains responsive with many revisions and large asset collections.
- Only visible editor previews are requested.
- Full-resolution data is released after leaving an image editor.
- A visual run remains in `Preparing` until all referenced templates are memory-resident.
- Execution duration excludes preparation duration.
- The first and later evaluations of the same condition use the same prepared template.
- Cancellation during preparation cleans partial buffers and never starts input dispatch.
- MediaProjection revocation and accessibility teardown clear the run cache.

### Physical-device tests

- Compare first-condition timing before and after preloading.
- Run workflows containing several full-screen and cropped templates repeatedly.
- Confirm condition timing does not include a first-use decode spike.
- Confirm device responsiveness and ordinary navigation after completion, cancellation, and failure.
- Test memory-heavy workflows near the supported device budget without allowing an out-of-memory
  crash.

## 17. Acceptance Criteria

The work is complete only when all of the following are true:

1. Unchanged images are physically stored once across revisions.
2. Saving a metadata-only revision does not rewrite image payloads.
3. Home, Workflow Manager, and Runs Manager perform no asset blob reads.
4. Opening an editor does not decode every full-resolution image.
5. Portable import and export remain compatible and independently validated.
6. Legacy revisions migrate without destructive in-place conversion.
7. A visual workflow cannot enter `Running` until all referenced templates are prepared.
8. Timed condition execution performs no image file I/O, Keystore call, decryption, or decoding.
9. Repeated polling reuses prepared templates.
10. Preparation and execution durations are reported separately.
11. Every completion and failure path releases sensitive image memory.
12. Revision deletion never removes a blob still referenced by another revision.
13. Measured manager and editor behavior no longer degrades in proportion to unrelated image data.

## 18. Explicit Non-Goals

The first implementation will not add:

- cloud synchronization;
- cross-device shared blob identifiers;
- unencrypted thumbnail caches;
- speculative background migration;
- branch-predictive asset eviction;
- mid-run template streaming for time-sensitive workflows;
- a new external `.veyarc` format unless existing compatibility cannot be preserved;
- hard real-time guarantees that Android cannot provide.

## 19. Final Design Rule

The storage system should be lazy everywhere except the timing-critical boundary:

> Browse with metadata, edit with on-demand previews, prepare runs with all referenced templates,
> and execute only from memory.
