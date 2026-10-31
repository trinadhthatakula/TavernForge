# Project status

Baseline: [TavernForge kickoff charter](../TavernForge_Project_Kickoff.html), revision 1.0, 31 October 2026.

## Current phase

The JSON compatibility update is implemented for character **V1/V2/V3**, bare V2 CharacterBook, standalone V3 lorebook, and native SillyTavern World Info. The inspector selects the detected format's validation rules and shows its version and lore-entry count. Engine edits and strict exports retain the source kind, unknown data, and number lexemes. The final offline build passes **102 JVM tests** and Android lint; the repository remains private and the toolchain is unchanged. This is an internal JSON inspector, not the full MVP or a certification of SillyTavern interoperability.

[GitHub: trinadhthatakula/TavernForge](https://github.com/trinadhthatakula/TavernForge) was created **private** under the user's explicit authorization. The kickoff and verified product changes are pushed to `main`. Keep the repository private during internal testing. Community publication is a later step.

**Approved UI:** C's character-library identity, A's native controls/navigation, and B's optional inspector on expanded windows. See [the recorded decision](DESIGN_DECISION.md). No further design-selection approval is required for that direction.

## Implemented

- `:core:model`: owned source bytes/SHA-256, resource budgets, diagnostics, projections, minimal project identity.
- `:format`: strict bounded UTF-8/JSON parsing, duplicate-name detection, unknown/null/number preservation, atomic revision-aware JSON Pointer patches, version-dispatched schema checks and strict same-format JSON export for the six supported JSON kinds. Untouched valid exports retain exact source bytes. Unknown/future versions remain inspectable with strict exports blocked. See the [support matrix](build/JSON_FORMAT_SUPPORT.md).
- `:app`: real system-picker JSON intake, bounded streaming, background parsing, validation diagnostics, and compact/expanded inspection UI. App binding uses the Koin compiler plugin. Inspection is in memory; process death requires selecting the file again. Editing/export are engine APIs, not exposed app features yet.
- Root Gradle wrapper, pinned version catalog and dependency locks; [private CI workflow](../.github/workflows/android.yml) with tests, fixture audit, APK build, and seven-day artifacts.

## Agent assignments and results

| Owner | Scope | Result |
| --- | --- | --- |
| Supervisor | Repository/GitHub, decisions, integration and status | Private remote maintained; reviewing JSON update, deployment and hosted CI |
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

Passed: **102 tests, zero failures/errors/skips** (4 model, 81 format, 17 app). The supervisor independently read final JUnit and lint XML; lint reports zero issues. The generated debug APK is `app/build/outputs/apk/debug/app-debug.apk`, package `com.valhalla.tavernforge`, version **0.2.0-internal**, code 2, min SDK 26, target SDK 37. APK size: 13,520,958 bytes; SHA-256: `2ffaa0ebb4410bb0ef93b19acf69368aaa8bb6ba993f27b59932ab7d621a2df3`. The packaged launcher and signature were checked. The exact APK was installed with `adb -s 1da5425f install -r`; the device package manager confirms code 2 / 0.2.0-internal. Cold launch with `am start -W` succeeded on model 25053PC47G, API 36. The home UI hierarchy displays the new JSON scope. SAF import smoke verification is pending because the device locked during interaction.

The previous 0.1.0-internal build passed 64 JVM tests and was installed successfully on the connected API 36 device. Installation alone did not establish SAF or rendering behavior.

Fixture integrity audit passes: eleven pinned files, three helper JSON objects and eight byte-identical JVM resource copies. This audit is not adapter/interoperability testing. Current evidence: [JSON format verification](testing/JSON_FORMAT_VERIFICATION.md), [JSON support matrix](build/JSON_FORMAT_SUPPORT.md). Earlier foundation evidence: [product build](build/PRODUCT_BOOTSTRAP.md), [foundation tests](testing/FOUNDATION_VERIFICATION.md), [format implementation](build/FORMAT_FOUNDATION.md).

The first [CI run](https://github.com/trinadhthatakula/TavernForge/actions/runs/41225026908) stopped before building because `sdkmanager` was not on the runner path. The next setup attempt requested the obsolete SDK `tools` package through the action's defaults. The workflow now explicitly sets up command-line tools and requests only `platform-tools` before installing pinned platform/build-tools packages. The corrected [run 41225752764](https://github.com/trinadhthatakula/TavernForge/actions/runs/41225752764) passed in 5m37s on code/workflow commit `9da318c`. Tests, lint, fixture audit, APK build and both artifact uploads succeeded. Artifacts are retained for seven days. Workflow runtime/image deprecation notices remain non-blocking and do not alter the tested toolchain. The initial isolated dependency probe separately passed three compiler integration tests; these are not included in the 64 product tests.

## Toolchain and constraints

- Requested AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21 remain unchanged. Product compile/target SDK 37.0 and Build Tools 37.0.0 satisfy dependency metadata; API 26 minimum is provisional for internal testing.
- The tested JDK is 27; Java/Kotlin bytecode targets 17. Wrapper and dependency locks are included.
- Kotlin/Koin vendor support-range caveats remain documented despite passing local builds. No version downgrade was inferred or approved.
- Weak/unstable internet caused transient GitHub/Maven failures; cached dependencies and offline verification were used where possible.

## Milestones and remaining work

M0 bootstrap is complete for this internal build: private repository, pinned wrapper/catalog/locks, product modules, local verification and a successful hosted CI build. M1 has the JSON model/parser/patch/validator/export foundation for character V1/V2/V3, V2/V3 lorebooks and native World Info. PNG/APNG, media, conversion review and real upstream interoperability still remain pending. M2 and later product milestones remain incomplete.

Next implementation work: Room 3 persistence and managed sources, repository/domain integration, editing/export UI, full import/asset association and the planned binary containers. Gemini remains optional future work with independent permissions and Keystore-backed credentials; this internal APK has no key flow or network permission. Finish device tests before community readiness claims.

Open decisions include public release timing, license/final public identity/distribution, final minimum SDK policy, cross-version migration scope, and generation-history defaults. Hosting is resolved to the private GitHub repository above.

## Reference documents

[Roadmap](planning/ROADMAP.md) · [architecture](planning/ARCHITECTURE.md) · [dependency evidence](planning/TECH_STACK.md) · [upstream format pins](planning/FORMATS.md) · [test strategy](testing/TEST_STRATEGY.md) · [design comparison](design-comparison.html) · [fixture provenance](../format/fixtures/README.md) · [isolated smoke evidence](build/COMPATIBILITY_SMOKE.md).
