# Project status

Baseline: [TavernForge kickoff charter](../TavernForge_Project_Kickoff.html), revision 1.0, 31 October 2026.

## Current phase

The user has authorized autonomous completion of the full planned app, with secrets excluded from Git/logs and progress documented. Existing AVDs are authorized for testing. See the [autonomous execution plan](planning/AUTONOMOUS_EXECUTION.md); the supervisor continues through the remaining increments without waiting for user supervision.

**Saved editing/export implemented and verified locally:** internal version **0.4.0** adds preserved original/current snapshots, revision conflict protection, a non-destructive Room v1→v2 migration, explicit field/value editing and strict same-format JSON export through the system picker. **179 JVM tests**, **nine Room tests on the existing API 36 AVD**, and both Android lints pass. Real picker smoke imported a synthetic V3 fixture, saved a name edit, reopened revision 1 after force-stop, and exported it; independent comparison found only the intended name change and preserved every other value/number lexeme. [Detailed evidence](testing/JSON_EDIT_VERIFICATION.md). Hosted checks for this increment are pending.

**Next implementation underway:** manual templates/lore editing, PNG/artwork/associations, and Gemini credential/provider foundations are being built in an isolated checkout. These are not features of the verified 0.4 APK yet. Scope and acceptance are in [manual authoring](planning/MANUAL_AUTHORING.md), [PNG/combine](planning/PNG_COMBINE.md), and [Gemini security](planning/GEMINI_SECURITY.md).

**Implemented and verified locally and on GitHub:** the [local JSON project library](planning/PROJECT_LIBRARY.md), the first M2 persistence increment. Room 3/managed files, library UI and independent tests are integrated with all six user-approved Dependabot PRs. Version **0.3.0-internal** passes **134 JVM tests**, **six Room tests on the physical device**, and **both Android lints**; the upgraded isolated probe passes its three tests. The APK is installed, and a V3 source saved through the UI reopens after force-stop/restart and removal of its temporary Downloads file. Hosted build, CodeQL, dependency submission/review and APK attestation have passed; details below.

The JSON compatibility update is implemented for character **V1/V2/V3**, bare V2 CharacterBook, standalone V3 lorebook, and native SillyTavern World Info. The inspector selects the detected format's validation rules and shows its version and lore-entry count. Engine edits and strict exports retain the source kind, unknown data, and number lexemes. The final offline build passes **102 JVM tests** and Android lint; the toolchain is unchanged. The user subsequently authorized public repository access and Dependabot. This is an internal JSON inspector, not the full MVP or a certification of SillyTavern interoperability.

[GitHub: trinadhthatakula/TavernForge](https://github.com/trinadhthatakula/TavernForge) was initially private. On 1 November the user explicitly authorized making it **public**, continuing GitHub Actions CI, and adding Dependabot. This supersedes the earlier visibility restriction; see [the decision and automation policy](planning/REPOSITORY_DECISION.md). The user also selected **GPL-3.0-or-later**, copying the license text from sibling Thor; see [LICENSE](../LICENSE) and [notices](../NOTICE.md). Release readiness remains a separate decision. The library, GPL documents, security automation and reviewed dependency upgrades are pushed to `main` in integration commit `b4053db`. SHA-only Actions enforcement is enabled. The verification follow-up [PR #7](https://github.com/trinadhthatakula/TavernForge/pull/7) passed all applicable checks and was merged.

**Approved UI:** C's character-library identity, A's native controls/navigation, and B's optional inspector on expanded windows. See [the recorded decision](DESIGN_DECISION.md). No further design-selection approval is required for that direction.

## Implemented

- `:core:model`: owned source bytes/SHA-256, resource budgets, diagnostics, projections, minimal project identity.
- `:format`: strict bounded UTF-8/JSON parsing, duplicate-name detection, unknown/null/number preservation, atomic revision-aware JSON Pointer patches, version-dispatched schema checks and strict same-format JSON export for the six supported JSON kinds. Untouched valid exports retain exact source bytes. Unknown/future versions remain inspectable with strict exports blocked. See the [support matrix](build/JSON_FORMAT_SUPPORT.md).
- `:data`: Room 3 project metadata, app-private content-addressed original/current JSON, integrity checks, interruption recovery, revision-checked editing and save/reopen/rename/duplicate/delete. The v1→v2 migration preserves existing sources. Schema-invalid edits can be saved as drafts; malformed JSON cannot.
- `:app`: real system-picker JSON intake, bounded streaming, background parsing, validation diagnostics, compact/expanded inspection, and the local library with search/sort and explicit save. App binding uses the Koin compiler plugin. Saved projects persist; unsaved inspection is in memory and requires selecting the file again after process death. Saved JSON field/value editing and strict export are available through native controls and the system picker. Pending field input requires explicit Apply or Discard; source versions stay fixed.
- Root Gradle wrapper, pinned version catalog and dependency locks; [CI workflow](../.github/workflows/android.yml) with tests, fixture audit, APK build, and seven-day artifacts.

## Agent assignments and results

Current ownership in the isolated next-feature checkout: `edit_persistence` owns PNG/combine format sources, assets/links and data persistence; `edit_ui` owns manual-authoring helpers and app UI/history; `edit_tests` owns generation policy, Keystore and Gemini transport. The supervisor owns integration, status, builds, Git/CI and emulator operation. Each assignment has disjoint paths and acceptance criteria; shared contracts are coordinated before changes. Earlier completed assignments follow.

| Owner | Scope | Result |
| --- | --- | --- |
| Supervisor | Repository/GitHub, decisions, integration and status | JSON update pushed to remote; local tests, device install/launch and hosted CI verified |
| `json_versions_engine` | Model/format production sources | Six JSON kinds, format-dispatched validation/export and source-kind guards implemented |
| `json_versions_tests` | Independent contract tests and fixtures | Five synthetic positives with pinned provenance; exact round-trip, edit, negative and numeric-boundary regressions |
| `json_versions_ui` | Android inspector and sole Gradle build owner | Format-aware UI, 0.2.0-internal build and app dispatch tests implemented |
| `product_bootstrap` | Root/module builds, `app/**`, CI | Debug APK, 5 app JVM tests and Android lint passed |
| `foundation_format` | Model/format implementation | Initial bounded JSON/V2 foundation implemented; reviewed and frozen |
| `foundation_test` | Independent regression tests and source/CI review | 59 pure JVM tests passed; preservation, malformed-input, patch and schema cases covered |

Earlier agents completed discovery, three design proposals, pinned upstream research, fixture recovery audits, and the isolated toolchain probe. All agents save their artifacts locally; the power-failure recovery preserved the original charter.

## Verification

Final JSON update command (31 October 2026):

```sh
./gradlew :core:model:test :format:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon --console plain
```

Passed: **102 tests, zero failures/errors/skips** (4 model, 81 format, 17 app). The supervisor independently read final JUnit and lint XML; lint reports zero issues. The generated debug APK is `app/build/outputs/apk/debug/app-debug.apk`, package `com.valhalla.tavernforge`, version **0.2.0-internal**, code 2, min SDK 26, target SDK 37. APK size: 13,521,062 bytes; SHA-256: `2e134bc28b3e2c61fd1973e54445f3758eaa6472dbbe4e00cb113e7b22ecc9a0`. The packaged launcher and signature were checked. The exact APK was installed with `adb -s 1da5425f install -r`; the device package manager confirms code 2 / 0.2.0-internal. Cold launch with `am start -W` succeeded on model 25053PC47G, API 36. The home UI hierarchy displays the new JSON scope. After the user unlocked the phone and selected a JSON file from Downloads, the supervisor inspected the Android UI hierarchy: it identified Character Card V3, declared version 3.0, and a schema diagnostic at `/data/group_only_greetings`. This confirms that selected file reached the inspector through SAF; it is not a full provider, accessibility, or lifecycle test.

The previous 0.1.0-internal build passed 64 JVM tests and was installed successfully on the connected API 36 device. Installation alone did not establish SAF or rendering behavior.

Fixture integrity audit passes: eleven pinned files, three helper JSON objects and eight byte-identical JVM resource copies. This audit is not adapter/interoperability testing. Current evidence: [JSON format verification](testing/JSON_FORMAT_VERIFICATION.md), [JSON support matrix](build/JSON_FORMAT_SUPPORT.md). Earlier foundation evidence: [product build](build/PRODUCT_BOOTSTRAP.md), [foundation tests](testing/FOUNDATION_VERIFICATION.md), [format implementation](build/FORMAT_FOUNDATION.md).

The first [CI run](https://github.com/trinadhthatakula/TavernForge/actions/runs/41225026908) stopped before building because `sdkmanager` was not on the runner path. The next setup attempt requested the obsolete SDK `tools` package through the action's defaults. The workflow now explicitly sets up command-line tools and requests only `platform-tools` before installing pinned platform/build-tools packages. The corrected [run 41225752764](https://github.com/trinadhthatakula/TavernForge/actions/runs/41225752764) passed in 5m37s on code/workflow commit `9da318c`. Tests, lint, fixture audit, APK build and both artifact uploads succeeded. Artifacts are retained for seven days. Workflow runtime/image deprecation notices remain non-blocking and do not alter the tested toolchain. The initial isolated dependency probe separately passed three compiler integration tests; these are not included in either the original 64 or current 102 product-test totals.

The [JSON update CI run 41229724561](https://github.com/trinadhthatakula/TavernForge/actions/runs/41229724561) passed on commit `5866cde`, including tests, fixture audit, lint, APK build and artifact uploads. The user then reported that the missing V3 group-greetings message was unclear; a diagnostic follow-up explains that the array is required but may be empty `[]`. Validation continues to preserve the input without inserting defaults. The follow-up offline run passed 81 format and 17 app tests (98 executed), clean lint and APK build; the four unchanged model tests passed in the preceding full run. The APK fingerprint above is the diagnostic follow-up artifact. [CI run 41230221340](https://github.com/trinadhthatakula/TavernForge/actions/runs/41230221340) also passed on pushed commit `0a79454`, completing hosted verification of the 0.2 update.

The user subsequently requested all applicable free GitHub security features and GPL-3.0-or-later licensing. [Security configuration](planning/GITHUB_SECURITY.md) and [LICENSE](../LICENSE) record these decisions. Dependabot PRs **#1–#6 are all merged**: Actions upgrades, AGP 9.5.0 in product/probe, Gradle 9.8.2 in both wrappers, and Material 3 beta02 in both catalogs. The user authorized these changes to the original baseline. Fresh checks passed before merges #3–#6; the supervisor also reviewed the already-merged #1/#2 and reconciled the local library with them.

The final library integration command regenerated locks and passed in 2m9s; locked offline verification passed in 27s. [Detailed commands and evidence](testing/PROJECT_LIBRARY_VERIFICATION.md) record 4 model + 81 format + 21 data + 28 app tests, zero failures/errors/skips and zero lint issues. The independent probe passed three tests in 16s. The updated raw physical-device runner reports `OK (6 tests)` in 0.912s. The installed debug APK is 14,690,582 bytes, SHA-256 `cf06958da125e45be93a2b804c3d0fa2206c2373f0157d7131c998e65b238eb8`; package manager confirms code 3 / 0.3.0-internal. Signature and manifest checks pass.

Device UI smoke: the supervisor copied the synthetic `spec-v3-rich.json` to a uniquely named Downloads test file, selected it with the real system picker, observed `Archive Keeper` / `Structure passes`, and tapped `Save draft to library`. After `am force-stop`, deletion of only that temporary fixture, and cold relaunch, the library showed one saved project. `Open project` restored the inspector with `Saved on this device` and the same character. The managed file's SHA-256 matches the 2,119-byte fixture (`3c39b7d2825e74cc5e0c97d4bee00eaf77eccbc59e0281e8fa1ee40db92810a2`). The test project remains in the library. This proves the exercised save/restart/reopen path; full accessibility, all provider variants, adaptive sizes and physical power-loss behavior remain unverified.

Hosted integration evidence on `b4053db`: [Android build and attestation](https://github.com/trinadhthatakula/TavernForge/actions/runs/41235996144), [CodeQL](https://github.com/trinadhthatakula/TavernForge/actions/runs/41235996169) and [both dependency graph submissions](https://github.com/trinadhthatakula/TavernForge/actions/runs/41235996175) passed. Java/Kotlin, Actions and Python analyses report zero results and no analysis errors. The downloaded CI APK's provenance was verified with `gh attestation verify /tmp/tavernforge-ci-b4053db/app-debug.apk --repo trinadhthatakula/TavernForge --format json`: its signed statement binds SHA-256 `076016d9f1919f8d41d719fe86ea33d23453439b5d2234d2e915753286c5b7bf` to the main-branch workflow and commit `b4053db`. This hosted debug APK differs from the locally signed device APK; reproducible release signing is not claimed.

[PR #7](https://github.com/trinadhthatakula/TavernForge/pull/7) verified the complete PR path: [read-only generation](https://github.com/trinadhthatakula/TavernForge/actions/runs/41236087248), [trusted validation/submission](https://github.com/trinadhthatakula/TavernForge/actions/runs/41236244185), [dependency review](https://github.com/trinadhthatakula/TavernForge/actions/runs/41236087006), [Android CI](https://github.com/trinadhthatakula/TavernForge/actions/runs/41236087063), and [CodeQL](https://github.com/trinadhthatakula/TavernForge/actions/runs/41236087068) all passed on its reviewed head `60b42cc`. The Python safeguards have 15 passing focused tests. Actual external-fork execution remains unverified; the test PR used a branch in this repository. No Dependabot or CodeQL alerts were open at the final API check.

## Toolchain and constraints

- The user-approved Dependabot batch supersedes the original AGP/Gradle/Material pins: AGP **9.5.0**, Gradle **9.8.2**, Material 3 **1.5.0-beta02**, resolved Compose **1.12.1**. Kotlin remains **2.4.21**, Room **3.0.3**, KSP **2.3.13**, Koin runtime/compiler **4.2.2/1.2.1**, and Compose BOM **2026.09.00**. Product compile/target SDK 37.0 and Build Tools 37.0.0 satisfy dependency metadata; API 26 minimum is provisional for internal testing.
- The tested JDK is 27; Java/Kotlin bytecode targets 17. Wrapper and dependency locks are included.
- Kotlin/Koin vendor support-range caveats remain documented despite passing local builds. No version downgrade was inferred or approved.
- Weak/unstable internet caused transient GitHub/Maven failures; cached dependencies and offline verification were used where possible.

## Milestones and remaining work

M0 bootstrap is complete for this internal build: repository, pinned wrapper/catalog/locks, product modules, local verification and a successful hosted CI build. M1 has the JSON model/parser/patch/validator/export foundation for character V1/V2/V3, V2/V3 lorebooks and native World Info. PNG/APNG, media, conversion review and real upstream interoperability still remain pending. M2 and later product milestones remain incomplete.

Next implementation work: complete manual authoring, PNG/artwork association, optional Gemini and lifecycle/adaptive hardening. The verified 0.4 APK has no key flow or network permission. The separate next-feature branch must pass independent security, preservation and emulator checks before integration. Finish broader device and interoperability tests before community readiness claims.

Open decisions include public release timing, final public identity/distribution, final minimum SDK policy, cross-version migration scope, and generation-history defaults. Hosting is resolved to the public GitHub repository above.

## Reference documents

[Roadmap](planning/ROADMAP.md) · [architecture](planning/ARCHITECTURE.md) · [dependency evidence](planning/TECH_STACK.md) · [upstream format pins](planning/FORMATS.md) · [test strategy](testing/TEST_STRATEGY.md) · [design comparison](design-comparison.html) · [fixture provenance](../format/fixtures/README.md) · [isolated smoke evidence](build/COMPATIBILITY_SMOKE.md).
