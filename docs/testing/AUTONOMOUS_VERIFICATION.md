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
