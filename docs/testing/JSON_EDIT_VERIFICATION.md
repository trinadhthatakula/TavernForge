# Saved JSON edit and export verification

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), sections 04–06. Scope: [saved JSON editing/export](../planning/JSON_EDIT_EXPORT.md), extending the [library checks](PROJECT_LIBRARY_VERIFICATION.md). [Current status](../STATUS.md) owns the integrated build and device outcome.

## Current state

On 1 November 2026 the supervisor's complete offline build passed **179 JVM tests**: 4 model, 91 format, 34 data and 50 app, zero failures/errors/skips. Both Android lint reports contain zero issues. Command: `./gradlew :core:model:test :format:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :data:assembleDebugAndroidTest :data:lintDebug :app:lintDebug --offline --no-daemon --console plain` (27s). After the compact-navigation/autofocus correction, `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon --console plain` also passed (20s); engine/storage unchanged.

The user authorized existing AVDs after disconnecting the physical phone. `FrameGallery_API36_1` launched as `emulator-5554`, API 36, without wiping data. After installing `data-debug-androidTest.apk` with `adb install -r -t`, the raw package-filtered runner reported **`OK (9 tests)`**, 0.272s, `INSTRUMENTATION_CODE: -1`. This includes all six original Room checks and three new migration/edit/duplicate checks. It is emulator SQLite/file evidence, not physical power-loss evidence. System-picker editing/export UI smoke subsequently passed as detailed below.

Version 0.4.0-internal/code 4 APK installed successfully: 15,402,343 bytes, SHA-256 `fcb460fd8eccb4f8618f4931cf59b2f5bec3347ed9b9e65e20199d10ca9b3564`. Initial cold launch before the navigation correction returned `Status: ok`, 1348ms. Fixture integrity audit and actionlint pass. The tester independently wrote/reviewed tests but did not execute builds or operate the emulator; the supervisor observed the results above.

## Real emulator picker and persistence check

On the same existing API 36 AVD, the supervisor selected the synthetic `spec-v3-rich.json` copied as `TavernForge-edit-test.json` through OpenDocument, saved it to the library, and changed `/data/name` from `Archive Keeper` to `Archive Keeper AVD` using Edit value → Apply → Save. The UI reported revision 1. After `adb -s emulator-5554 shell am force-stop com.valhalla.tavernforge` and `am start -W`, cold launch succeeded in 1071ms and the saved project reopened at revision 1 without unexpected keyboard focus.

Export saved JSON displayed the immutable revision/format/byte-size preview, then opened CreateDocument. Saving into Downloads produced `TavernForge-edit-test-edited.json` (1,734 bytes), and the app displayed “JSON export completed.” After `adb pull`, Python parsed the original and exported synthetic files with `parse_int=str, parse_float=str`: restoring only the expected `/data/name` change made both trees equal. All other fields, unknown extensions and number spellings were retained. `adb shell run-as ... sha256sum files/project-sources/3c39b7d2825e74cc5e0c97d4bee00eaf77eccbc59e0281e8fa1ee40db92810a2.json` confirms the immutable managed original still matches the fixture. A local screenshot was visually reviewed; it is not committed. The synthetic project and export remain on the AVD for subsequent upgrade checks.

This exercises one system DocumentsUI provider on an emulator; it does not establish every provider, physical durability, accessibility or SillyTavern runtime behavior. Hosted checks are pending for this increment.

## Added coverage

- `DocumentSnapshotTest`: ten methods exercise exact untouched export for all six JSON kinds; edited snapshot/restore/export for every kind; immutable original and unknown numeric/null/boolean data; restored revisions; schema-invalid V1/V3 drafts and explicit repairs; version/kind conversion rejection; invalid/unequal revision-zero snapshots; receiving-engine limits; strict standalone JSON-value parsing and malformed/duplicate/UTF-8/size failures.
- `ProjectEditingRepositoryTest`: thirteen methods exercise actual repository and managed-file code with a fault-injectable metadata index. Cases cover original/current separation and metadata retention, stale/empty edit batches, schema-invalid draft repair, unsupported sources, edited duplicates and shared references, failed file/DB writes, compare-and-set conflicts, ambiguous commits followed by failed reference scans, current-file corruption/missing data, and cancellation before/during the commit section.
- `ProjectEditingDeviceTest`: three methods exercise real Room and app-private files in UUID-named isolated stores. The migration case constructs the frozen version-one DDL and Room identity from the checked-in v1 schema, inserts a valid V1 row and a schema-invalid V3 row, then opens the production v2 database. It checks unchanged IDs, titles, filenames, timestamps, media types, hashes, source/current bytes and draft state, followed by actual edits, strict export, V3 repair, a stale repository edit, the DAO's conditional-update result and close/reopen. Other cases cover edited duplicate/delete shared references and an invalid V1 draft retaining its source kind through reopen and repair.

`FaultIndex` now provides conditional update and before/after update failure hooks. It remains explicitly an in-memory test index, not a Room implementation. The earlier test that treated every positive revision as unsupported now rejects a negative revision; positive saved revisions are the feature being added.

Synthetic JSON is authored in tests or loaded from the existing pinned fixtures. Tests never read user character content, require a Gemini key, or replace a production database. The migration fixture intentionally creates the historical schema directly rather than deriving it from the current entity, so a broken migration cannot pass by starting from an already-upgraded table.

## Execution handoff

The supervisor is the sole Gradle owner. Focused commands from the project root:

```sh
./gradlew :format:test :data:testDebugUnitTest :data:assembleDebugAndroidTest --offline --no-daemon --console plain
```

The full integration also requires the unchanged core suite, the app owner's editor/writer tests, app/data lint, APK build, and fixture audit. The raw device runner should include **both** `ProjectLibraryDeviceTest` and `ProjectEditingDeviceTest`; the six prior library tests and three new tests produce nine expected physical-device methods before any later additions. Installation success alone, Gradle exit code zero, or an empty HTML test report is insufficient evidence.

App-specific pending-edit confirmation, stale picker completion, strict-export gating, failure reporting, and immutable export-byte selection are tested by the app owner. The supervisor used the authorized existing AVD for the CreateDocument workflow because the physical device is disconnected; physical-device upgrade remains unverified for 0.4. Record actual commands, XML/raw counts, failures and final artifact fingerprints here or in current status after execution.

## Review and limits

Source review confirms that the new repository finalizes current bytes before its conditional metadata update and uses both original/current references for pruning. A failed complete reference read prevents cleanup. The migration copies every v1 metadata column and initializes current references from the original; original files are not rewritten. These are source-review observations, not passing runtime evidence.

This suite does not simulate physical power failure, storage-device durability guarantees, all document providers, accessibility or every adaptive size. A schema migration/Room close-reopen test does not establish process-death draft recovery. Actual external SillyTavern interoperability remains a separate validation task. Binary containers, conversion, credential storage and Gemini are outside this increment.
