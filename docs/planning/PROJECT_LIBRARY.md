# Local JSON project library

Baseline: [TavernForge kickoff charter](../../TavernForge_Project_Kickoff.html), sections 03 and 06. Supervisor-scoped next increment, 1 November 2026. Builds on [canonical contracts](ARCHITECTURE.md), the [approved UI](../DESIGN_DECISION.md), and [verified JSON formats](../build/JSON_FORMAT_SUPPORT.md). Execution evidence belongs in [current status](../STATUS.md).

## User outcome

After explicitly saving an imported character or lorebook JSON, the user can close the app, remove access to the original document, and reopen the same source from the local library. The library supports search, sorting, a local title, duplication, and confirmed deletion. A local title is metadata and never rewrites the character name or other JSON fields.

This is the first M2 persistence increment, not completion of M2 or the full authoring workflow. Editing/export screens, asset association, PNG/CHARX, credentials, Gemini, sharing/backup, and full migration UI remain later work. No dependency baseline change or community release is authorized by this scope. The subsequent [repository visibility decision](REPOSITORY_DECISION.md) permits public source hosting.

## Data and safety contract

- Room 3 version 3.0.3 owns saved-project metadata and stable managed-source references. Original bounded JSON bytes live in app-private files, with size and SHA-256 verified on read. No dependency on a retained picker URI and no source rewrite.
- Each imported snapshot starts at document revision zero; original and current content are identical until a future reviewed editing increment adds persistent patches/revisions. The existing format engine is the shared parsing/validation authority.
- Parsed JSON with schema errors or an unsupported future format may be saved as a draft. Saving a draft is distinct from strict export. Malformed/unparseable JSON cannot enter this library increment; the inspector still reports its parse diagnostics.
- Source format/version, filename, checksums, timestamps, local title and validation-derived draft status remain attached to each record. Unknown fields, null/missing distinctions, numbers and exact original bytes survive save/reopen/duplicate.
- Managed writes are finalized atomically before their Room references are committed. Interrupted or failed saves must not expose broken saved rows. Recovery only removes unreferenced managed files; database-read failures must never cause referenced files to be deleted.
- Deletion removes only the local project. Shared/content-addressed files, if used, survive while referenced by another project. The original Downloads/provider document is never deleted. No destructive Room migration fallback.
- Cancellation, overlapping operations, stale UI completions, missing/corrupt files and database failures must yield clear errors without replacing another selected project or corrupting committed data.
- Non-secret library sorting preferences may use the pinned DataStore 1.3.0. No credential or generation permission storage is introduced.

## Ownership and acceptance

| Owner | Owned paths | Output and acceptance |
| --- | --- | --- |
| Supervisor | This scope, STATUS, README, integration/review and Git/device deployment | Confirm prior work is pushed; review contracts, tests and runtime evidence; respect the recorded repository visibility decision |
| Persistence agent | New core model types; `data/src/main`, `data/build.gradle.kts`, `data/schemas`, storage report | Repository/Room/files implementation with exported schema; safe atomic writes and restart behavior |
| UI/build agent | `app`, root/catalog/locks, CI, library UI report | Save/reopen/library actions with approved adaptive layout; version 0.3.0-internal; sole Gradle owner |
| Independent test agent | `data/src/test`, `data/src/androidTest`, verification report | Preservation/failure/recovery regression suite and actual Room/device evidence; no competing Gradle |

The persistence owner publishes exact Kotlin signatures before consumers finalize. Build and dependency changes use the already tested Room3/KSP toolchain, with cached artifacts preferred on the user's unstable connection. Tests must distinguish pure fault-injection checks from actual Room/database/file behavior on Android. Existing JSON suites must remain green. Record exact commands, exported schema, final APK fingerprint and any unperformed hardware checks before reporting this increment complete.
