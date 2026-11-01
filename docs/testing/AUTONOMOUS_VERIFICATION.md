# Autonomous app integration evidence

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). Scope: [execution plan](../planning/AUTONOMOUS_EXECUTION.md). [Current status](../STATUS.md) distinguishes shipped integration from these developing checkpoints.

## Completed 0.4 integration

[PR #8](https://github.com/trinadhthatakula/TavernForge/pull/8) merged as `e4dbfcc` after Android CI, all three CodeQL languages, dependency review and both dependency snapshots passed. Main [Android run](https://github.com/trinadhthatakula/TavernForge/actions/runs/41242045365), [CodeQL](https://github.com/trinadhthatakula/TavernForge/actions/runs/41242045199) and [dependency submission](https://github.com/trinadhthatakula/TavernForge/actions/runs/41242045170) also passed. Local 179 JVM/9 AVD and real edit/export evidence is in [JSON edit verification](JSON_EDIT_VERIFICATION.md).

## Developing 0.5 checkpoints, 1 November 2026

The supervisor runs builds, emulator and Git operations. Production owners work only in the isolated `codex/authoring-assets-ai` checkout; the stable main checkout is retained. The existing `FrameGallery_API36_1` / `emulator-5554`, API 36, is used without wiping unrelated data. Physical-device coverage is not inferred from these checks.

Full integration command (with `ANDROID_HOME` pointing to the locally installed SDK):

```sh
./gradlew :core:model:test :format:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :data:assembleDebugAndroidTest :data:lintDebug :app:lintDebug --write-locks --offline --no-daemon --console plain
```

Initial builds exposed a test fixture's unsupported CHARX asset reference and the new `IN_USE` storage error missing from UI mapping. Both were fixed before the passing checkpoints. A fixture with unresolved embedded asset references remains a negative PNG-export case, not silently stripped production data.

| Checkpoint | Actual result | Limits |
| --- | --- | --- |
| First manual/PNG/provider integration | 244 JVM tests: 12 model, 121 format, 51 data, 60 app; zero failures/errors/skips. Build 34s. Data lint clean, app one unused-string warning. | Subsequent source/test additions require fresh verification. |
| Room v3 AVD | Raw package runner `OK (12 tests)`, 0.406s, code -1. | Includes frozen v2 migration, PNG source/provenance and embedding; not physical power-loss simulation. |
| Second composition/security/journal checkpoint | 278 JVM: 12 model, 124 format, 77 data, 65 app; zero failures/errors/skips. Build 39s. | Lint only platform ExifInterface and unused-string warnings; AndroidX replacement follows. |
| Room/Keystore AVD | Raw package runner `OK (19 tests)`, 0.584s, code -1. | Includes five credential/image checks and two restart-phase methods; final v4-specific tests follow. |

The initial 0.5 APK/code 5 installed successfully: 16,662,390 bytes, SHA-256 `b26b1e44e6b0a303f391a40b9b5c2fd1b209aaecea98469fd9e1d2c6a7f8ada4`. Cold launch succeeded in 1044ms. This is an intermediate artifact fingerprint, not the eventual final APK.

## Manual workflow exercised on the AVD

Create manually → select Character Card V3 → local name `Manual AVD V3` → inspect creation preview → Create draft opened the shared editor at revision 0. Add embedded lorebook followed by Add entry produced two pending commands and one entry. Undo removed only the entry; Redo restored it. Save produced revision 1 with valid structure.

Export saved JSON → immutable revision preview → CreateDocument → Downloads produced `Manual AVD V3-edited.json` (469 bytes). Independent Python parsing of the pulled file verified the exact V3 discriminator/version, empty required group greetings, and one embedded V3 entry with `use_regex:false` and `insertion_order:100`. Empty creative text remains valid; no artwork or timestamps were fabricated. This check covers the exercised empty-template/lore workflow, not every specialized control or accessibility mode.

## PNG reference-reader verification

```sh
./gradlew :format:test --tests '*PngReferenceArtifactsTest' --offline --no-daemon --console plain
node format/fixtures/reference/png/check.mjs format/build/png-reference
python3 format/fixtures/verify_manifest.py
```

Both supervisor and owner observed the Node harness pass for six actual engine outputs (V1/V2/V3 no-op and edit), using Node 26.13.0 and the exact pinned SillyTavern reader at `06bde939fb1e9c4c8d8641d810f0a916b5bce127`. Assertions cover expected JSON bytes, independent CRC/chunk checks, correct metadata keyword, non-card byte preservation and exact no-op. The fixture audit passes 15 pinned files plus three materialized helpers. This is reader interoperability; it does not claim a complete SillyTavern application workflow, APNG editing or CHARX asset resolution. The CI workflow now includes the reference check using pinned Node and a SHA-pinned setup action.

## Credential process restart

After the 19-test package run, the supervisor separately ran `GeminiCredentialRestartDeviceTest#phase1StoreSyntheticCredential`, force-stopped only the isolated test package, then ran `#phase2VerifyAndRemoveSyntheticCredential`. Both separate raw invocations reported `OK (1 test)` and code -1. This establishes the tested synthetic Android Keystore envelope surviving a process restart and being removed afterward. No real API key or Google request was used. It does not establish hardware-backed isolation, cloud key revocation or billing access.

## Latest verified source checkpoint

The next full locked run passed in 37s: **293 JVM tests** (12 model, 124 format, 84 data, 73 app), zero failures/errors/skips and zero app/data lint issues. This includes the HTTP single-send guard, draft journal, JPEG/local metadata tests and eight Gemini ViewModel mock tests. An intermediate generation-test failure came from real background response work outliving the test scheduler; the test now waits for actual completion. First-page model refresh also now replaces stale model objects. The actual AVD runner reports **`OK (22 tests)`**, 0.654s, code -1, including dedicated frozen v3→v4 migration, original JPEG/EXIF retention, reviewed rotation/metadata omission, and duplicated local tags with revision checks.

Current built APK/code 5: 17,533,878 bytes, SHA-256 `be4dd5e8693072b890b2b8f8856e4432c115a9af3df5ef24272e165473ebdaee`. The latest Gemini UI is built and mock-tested, but its real UI smoke remains pending. No real provider call was made.

Real PNG picker smoke on the preceding composition APK imported the pinned upstream-writer V2 fixture (1,118 bytes), displayed both `chara` and invalid preferred `ccv3` candidates, accepted an explicit legacy choice, and saved original PNG/extracted JSON/provenance together. A reviewed CreateDocument export produced 701 bytes with exactly one `chara` chunk. Independent Python CRC/chunk/base64 checks found the original V2 JSON and byte-identical unrelated complete chunks. The original dual-metadata image remains saved; no source conversion or implicit fallback occurred. The human readability of the metadata-replacement review is being improved.

## Remaining integration evidence

Final JPEG/tag migration and conversion tests; single-send HTTP replay guard; backup/restore; Gemini/settings/draft-acceptance UI; recovery after process death; narrow/expanded/rotation/large-font checks; real PNG picker workflow; final full locked regression/CI/security scan and artifact fingerprint. Real-key provider calls remain separate from deterministic synthetic testing and will not be fabricated.

## Durable-worktree recovery checkpoint

The temporary feature checkout was cleared during the interruption. Pushed checkpoint `37b7175` and stable main survived. Continued work now uses a permanent sibling worktree; recovery work is committed incrementally. Historical build12 had 326 passing JVM tests and two app lint warnings, but its files/results were lost and do not verify the reconstructed tree.

The reconstructed archive production plus repository bridge compiled and passed the existing suite in 50s. The subsequent full locked command (same task list above without `--write-locks`) passed in 39s: **313 JVM tests** (13 model, 128 format, 93 data, 79 app), no failures/errors/skips, and **zero issues in both lint reports**. This includes recovered six repository tests and six editing-recovery tests, password masking, four PNG output-budget regressions, and four policy/service preview-disposal regressions. The remaining 21 archive codec/service and four Room test methods are being restored separately.

Independent review found that abandoned generation/count previews accumulated in a bounded service map, and counting could bypass that map's limit. Explicit disposal now releases entries and permanently invalidates old approvals; UI cancellation, count replacement, key/grant changes and execution cleanup call it. A 300-cycle mock test proves prepare/count/discard stays available without a generation call. Counts share the 256 outstanding-request bound. PNG export now respects the smaller of input and output budgets so the app cannot successfully export a card it rejects as oversized on reimport; source/storage limits were not increased.

Before interruption, the AVD's real password field exposed only bullets after the fix. A deliberately invalid synthetic credential was stored, survived force-stop/reopen and was deleted; all grants stayed off. No provider request was made. A manual edit's unfinished text survived force-stop/recovery; applying it and force-stopping again restored one undoable pending change. Recovered input's incorrect previous-value caption was found and corrected in the reconstructed source. These older UI observations will be rerun against the final reconstructed APK.

## Final reconstructed integration and AVD workflows

Full locked build7 passed in 42s with **347 JVM tests**, zero failures/errors/skips and clean app/data lint. A final independent review found an accepted CreateDocument callback could be silently ignored after ViewModel loss, leaving an empty destination. The fix reports an interrupted export without writing or repeating callbacks. Its focused app suite/build/lint passed in 27s, bringing the final total to **348 JVM tests** (13 model, 128 format, 114 data, 93 app). AndroidX ExifInterface 1.4.2 is now also an explicit app dependency for orientation-correct JPEG previews; regenerated locks change only that compile-classpath membership.

The rebuilt isolated test APK reported **`OK (26 tests)`**, 0.985s, code -1. This includes all four restored archive Room tests: rich PNG/JPEG/source provenance and fresh-ID reopen/repeated restore, late metadata ABORT rollback, semantic/hash preflight rejection and cancellation cleanup preserving existing shared files. An earlier run of the old test APK reported 22 tests and is not counted as archive evidence.

Actual backup picker: CreateDocument wrote `TavernForge-library.zip`, 10,385 bytes, containing three synthetic projects and one original PNG. Independent Python ZipFile CRC, STORED-method and SHA-256 checks passed. Archive SHA-256: `007a266bb3601b190e777262b33c5cb5090c3bef4fdc516ce5bc41c580ac2778`. OpenDocument showed the verified counts, explicit acceptance added three new projects/one image, and the library displayed six projects after force-stop/cold launch. No key or request state was included.

Actual JPEG picker: a synthetic 64×96 two-color JPEG with EXIF orientation6 displayed rotated correctly. Save retained its exact770 bytes. Review stated rotate90 clockwise, 96×64 output and metadata omission; acceptance saved a separate282-byte PNG. Independent reads of only these synthetic managed assets confirmed original-byte equality, derived hash,96×64 dimensions, expected left/right pixel colors and absent EXIF. The prior character artwork stayed selected.

Actual tags/layout: adding `AVD-check` to one project created one visible local tag without changing its JSON revision0. Tapping it filtered six projects to one. Compact1080×2400 at420dpi, font-scale2.0, expanded1600×1200 at160dpi, and landscape rotation were inspected through the UI hierarchy and screenshots. Expanded layout showed the three-column library with supporting inspector. Text/buttons wrapped at200%; scrollable content stayed reachable. Keyboard input and dismissal worked in the tag dialog. Original size/density, font1.0, rotation0 and automatic rotation1 were restored afterward. This is bounded visual smoke, not a full TalkBack/IME/foldable certification.

Fixture audit:15 pinned files and3 helpers pass. PNG reader oracle:6 outputs pass at the exact pinned revision on Node26.13.0. The15 Python dependency-graph safeguard tests and actionlint pass. No real provider key or Google call was used.

## Build-tool dependency security mitigation

The final GitHub API check discovered 18 open dependency alerts (nine repeated across product/probe), with zero CodeQL and secret alerts. They were traced to AGP/lint/Room processor transitive dependencies, not ignored as build-only findings. The [shared settings policy](../../gradle/dependency-security.settings.gradle.kts) pins eight patched libraries; [exact versions and origins](../planning/DEPENDENCY_SECURITY.md).

The complete product build with regenerated locks passed in 78s:348 JVM tests, clean data/app lint, app and instrumentation APKs. The isolated probe passed all3 tests, assemble and lint in 21s. Its buildEnvironment and a separate offline product buildEnvironment/debugRuntimeClasspath run confirm Guava33.7.2-jre, aligned Bouncy Castle1.85, commons-lang3 3.18.0, HttpClient4.5.14, jose4j0.9.6 and JDOM2.0.6.1. All observed occurrences resolve to those exact patches. None of these eight coordinates occurs in the actual application runtime graph. AGP/Kotlin/Gradle baseline versions are unchanged. Default-branch dependency alerts await the corrected post-merge snapshots; no alerts were dismissed.

## Installed physical-device test build

After the security rebuild, the isolated AVD runner again reported **OK (26 tests)** in 1.108s, code -1. The final locally signed debug APK is **17,072,054 bytes**, SHA-256 **`f8cdf70b8e9a111981d2810fdc14fb82223ff148ec7d67c3038e17f30282e2f4`**. `apksigner verify --verbose` confirms the v2 APK signature. This is a local debug-signing artifact, not a production release-signing claim.

The user explicitly requested installation on the reconnected physical phone. `adb -s 1da5425f install -r app/build/outputs/apk/debug/app-debug.apk` returned Success without clearing app data. Package manager confirms versionCode 5, versionName 0.5.0-internal, min SDK 26 and target SDK 37. `am start -W -n com.valhalla.tavernforge/.MainActivity` completed cold launch successfully in 1,014ms. Private user projects and credential contents were not inspected. The installed version is ready for the user's testing; these installation checks do not claim full physical-device workflow coverage.
