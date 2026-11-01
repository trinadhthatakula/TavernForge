# Optimized release-build verification

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). Scope: REL-02 in the [test strategy](TEST_STRATEGY.md). [Current status](../STATUS.md) identifies the integrated build and verification limits. This increment prepares an optimized, unsigned release variant; it does not select a production signing identity or publish a community release.

## Configuration and authoritative evidence

The app's `release` build type enables `optimization { enable = true }`. The pinned AGP version remains 9.5.0. [Android's R8 setup guidance](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization), retrieved through Android CLI on 1 November 2026, documents this DSL for AGP 9.3 and later. It enables code and resource optimization together and supplies the default Android keep rules equivalent to `proguard-android-optimize.txt`. The cached `gradle-api-9.5.0.jar` was independently inspected with `javap`: `ApplicationBuildType.optimization` and `Optimization.setEnable(boolean)` are present.

No legacy duplicate `isMinifyEnabled`/`isShrinkResources` settings, custom keep rules, blanket package retention, or app-level `-dontwarn` rules were added. There is no `android.enableR8.fullMode=false` override. Debug behavior and signing are unchanged. Release remains unsigned because no release signing configuration is defined.

## Dynamic-code and resource review

| Boundary | Source/cache evidence | Required optimized-runtime check |
| --- | --- | --- |
| Room 3 | `createProjectDatabase` uses the context builder and generated `ProjectDatabase_Impl`. Room 3.0.3's AAR supplies `-keep class * extends androidx.room3.RoomDatabase { void <init>(); }`, covering the generated database constructor and name. Entities/DAOs are reached through generated code. | Launch with existing synthetic projects; open, edit, save and reopen. A successful debug instrumentation run alone does not prove the minified app can open Room. |
| Navigation serialization | Navigation keys use the serialization compiler. Serialization 1.12.0 ships consumer rules for serializable-object instances, companion serializer lookup and runtime annotations. No app-defined named companion serializer or class-name lookup was found. | Visit library, editor, artwork and generation/settings; rotate or recreate, then navigate Back. Confirm selection and pending edits survive the exercised lifecycle. |
| Koin | `single<FormatServices>()` uses the Koin compiler plugin; repository/preferences definitions are explicit constructor lambdas. App source has no reflective constructor discovery. | Cold launch and open the library. Confirm dependencies instantiate in the optimized app. |
| Ktor/OkHttp | `HttpClient(OkHttp)` names the engine explicitly. Gemini JSON is handled through bounded wire/JSON code. OkHttp 5.5.1's AAR supplies its own optional-platform/annotation warning rules. | Open Gemini controls without sending a request. Any live HTTP/provider result remains a separate reviewed-key test. |
| Resources | Source review found no `Resources.getIdentifier` or dynamic resource-name lookup. Manifest entry points and normal Compose resource references remain visible to AGP/R8. | Check launcher, theme, labels, icons, document pickers and images in the optimized app. |

Consumer rules were read from the exact cached artifacts and checked against the release resolved graph and merged R8 configuration. Library-supplied rules are retained as upstream contracts; their presence is not proof that every optional library path is exercised.

## Build and artifact checks

Early task-selection failures are retained in external evidence logs. The initial release-test task was absent under AGP 9's debug-only host-test default. Enabling resource-aware host tests and explicitly enabling the release host test through `androidComponents.beforeVariants` resolves both that selection and Compose Preview task discovery. `:app:tasks --all` now succeeds; this also verifies that task listing realizes both variants correctly. No test-runner/default-value bypass was added.

The final full command passed in 1m22s (`hardening-build6.log`):

```sh
ANDROID_HOME=/Users/trinadhthatakula/Library/Android/sdk ./gradlew :app:tasks --all :core:model:test :format:test :data:testDebugUnitTest :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease :data:lintDebug --offline --no-daemon --console plain
```

JUnit XML reports **348 unique product cases**: 13 model, 128 format, 114 data and 93 app, with zero failures/errors/skips. Release host tests repeat the same 93 app cases and also pass; they do not increase the unique count or prove minified runtime behavior. App debug/release and data debug lint report zero issues. The existing Koin 2.4.21 adapter support-range warning remains documented; no approved versions changed.

Only configuration memberships were added to existing locked product dependencies. Four newly used Compose mapping-tool coordinates were also locked: Kotlin compose-group-mapping 2.4.21, its Kotlin stdlib 2.2.21, and ASM/ASM-tree 9.9.1. These are `composeMappingProducerClasspath` tooling, not runtime upgrades. The runtime notice inventory exactly matches 184 release-runtime coordinates.

After the pinned protobuf attribution was added to the bundled notices, an asset-only rebuild passed in 25s (`hardening-build7.log`). A final backup close-label correction was rebuilt and linted with the same command in 1m2s (`hardening-build8.log`):

```sh
ANDROID_HOME=/Users/trinadhthatakula/Library/Android/sdk ./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug :app:lintRelease --offline --no-daemon --console plain
```

Final unsigned artifact: `app/build/outputs/apk/release/app-release-unsigned.apk`, **2,373,770 bytes**, SHA-256 `cf4bb80acc0b7749f12b58b4477e4f6e747874888f4f58da3fd83044a27fbc6b`. `aapt2 dump badging` confirms package `com.valhalla.tavernforge`, version **0.6.0-internal/code 6**, min SDK 26, target 37 and no `application-debuggable`. ZIP integrity and dex presence pass; packaged notices match the audited asset byte-for-byte. `apksigner verify` deliberately fails with missing signature metadata, confirming this file is unsigned.

R8 outputs include a 49,729,646-byte `mapping.txt`, 51,775-byte merged `configuration.txt`, seeds, removed-code usage, resource analysis and Compose mapping metadata. Room/serialization consumer rules are present; no missing-class failure or blanket app suppression was added. CI now verifies the unsigned optimized APK, bundled notice equality and mapping presence, retains R8 metadata alongside each commit's artifact, and attests both APKs on main. GitHub execution is recorded in status when completed.

## Optimized runtime evidence

A copy signed with the established local debug identity for internal testing only is retained outside Git as `TavernForge-worktrees/evidence/TavernForge-0.6.0-optimized-internal.apk`. Signature verification passes; SHA-256 is `9569fded08ee1d0d20e476e6169cd223ec02e7cfe0bfda23ad5edf3486f2fda8`. No signing material or secret environment file is committed. This identity is not a production signing decision.

The final copy installed with `adb -s emulator-5554 install -r` on the existing API 36 AVD; cold launch completed in **805ms**. Existing synthetic library data remained available. The same optimized source checkpoint reopened Room projects, saved a name edit as revision 2, and reopened the saved value after force-stop. Compact/medium/expanded layout, light/dark theme, 200% text, actual artwork previews, inspector hide/show, Settings, offline licenses and keyboard permission isolation were exercised; see [UI evidence](ACCESSIBILITY_REVIEW.md). No Google request was sent.

The final copy also installed using `adb -s 1da5425f install -r` on the reconnected API 36 phone. Package manager confirms code 6 / 0.6.0-internal; cold launch passed in 480ms. Existing data was retained without uninstalling or clearing storage.

The previously passing 26 AVD instrumentation cases are historical regression evidence from the 0.5 foundation and were not rerun for this UI/asset increment. Source-level tests, minified runtime smoke and physical installation are reported separately.

## Remaining release work

Production signing/distribution, broader minimum-SDK/device and TalkBack coverage, and a user-key Google smoke test remain separate. Runtime dependency notices are now bundled and their previously missing protobuf attribution is resolved through pinned upstream source. This optimized internal APK is not a community-release sign-off.
