# Saved JSON revisions and migration

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), sections 04–06. Implements the storage/format portion of [saved JSON editing and export](../planning/JSON_EDIT_EXPORT.md). [Current status](../STATUS.md) records executed build/device evidence; this document describes the implementation and its contracts.

## Original and current content

`StoredProject.source` remains the immutable imported source. `currentSource` defaults to that same source and `documentRevision` defaults to zero, preserving existing callers. `ProjectSummary.sourceSha256`, source byte size, format, version and filename continue to describe the original import. Draft status describes current validation; library title remains independent of JSON content.

`ProjectRecord` and Room's `ProjectEntity` add `currentFileKey`, `currentSha256` and `currentSizeBytes`, initially copied from the original reference. The existing media type applies to both snapshots and is restored exactly on load. Both original and current files are hash/size/path checked. A file shared by either role or by multiple projects survives until no row references it.

The file store remains immutable and content-addressed. It stores only original and latest current snapshots, not a complete revision history. After a successful edit, unreferenced previous current snapshots may be pruned. Duplicate projects retain the same original/current snapshots and current revision, but receive a new ID/title/timestamps; subsequent edits are independent. Deletion/recovery considers both reference columns and never guesses references after a failed complete-index read.

## Engine boundaries

- `CharacterCardEngine.parseValue(bytes): JsonValueResult` parses an explicitly edited field value through the same bounded JSON parser as document intake. It accepts scalar values but still rejects duplicate object keys, malformed UTF-8, resource overflow and invalid JSON; callers cannot use a lossy permissive parser before patching.
- `restore(original, current, revision): ParseResult` checks both bounded JSON objects, nonnegative revision, unchanged media type, format/version markers and source kind. Revision zero requires exact original/current byte equality. Nonzero revision is allowed only when the original has a supported contract. The restored document retains the original source kind when an explicit edit leaves a discriminator-free draft too incomplete for fresh recognition.
- `snapshot(document): ExportResult` serializes bounded local draft content. It deliberately does not assert schema validity or permit external strict export. Original bytes are emitted for untouched revision zero; edited trees retain unknown values and number lexemes through the existing encoder.
- `export(document)` remains the strict validation/export boundary. Invalid drafts, unsupported/future source contracts and format changes cannot gain valid export merely because a snapshot was saved.

Restore never treats a nonzero revision as untouched. An explicit edit that leaves original-equal current bytes is valid at a nonzero revision; it remains an edited revision rather than an assertion of untouched-source fidelity.

## Edit commit and conflict handling

`ProjectRepository.applyEdits(id, expectedRevision, operations)` loads and restores the saved original/current pair, rejects stale revisions and unsupported editing contracts, and applies the existing bounded patch pipeline. An empty patch list returns the current project without a new revision after checking the expected revision. A successful nonempty batch increases the saved revision once, even if its explicitly requested replacement is semantically identical.

The repository finalizes and syncs the new immutable snapshot before `ProjectIndex.updateContent`. The production DAO uses one conditional SQL update matching ID and expected revision; only current references, current draft status, timestamp and revision change. The original references and library title do not change. No matching row returns `REVISION_CONFLICT`, never an unconditional overwrite.

The file/row commit runs under the same single-writer mutex and bounded non-cancellable section as import persistence. Cancellation before the commit section saves nothing; a cancellation arriving within that section may be reported after a consistent commit. An index call may commit before throwing, so recovery rereads every reference before deleting any file. If that read also fails, both old and potentially committed snapshots are retained. Prune-only failure leaves harmless orphans and does not invalidate an otherwise committed project.

## Room schema 1 to 2

The public `MIGRATION_1_2` uses Room 3's `Migration.migrate(SQLiteConnection)` and is registered on `createProjectDatabase`. Room executes it transactionally. It creates a table containing all existing columns plus the three current-reference columns, copies every existing row with current references initialized from its original, and renames the completed table after replacing the old table. Original project IDs, revisions, titles, timestamps, validation metadata, filenames, media types and hashes are retained. No managed file is read or rewritten by migration. No destructive migration fallback is enabled.

The API was checked against cached official Room 3.0.3 runtime sources (`commonMain/androidx/room3/migration/Migration.kt`, `RoomDatabase.android.kt`) and SQLite 2.7.1's `SQLiteConnection.execSQL` source. Source/API inspection is not a passing migration test. The independent tester supplies a frozen v1 database upgrade test and edit/reopen checks; the supervisor owns schema generation, Gradle execution and real device verification.

## Verification boundaries

The implementer ran `git diff --check` on the owned production paths successfully and did not run Gradle. Root is the sole build owner. Independent tests cover draft snapshots, bounded field parsing, original/current preservation, stale revisions, ambiguous commits, edited duplication/deletion, and migration. Exact executed commands/results belong in [current status](../STATUS.md) and the testing report. Full revision history, multi-process writers, backup/restore and physical power-failure guarantees remain outside this increment.
