# Project library verification

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). Scope: the first Room 3 project library and managed JSON sources. This report extends the [test strategy](TEST_STRATEGY.md), especially DB-01, DB-02, DB-04 and JSON preservation cases RT-01–05. The [architecture contract](../planning/ARCHITECTURE.md) governs persistence and recovery.

## Current state

**Updated-stack verification, 1 November 2026:** after merging the user-approved Dependabot batch, the supervisor regenerated product dependency locks with AGP 9.5.0, Gradle 9.8.2 and Material 3 beta02 / Compose 1.12.1. The full product build executed 142 tasks and passed in 2m9s; a subsequent locked offline verification passed in 27s. JUnit XML again contains **134 tests, zero failures/errors/skips** (4 model, 81 format, 21 data, 28 app), and both Android lint XML files contain zero issues. The isolated upgraded probe separately passed its three tests in 16s. Fixture audit and `actionlint` also pass.

Commands from the project root (SDK set through `ANDROID_HOME`):

```sh
./gradlew :core:model:test :format:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :data:assembleDebugAndroidTest :data:lintDebug :app:lintDebug --write-locks --no-daemon --console plain -Dorg.gradle.internal.http.connectionTimeout=15000 -Dorg.gradle.internal.http.socketTimeout=30000
./gradlew :core:model:test :format:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :data:lintDebug :app:lintDebug --offline --no-daemon --console plain
./gradlew -p tools/compatibility-smoke :probe:testDebugUnitTest :probe:assembleDebug --offline --no-daemon --console plain
```

The upgraded isolated test APK was installed with `adb install -r -t`, then the same explicit runner command below again reported all six completion codes 0, **`OK (6 tests)`**, time **0.912s**, `INSTRUMENTATION_CODE: -1`. Main app installation also returned `Success`; package code 3 / `0.3.0-internal`, APK v2 signature and absence of Internet/broad-storage permissions were rechecked. Cold launch reported `Status: ok`, 1072ms. App UI restart evidence is recorded separately in [current status](../STATUS.md).

| Updated artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 14,690,582 | `cf06958da125e45be93a2b804c3d0fa2206c2373f0157d7131c998e65b238eb8` |
| `data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk` | 2,878,813 | `616be1e31dfb80e6145355f199c0a917ad963922d987a74873f69285d8b5dc0e` |

The earlier checkpoint evidence below remains for traceability; its APK hashes are superseded by the table above.

**Pre-dependency-update checkpoint, 1 November 2026:** the tester independently inspected JUnit XML showing **134 JVM tests pass with zero failures, errors or skips**, including all 21 new persistence methods. Data and app lint reports each contain zero issues. The supervisor subsequently ran all **six Room instrumented tests successfully on the connected physical device**. These device checks establish the database/file operations and close/reopen cases below, not application process-death or full UI workflow coverage. Later dependency updates require a fresh integrated verification run; these results and fingerprints describe the checkpoint before those updates.

## Coverage and boundaries

| Layer | Acceptance evidence |
| --- | --- |
| Repository orchestration on JVM | Save/reload/rename/duplicate/delete use the actual repository with a fault-injectable metadata index and real managed files. Byte/hash/version preservation, schema-invalid and unknown drafts, parse rejection, shared-source safety, explicit failure and cancellation semantics are checked independently of Room. |
| Managed files on JVM | Source bytes are bounded and hash-checked; temp/orphan recovery retains referenced files; malformed keys, missing/corrupt sources and failures produce actionable errors. These tests exercise the actual disk implementation in temporary directories. |
| Room on physical Android | Open the production schema, save/reopen/rename/duplicate/delete with real Room and app-private files, then close and recreate the database/repository to verify disk persistence. Android tests use isolated database/file names and never clear the user's app library. |
| Supervisor device checks | Install and launch the built app, save through the real UI, force-stop/restart and reopen if the screen/device permits. These checks are separate from JVM tests and database close/reopen instrumentation. |

`RoomProjectRepositoryTest` has 15 JVM methods against the real repository orchestration, an explicitly fake metadata index, and the real disk store wrapped for injected failures. `ManagedJsonFilesTest` has six methods against actual temporary-directory operations, including same-length corruption, size boundaries, symlink/traversal rejection and selective recovery. `ProjectLibraryDeviceTest` has six methods using the production Room database factory and repository. Each Android method creates a UUID-named test database and source directory and removes only those isolated resources afterward.

Synthetic JSON is authored inside these tests and contains deliberate unknown/null/boolean/numeric values and original number spellings. Both suites explicitly preserve a V3 draft missing `group_only_greetings` and an unknown future format; save/reopen does not insert defaults. This is original test data, not captured user content or a claimed upstream export.

Review found a source media-type bound originally implemented by truncation. It now rejects overlong input explicitly. The repository suite verifies 255-character metadata survives unchanged and 256 characters are rejected before a saved row is created.

The generated Room v1 schema was inspected independently: the `projects` table contains source references and metadata in TEXT/INTEGER columns, with `id` as the primary key. It contains no JSON payload or image BLOB. This schema inspection is separate from running the database on a device.

An initial connected-test artifact reported exit code 0 but its HTML contained **zero tests** and an N/A success rate. The supervisor inspected raw output and found `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. That result is not accepted as device verification. After the user allowed installation, the supervisor successfully installed the isolated test APK and used the explicit class-filtered runner command below, which reported **`OK (6 tests)`**. The stale zero-test Gradle HTML does not represent this later raw runner execution.

The metadata-index seam does not assert Room transaction correctness. JVM fault injection verifies repository decisions under failures, including an insert that commits before reporting failure and a failed metadata requery; neither may cause deletion of potentially referenced source bytes. A failed full-index read blocks recovery; a cleanup-only failure retains orphan files without blocking healthy reads/deletes. The agreed cancellation policy permits an operation already inside its non-cancellable commit section to finish consistently; cancellation before that section must not save a project.

Instrumented dependencies use the already cached AndroidX test runner/core 1.7.0 and ext JUnit 1.3.0. Room remains on the verified `androidx.room3` 3.0.3 line. Build ownership transferred from the UI agent to the supervisor for final verification and device installation; the tester runs no competing Gradle task.

## Local artifact evidence

The supervisor's full offline command completed in 39 seconds:

```sh
./gradlew :core:model:test :format:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :data:lintDebug :app:lintDebug :data:connectedDebugAndroidTest --offline --no-daemon --console plain
```

The connected-task false success described above occurred within that command. After removing three unused app strings, the supervisor ran `./gradlew :app:assembleDebug :app:lintDebug --offline --no-daemon --console plain`, which passed in 15 seconds. No JVM test code or production persistence behavior changed in that string cleanup.

The tester parsed `TEST-*.xml` under each module's `build/test-results` directory: 4 model, 81 format, 21 data and 28 app methods, all with zero failures/errors/skips. `data/build/reports/lint-results-debug.xml` and `app/build/reports/lint-results-debug.xml` each contain zero issues.

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `app/build/outputs/apk/debug/app-debug.apk` | 14,790,204 | `30a4e72bf56c717fde447c3369ddf028f20bbcc482982c17992bc826f5ece2eb` |
| `data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk` | 2,879,165 | `8921cf1b3ef35b30310c89be6eae1f1a46e5f269f99d7cc0f5e9158f48603556` |
| `data/schemas/com.valhalla.tavernforge.data.ProjectDatabase/1.json` | 3,320 | `4c6526024bba5daaa93a8546117efadaba6d20e57594807a2e22cd7ce5b91a9a` |

The main APK metadata identifies `com.valhalla.tavernforge`, code 3 / `0.3.0-internal`. Independent `aapt2 dump permissions` lists only its app-scoped receiver permission, with no Internet or broad-storage permission. `apksigner verify --verbose` verifies APK Signature Scheme v2 with one signer; its JDK native-access warning is non-fatal. These are debug-artifact checks, not release-signing or runtime-persistence evidence.

## Physical-device Room evidence

After installation was allowed, the supervisor executed:

```sh
adb -s 1da5425f install -r -t data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk
adb -s 1da5425f shell am instrument -w -r -e class com.valhalla.tavernforge.data.ProjectLibraryDeviceTest com.valhalla.tavernforge.data.test/androidx.test.runner.AndroidJUnitRunner
```

Installation returned `Success`. On physical model 25053PC47G, API 36, the raw runner reported `numtests=6`, all six test completion statuses 0, `OK (6 tests)`, elapsed time 0.648 seconds, and `INSTRUMENTATION_CODE: -1`. The supervisor observed that output; the tester did not independently operate the device.

The six passing cases exercise the actual Room database and managed app-private files: exact source/hash/metadata through database close/reopen; title-only rename; duplicate/delete shared-source safety; unchanged missing-group V3 and unknown-format drafts; malformed JSON rejection; simulated interrupted-write recovery; missing-source diagnostics without row removal or duplicate creation; and primary-key conflict rejection. Test namespaces were isolated from the user's main library. Database close/reopen is not a force-stop/process-death test, and simulated leftover files are not a physical power-loss test.

## Limits

This initial library does not establish schema upgrade migrations, backup/restore, binary artwork handling, shared lorebook editing, trash/undo, DataStore settings, credentials, or Gemini. No migration from an earlier production Room schema exists yet. A clean JVM suite cannot prove Android database I/O, process death, document-provider access, accessibility, or live SillyTavern interoperability.
