# Project status

Baseline: [TavernForge kickoff charter](../TavernForge_Project_Kickoff.html), revision 1.0, 31 October 2026.

## Current phase

The first internal product build is implemented and locally verified: a launchable Android JSON inspector backed by pure Kotlin model and format modules. The final offline build passes all **64 JVM tests**. This is a foundation build, not the full MVP or a certification of SillyTavern interoperability.

[GitHub: trinadhthatakula/TavernForge](https://github.com/trinadhthatakula/TavernForge) was created **private** under the user's explicit authorization. The kickoff checkpoint is pushed to `main`; the verified product changes are being prepared for push. Keep the repository private during internal testing. Community publication is a later step.

**Approved UI:** C's character-library identity, A's native controls/navigation, and B's optional inspector on expanded windows. See [the recorded decision](DESIGN_DECISION.md). No further design-selection approval is required for that direction.

## Implemented

- `:core:model`: owned source bytes/SHA-256, resource budgets, diagnostics, projections, minimal project identity.
- `:format`: strict bounded UTF-8/JSON parsing, duplicate-name detection, unknown/null/number preservation, atomic revision-aware JSON Pointer patches, V2 schema checks and strict JSON export. Untouched valid V2 exports retain exact source bytes. V1/V3/unknown documents are inspectable, with unsupported exports blocked.
- `:app`: real system-picker JSON intake, bounded streaming, background parsing, validation diagnostics, and compact/expanded inspection UI. App binding uses the Koin compiler plugin. Inspection is in memory; process death requires selecting the file again. Editing/export are engine APIs, not exposed app features yet.
- Root Gradle wrapper, pinned version catalog and dependency locks; [private CI workflow](../.github/workflows/android.yml) with tests, fixture audit, APK build, and seven-day artifacts.

## Agent assignments and results

| Owner | Scope | Result |
| --- | --- | --- |
| Supervisor | Repository/GitHub, decisions, integration and status | Private remote created, kickoff checkpoint pushed, source and final test evidence reviewed |
| `product_bootstrap` | Root/module builds, `app/**`, CI | Debug APK, 5 app JVM tests and Android lint passed |
| `foundation_format` | Model/format implementation | Initial bounded JSON/V2 foundation implemented; reviewed and frozen |
| `foundation_test` | Independent regression tests and source/CI review | 59 pure JVM tests passed; preservation, malformed-input, patch and schema cases covered |

Earlier agents completed discovery, three design proposals, pinned upstream research, fixture recovery audits, and the isolated toolchain probe. All agents save their artifacts locally; the power-failure recovery preserved the original charter.

## Verification

Final root command (31 October 2026):

```sh
./gradlew :core:model:test :format:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon --console plain
```

Passed: **64 tests, zero failures/errors/skips** (4 model, 55 format, 5 app). The supervisor independently read the final JUnit XML. Android lint reports no issues. The generated debug APK is `app/build/outputs/apk/debug/app-debug.apk`, package `com.valhalla.tavernforge`, version `0.1.0-internal`, min SDK 26, target SDK 37. The packaged launcher and signature were checked. Device installation/launch, UI rendering, SAF provider behavior, and accessibility remain unverified.

Fixture integrity audit passes: six pinned files and three helper JSON objects. This audit is not adapter/interoperability testing. Full evidence: [product build](build/PRODUCT_BOOTSTRAP.md), [foundation tests](testing/FOUNDATION_VERIFICATION.md), [format implementation](build/FORMAT_FOUNDATION.md).

CI configuration exists; no remote CI pass is claimed until the pushed workflow finishes. The initial isolated dependency probe separately passed three compiler integration tests; these are not included in the 64 product tests.

## Toolchain and constraints

- Requested AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21 remain unchanged. Product compile/target SDK 37.0 and Build Tools 37.0.0 satisfy dependency metadata; API 26 minimum is provisional for internal testing.
- The tested JDK is 27; Java/Kotlin bytecode targets 17. Wrapper and dependency locks are included.
- Kotlin/Koin vendor support-range caveats remain documented despite passing local builds. No version downgrade was inferred or approved.
- Weak/unstable internet caused transient GitHub/Maven failures; cached dependencies and offline verification were used where possible.

## Milestones and remaining work

M0 has a locally verified product scaffold; remote CI evidence is pending. M1 has the initial V2 JSON model/parser/patch/validator/export foundation, with PNG, V3, standalone World Info, conversion review and real upstream interoperability still pending. M2 and later product milestones remain incomplete.

Next implementation work: Room 3 persistence and managed sources, repository/domain integration, editing/export UI, full import/asset association, and the other planned formats. Gemini remains optional future work with independent permissions and Keystore-backed credentials; this internal APK has no key flow or network permission. Finish device tests before community readiness claims.

Open decisions include public release timing, license/final public identity/distribution, final minimum SDK policy, V1 migration scope, and generation-history defaults. Hosting is resolved to the private GitHub repository above.

## Reference documents

[Roadmap](planning/ROADMAP.md) · [architecture](planning/ARCHITECTURE.md) · [dependency evidence](planning/TECH_STACK.md) · [upstream format pins](planning/FORMATS.md) · [test strategy](testing/TEST_STRATEGY.md) · [design comparison](design-comparison.html) · [fixture provenance](../format/fixtures/README.md) · [isolated smoke evidence](build/COMPATIBILITY_SMOKE.md).
