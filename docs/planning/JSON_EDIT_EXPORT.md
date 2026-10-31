# Saved JSON editing and export

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), sections 03–06. Continues the verified [local library](PROJECT_LIBRARY.md) under the [approved hybrid UI](../DESIGN_DECISION.md). Scope agreed by the supervisor from the user's instruction to continue, 1 November 2026. Evidence belongs in [current status](../STATUS.md).

## Outcome and boundaries

Version 0.4.0-internal lets a user edit a saved character/lorebook JSON, review validation, save a new local revision, and export valid current JSON through Android's CreateDocument picker. Original imported bytes remain immutable. Editing and export use the existing version-aware engine and preserve unknown fields/value types/numeric lexemes. No conversion, provider writeback, network dependency, new toolchain dependency, or community release is introduced.

The first editor provides named text fields for characters and book/entry text where applicable, plus explicit JSON-value editing for missing/wrong-type fields and advanced content. Each explicit edit becomes a bounded JSON patch against the retained raw tree; untouched values are never reconstructed from forms. Source format/version markers cannot change. A V3 missing group greeting can be explicitly repaired to an empty array. Unsupported/future JSON stays inspectable and saved unchanged; editing/export require a supported source contract. Manual creation, complete specialized lore controls, PNG/CHARX, binary assets, conversion, and Gemini remain later increments.

## Persistence contract

- Saved projects retain original source metadata/files and a separate current snapshot plus monotonically increasing document revision. Existing v1 rows migrate without rewriting their source or losing identity/title/timestamps.
- `StoredProject.source` continues to mean original source. Add current source/revision with backward-compatible defaults. Load validates both references and restores the source format even when an explicit edit leaves a discriminator-free draft structurally invalid.
- Repository edit commit accepts expected persisted revision and explicit patches, applies the same engine rules, atomically finalizes the current file, then conditionally updates the row. Stale revisions fail without overwriting current data. Rename remains metadata-only.
- Reopen, duplicate, delete and recovery retain both original/current references; a shared file remains until its last reference is removed. Database failure never causes guessed pruning. Schema-invalid edits may save as drafts; malformed JSON and format conversion may not.
- Expose bounded draft snapshot/restore operations separately from strict export. Restore validates original/current/revision and never falsely claims edited content is untouched.

## UI and export contract

- Editing is explicit, with pending changes, discard confirmation, save, diagnostics and conflict feedback. Changing project/import or leaving an editor cannot silently discard pending edits. ViewModel retains drafts through activity recreation; unsaved process-death recovery remains outside this increment and is disclosed.
- Export prepares immutable bytes from the exact validated current revision before launching the system picker. Invalid or unsupported documents cannot launch strict export. Confirmation shows source format and whether formatting changed; no conversion is implied.
- Picker cancellation writes nothing. Writes use scoped ContentResolver output on IO, close resources, detect errors, and never report success on a failed/partial write. Report that a provider may retain a partial document after failure. Do not delete arbitrary provider documents as cleanup.
- Guard busy/repeated operations, stale picker completions and project replacement. Export does not silently save an unaccepted edit. Original-source backup is not presented as strict export.

## Ownership and acceptance

| Owner | Owned paths | Output and checks |
| --- | --- | --- |
| Supervisor | Scope/STATUS/README, root build integration, Git/CI/device | Review contracts; sole Gradle owner; focused suites, app/data lint, install and migrate existing phone library, picker edit/save/reopen/export smoke |
| Persistence/format implementer | `core/model/.../ProjectModels.kt`, `format/src/main`, `data/src/main`, generated data schema (via root build), build report | Publish exact API signatures first; migration v1→v2; preserved original and current snapshots; safe revision/conflict/failure behavior |
| UI implementer | `app/**` including app tests/build/version, UI report | Approved adaptive native controls, bounded editing/review/save/export; 0.4.0-internal code 4; ViewModel/writer regression checks; no Gradle executions |
| Independent tester | `format/src/test`, `data/src/test`, `data/src/androidTest`, testing report | Unknown/null/number preservation, no-op export, migration, stale revisions, file failure/recovery, duplicate/delete edited projects; no competing Gradle executions |

No baseline version changes are needed. Existing library data is never cleared to make migration pass. Root runs required checks after owners hand off; JVM-only evidence does not prove the document picker, migration or provider writes on a physical device.
