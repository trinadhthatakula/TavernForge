# Local JSON project storage

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), sections 03 and 06. Implements the bounded snapshot-import slice of [M2](../planning/ROADMAP.md) using the [canonical architecture](../planning/ARCHITECTURE.md). See [current status](../STATUS.md) for executed verification and device evidence.

## Contract and scope

The Android `:data` module depends on the pure model/format modules. `ProjectRepository` exposes `observeProjects`, `saveImported(fileName, source)`, `load`, `rename`, `duplicate`, and `delete`. `createProjectRepository(context)` provides one repository for the application process. The app assembles UI/DI; storage does not depend on Compose.

`ProjectSummary` stores library identity/title, display filename, detected source kind/version, draft status, timestamps, source SHA-256 and byte size. `StoredProject` pairs that summary with an owned `SourceEnvelope`. `ProjectRecord.documentRevision` is zero: original and current payload are identical in this slice. Renaming changes only the library title. Titles are limited to 256 characters; provider display filenames to 255. Display names are never used as filesystem paths. Source media types are preserved exactly; values over 255 characters are rejected before writing instead of being truncated.

A save first parses with the existing bounded JSON engine. Successfully parsed JSON objects may be saved even when schema-invalid, unknown, or from a future version; those imports are marked as drafts. Syntax/resource failures are rejected before managed-source writes. Storage never calls strict export to normalize or gate a save, adds defaults, changes a format discriminator, fetches assets, or depends on continued provider URI access. Original bytes remain exact, including whitespace, unknown keys, nulls and numeric lexemes.

Payload editing/export UI, migrations between card versions, PNG/APNG/CHARX/media, DataStore preferences and credentials are outside this repository slice. The previously defined `CharacterProject` remains a planned editing/export model; the new summary is the saved-library read model and does not select a different export target.

## Room 3 metadata

The requested pinned Room **3.0.3**, KSP **2.3.13**, Kotlin/AGP versions and other toolchain pins are unchanged. Room processing uses KSP; Koin continues to use its compiler plugin in the app. `ProjectDatabase` version 1 exports its schema to `data/schemas`. One `projects` table contains metadata and a managed-source key, media type and revision; it contains no JSON/image blobs or provider URI dependency. IDs are generated locally, independently of upstream character/lore-entry IDs. Insert uses `ABORT`, never replace.

`ProjectDao` uses individual atomic insert/update/delete statements. Recovery obtains its full reference set through one complete `SELECT`. `RoomProjectIndex` adapts the DAO to the repository's index seam. `createProjectDatabase(context, name)` permits isolated real-database tests with explicit close/reopen. The Room context builder installs `AndroidSQLiteDriver` when no driver is specified, verified in cached Room 3.0.3 sources (`RoomDatabase.android.kt`, `build`). No additional bundled SQLite dependency is required. No destructive migration fallback is enabled; later schema versions need explicit migrations.

## File/index ordering and interruption recovery

Originals live in the dedicated app-private `files/project-sources` directory. A source's immutable key is its lowercase SHA-256 plus `.json`. Duplicate projects share the original source; they have distinct project IDs, library titles and timestamps.

1. Serialize each repository operation with the process-wide repository's mutex. Validate/derive import facts before the save critical section.
2. Write bounded bytes to a temporary file in the same directory, sync the file descriptor, and verify actual disk size/hash.
3. Atomically move the file to its content-addressed name, then sync the directory entry before inserting its Room reference. Existing sources are reverified and synced before reuse. Source-directory creation also syncs its parent entry.
4. Insert the metadata row. If insertion fails, its result may be ambiguous; read the entire index again before pruning anything. If that read fails, retain the files.
5. On reopen/recovery, read every index reference successfully before deleting temporary and unreferenced source files. Keep referenced files even when corrupt. Unknown files/directories are left alone; cleanup never recurses.

Deletion commits the metadata removal first, then prunes only files absent from a fresh complete reference set. A shared source remains while another project references it. Cleanup errors after commit leave harmless orphans for later recovery rather than a visible project referencing a removed source. Startup database-read errors are reported without guessing at references. Prune-only failures do not block access to healthy projects; corrupt references fail their own load while their files remain untouched. No stored project is automatically deleted to repair corrupt data.

The bounded source-write/commit section and mutation commits run under `NonCancellable` after checking cancellation. Cancellation before that section does not save; cancellation after entry may be reported to the caller even though the operation completed. This explicit boundary avoids abandoning a file/row transition. Concurrent callers use the same factory singleton. Multiple application processes, or multiple live custom repository instances over the same stores without a shared mutex, are not supported; test constructors expose a shared mutex when needed.

## Integrity and failure behavior

Every load and duplication checks the source key, bounded recorded size, regular-file/no-symlink status, actual size and SHA-256 before parsing. The parsed kind and version must match the recorded source facts. Corrupt/missing source failures retain the metadata and available bytes. A database failure during recovery never triggers deletion of potentially referenced files. Error codes distinguish invalid input, missing project, corrupt source, filesystem failure and database failure; UI messages contain no source content, private paths or provider exception text. Coroutine cancellation propagates rather than becoming an ordinary storage error.

The file implementation requests file and directory synchronization before committing references. Unsupported atomic moves or directory synchronization fail the save rather than pretending durability. JVM fault-injection tests demonstrate ordering and recovery logic, not guaranteed hardware behavior during a physical power loss; Android filesystem/SQLite close-reopen and device checks need separate evidence. App backup must continue to treat database and sources as one ownership unit; the current app disables backup.

## Verification seams and evidence

`ProjectIndex` and `ProjectFileStore` are injected into the actual `RoomProjectRepository` orchestration. Independent tests can fail writes, fail or ambiguously commit an insert, fail recovery reads, cancel before/within commit, and reopen with the same persisted stores. `ManagedJsonFiles` exercises real bounded disk reads, integrity checks, atomic staging, path confinement, orphan cleanup, and shared-source preservation. Real Room tests separately exercise the generated schema/DAO/default Android driver across close/reopen; a fake index is not evidence that Room opened successfully.

Compilation, exported schema contents, exact test counts, offline restart behavior and Android device verification are recorded by the build/test/supervisor owners in current status once executed. This document describes the implemented design and does not itself assert those checks passed.
