# Product bootstrap and first internal APK

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). This follows the [approved hybrid](../DESIGN_DECISION.md) and the [verified dependency probe](COMPATIBILITY_SMOKE.md). Evidence collected 31 October 2026.

## Result

The root product build now contains pure JVM `:core:model` and `:format` modules plus the Android `:app`. The internal APK builds, all **64 JVM tests pass**, and Android lint reports **no issues**. This is a working JSON inspection foundation, not a complete library/authoring application or a certified SillyTavern format-support release.

Final command from the repository root:

```sh
./gradlew :core:model:test :format:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon --console plain
```

Exit **0**, `BUILD SUCCESSFUL in 18s`, 60 actionable tasks (31 executed, 29 up-to-date). Tests: `:core:model` 4, `:format` 55, `:app` 5; zero failures, errors, or skips. Lint: `No issues found.` The local execution log is `build/product-verified.log`; XML test and lint reports are under each module's ignored `build/` directory. An earlier resolving build wrote dependency locks with `--write-locks`; ordinary builds should retain those locks.

## APK

- Path: `app/build/outputs/apk/debug/app-debug.apk`.
- Size: **14,040,904 bytes**.
- SHA-256: `56338290a727124dcf3dddccf5b08dca7048bcfd7e3259c020db8594d95c1305`.
- Package `com.valhalla.tavernforge`; version code 1, version `0.1.0-internal`; launchable `MainActivity`.
- Debug signature verified with SDK Build Tools 37.0.0 `apksigner verify` (exit 0). This is an internal debug build, not a release-signed distribution.
- Packaged manifest declares no Internet or storage permissions. Its only uses-permission is the AndroidX-added application-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- Backup and device transfer are disabled using legacy and Android 12+ rules. No user project persistence or credential store exists yet.

No emulator or physical device was installed or launched during this assignment. Startup, system document picker behavior, screen-reader/focus behavior, compact/expanded rendering, rotation, and process recreation remain device checks.

## Implemented inspection flow

The warm workshop shell and native Material controls follow the selected hybrid. Navigation 3's Material supporting-pane strategy adapts the inspector to available window space and fold information. The app uses MVVM with StateFlow and lifecycle-aware collection.

The system document picker supplies a JSON URI. Reading occurs on the IO dispatcher, using actual streamed byte counts with a 4 MiB limit, optional display-name metadata, resource closure, and cooperative cancellation. Parsing/validation happens on the Default dispatcher through the shared `CharacterCardEngine`. The screen displays file identity, detected format, checksum, V2 validation results, and explicit scope limits. Character names and displayed diagnostic paths are bounded for UI rendering; the original source is never written or modified. A new selection cancels the old inspection. No long-lived URI grant or file copy is saved.

Rotation retains the ViewModel's current inspection; process death does not persist it. A restored inspector route with no state explains that a file must be opened again. The UI states that results stay in memory. Library saving, editor/export controls, PNG handling, standalone lorebook workflows, and Gemini are deliberately not exposed as implemented features.

The five app JVM checks exercise the real Koin compiler-DSL binding, shared engine construction, exact-limit input, empty input, oversized input despite misleading `available()`, and progress after zero-length bulk reads (the last four are streaming checks). They do not emulate Android document providers or render Compose.

## Toolchain and dependencies

Requested AGP **9.4.1**, Gradle **9.8.1**, Kotlin **2.4.21** remain unchanged. The wrapper uses the previously verified distribution checksum; the version catalog and module lockfiles retain exact dependencies. Local builds used Homebrew JDK **27.0.1.0.3** with Java/Kotlin bytecode **17**.

The first-test app uses compile SDK **37.0**, target SDK **37**, Build Tools **37.0.0**. Minimum SDK **26** is a provisional internal-test policy, not a finalized public support commitment.

The app uses Material 3 **1.5.0-alpha29**, Compose BOM **2026.09.00** with resolved Compose **1.13.0-alpha01**, Koin runtime **4.2.2** / compiler **1.2.1**, coroutines **1.11.0**, and serialization JSON **1.12.0**. New UI artifacts are Activity Compose **1.13.0**, Lifecycle **2.11.0**, Navigation 3 **1.2.0**, and Material adaptive Navigation 3 **1.3.0**. These existing cached releases were selected to limit downloads on the unstable connection. Pure model code has no Android/Compose/serialization dependency; `:format` exposes JSON types intentionally.

Room 3, DataStore, Ktor, and Coil pins remain in the catalog for later milestones; the product does not pretend to implement their storage/provider/image functionality. Their compatibility probe remains separate.

## Checks and limits retained

- Initial root configuration and pure tests passed offline. The first complete offline app attempt found uncached UI dependencies. Optional Compose tooling/preview dependencies were removed; one normal resolving build with 15-second HTTP connection/socket timeouts succeeded. Later verification passed offline.
- The Koin compiler confirms transformation of `single<FormatServices>()`; the app test resolves the actual module and shared format engine. Its Kotlin 2.4.21 adapter warning remains: the newest vendor-verified patch is 2.4.20. This local pass does not enlarge vendor guarantees.
- Gradle reports deprecations relevant to a future Gradle 10 upgrade. The tested wrapper remains 9.8.1.
- Lint's first two warnings were fixed using quantity plurals and explicit data extraction rules. No lint warning is suppressed to produce the clean result.
- The [adaptive skill](/Users/trinadhthatakula/.agents/skills/adaptive/SKILL.md)'s Navigation 3 supporting-pane guidance was applied (local instruction source). No golden screenshot suite existed for this new screen; optional tooling downloads were avoided on the unstable connection. Actual adaptive/accessibility verification remains pending rather than claimed.

## CI

[Android internal build workflow](../../.github/workflows/android.yml) checks out the private repository with read-only contents permissions, sets up Java 27, installs SDK 37.0/Build Tools 37.0.0, audits fixtures, runs the same tests/lint, and builds the debug APK. Successful APKs and test reports have seven-day retention. There is no release/publication or visibility-change step.

The workflow uses standard version-tagged official actions; remote CI execution is separate evidence and was not run by this assignment. Local success is not reported as hosted-CI success. The supervisor owns repository creation, commits, pushes, and monitoring that run.

## Next milestones

The [format foundation report](FORMAT_FOUNDATION.md) describes actual parsing, patching, preservation and V2 validation boundaries. Product persistence, full editors/import/export, additional formats, image handling, Gemini and end-to-end device tests remain. Keep the repository private until the user decides to publish it.
