# Project status

Baseline: [TavernForge kickoff charter](../TavernForge_Project_Kickoff.html), revision 1.0, 31 October 2026.

## Current phase

Product scaffold and format foundation implementation are in progress following user authorization. The isolated compatibility probe passed. The user also authorized a private GitHub repository; it must remain private during the first internal test-build phase. Public community distribution is a later step.

**UI decision approved by the user:** C’s character library, A’s native controls and navigation, and B’s optional inspector on expanded windows. The approval was given in the project conversation after reviewing the comparison choices. This clears the design selection gate; dependency validation and format correctness requirements still apply.

## Agent assignments

| Role | Scope and owned paths | Deliverable / acceptance | Status |
| --- | --- | --- | --- |
| Agent 0 — supervisor | Root instructions, repository setup, `docs/STATUS.md`, `docs/design-comparison.html` | Consistent instructions, integrated findings, reviewable design comparison, honest verification status | Discovery integrated; decision and recovery status saved |
| Agent 1 — scope and dependencies (`recover_planning`) | `docs/planning/**` | Task graph and contracts; exact version matrix backed by official evidence; pinned upstream format definitions | Complete for discovery |
| Agent 3 — UI/UX | `docs/design/**` | Three distinct standalone proposals and rationale covering comparable screens, all workflows, accessibility, adaptive layouts | Proposals saved; recommended hybrid approved |
| Agent 4 — tester (`recover_tests`) | `docs/testing/**`, `format/fixtures/**` | Traceable acceptance/test matrix and fixtures with source provenance; planned vs executed checks distinguished | Recovery and final probe artifacts verified; report saved |
| Agent 2 — implementation (`recover_bootstrap`) | `tools/compatibility-smoke/**`, `docs/build/**` | Isolated compile probe for the requested full stack; exact command/result evidence | Final probe passed; no product implementation yet |

The current session permits three concurrent workers plus the supervisor. Roles will be sequenced as discovery tasks finish.

## Current implementation assignments

| Owner | Owned paths | Task / acceptance | State |
| --- | --- | --- | --- |
| Supervisor | Root docs, Git/GitHub integration | Private repository, preserve decisions, integrate verified changes and status | In progress |
| `product_bootstrap` | Root Gradle/catalog/wrapper, module build files, `app/**`, `.github/workflows/**`, product build report | Real debug APK, approved hybrid shell, bounded SAF JSON inspection, CI | In progress |
| `foundation_format` | `core/model/src/main/**`, `format/src/main/**`, format report | Immutable source/raw model, bounded parsing, revisioned patches, strict V2 validation/export | In progress |
| `foundation_test` | Model/format test sources, foundation test report | Independent preservation, malformed-input, patch and validation regression tests; app/CI review | In progress |

Only `product_bootstrap` runs the shared Gradle build while these assignments are active. Source owners resolve compiler/test failures together. The first APK is an internal foundation build, not the full MVP.

## Saved discovery artifacts

- [Roadmap and task graph](planning/ROADMAP.md)
- [Architecture and preservation contracts](planning/ARCHITECTURE.md)
- [Exact dependency matrix and support limits](planning/TECH_STACK.md)
- [Pinned upstream format references and coverage gaps](planning/FORMATS.md)
- [Approved UI decision](DESIGN_DECISION.md), [comparison](design-comparison.html), and [proposal rationale](design/proposals.md)
- [Test strategy](testing/TEST_STRATEGY.md), [fixture corpus](../format/fixtures/README.md), and [recovery verification](testing/RECOVERY_VERIFICATION.md)
- [Compatibility probe and execution evidence](build/COMPATIBILITY_SMOKE.md)

## Pending gates and decisions

1. Requested AGP 9.4.1 / Gradle 9.8.1 / Kotlin 2.4.21 retained and locally tested with Compose/Material 3, Koin compiler DSL, Room 3/KSP, serialization and Coil Compose. Official Kotlin/Koin support-range caveats remain documented. This tested configuration can seed the non-UI foundation; broader runtime/production compatibility is unverified. Any later baseline change still requires approval.
2. UI selection resolved: approved hybrid described above. [Comparison and previews](design-comparison.html) remain available as design references.
3. Pin upstream sources and fixture coverage before deciding advertised character-card/PNG/World Info support; V1 scope remains open.
4. Decide license, hosting/distribution, final public identity, and generation-history retention before the corresponding implementation/release work.

## Environment observed

- Android SDK is available at `~/Library/Android/sdk`.
- Java, Gradle, Git, Python 3, Node, and curl are installed.
- Gradle 9.8.1 appears in the local wrapper cache; this is not a complete compatibility check.

## Verification

The charter was read in full and the initially empty project state was inspected. After recovery:

- `python3 format/fixtures/verify_manifest.py` passed: six pinned files and three helper objects. This is fixture integrity, not application format validation.
- The tester independently fetched all three vendored upstream reference files from their pinned commit URLs; their bytes/hashes matched the local copies.
- The comparison page opened in the available Twilight browser. All three local prototype frames rendered, and changing the comparison selector to Gemini request review updated all three previews. Text, image, and follow-up switches were visibly off in each.
- The comparison page and all three prototypes passed JavaScript syntax (`node --check`), unique HTML ID, and local-link checks. Android accessibility and complete interaction testing remain pending.
- Final isolated probe: `./gradlew :probe:assembleDebug :probe:testDebugUnitTest --no-daemon --console plain` from `tools/compatibility-smoke` passed with Coil Compose included; three tests, no failures/errors/skips. Initial dependency-download and compileSdk 36 attempts failed; the final build uses installed SDK 37.0 and Build Tools 37.0.0, required by selected artifacts. No requested toolchain versions were changed.
- The tester checked final XML results, generated Room schema/DAO/database, compiled AAR contents, and locked compiler/library versions. See [independent smoke review](testing/SMOKE_REVIEW.md) and [build evidence](build/COMPATIBILITY_SMOKE.md) for warnings and exact scope.
- No application feature, SillyTavern adapter, device, credential-storage, provider, or end-to-end interoperability test has passed. Those implementations/tests are still pending.

## Next implementation handoff

The supervisor reviewed the [minimal architecture and preservation contracts](planning/ARCHITECTURE.md): start pure `:core:model` and `:format` modules, preserving source bytes, explicit null/missing/type distinctions, nested unknown values, and source/export version separation. Add Android data/app modules as the shared contracts become executable. Follow `MODEL-01`, `FORMAT-01`, and `FORMAT-02` in the roadmap, using the verified probe as build evidence rather than treating its demonstration entities as product models.

M0 still requires the actual product build/module scaffold and CI. The first format milestone requires bounded parsing, patching, strict validation and attributable positive/negative fixtures. The approved design can guide UI work once those foundations support it.
