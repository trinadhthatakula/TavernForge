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

Consumer rules were read from the exact cached artifacts selected by the current debug runtime lockfile. The release resolved graph and merged R8 configuration must confirm the release selection before this audit is treated as release-artifact evidence. Library-supplied rules are retained as upstream contracts; their presence is not proof that every optional library path is exercised.

## Build and artifact checks

The supervisor is the sole Gradle/device owner. The first suggested command was:

```sh
./gradlew :app:assembleRelease :app:lintRelease :app:testReleaseUnitTest --write-locks --offline --no-daemon --console plain
```

That command **failed during task selection** because `:app:testReleaseUnitTest` does not exist in this configured build. No source/build tasks ran in that attempt. The command is recorded as failure evidence, not as a runnable verification instruction.

[AGP 9.0's documented default change](https://developer.android.com/build/releases/agp-9-0-0-release-notes#behavior-changes) explains the result: `android.onlyEnableUnitTestForTheTestedBuildType` defaults to `true`, so the default tested `debug` build type receives unit tests. The same default and selection logic were confirmed in the pinned AGP 9.5.0 classes `BooleanOption` and `VariantManager`. This is unrelated to enabling R8. The existing debug JVM tests remain the source-level regression suite; runtime validation must exercise the optimized APK. No persistent testing override was added merely to repeat the same unminified JVM tests.

The subsequent `:app:tasks --all` attempt failed while creating `generateDebugComposePreviewRunfiles`: Compose Preview requires Android resources, while `testOptions.unitTests.isIncludeAndroidResources` was false. The failure is recorded in the supervisor's `hardening-tasks.log`; it is a preview/test setup failure rather than an R8 compile error. [Android's Robolectric setup](https://developer.android.com/training/testing/local-tests/robolectric) and the cached AGP 9.5 `UnitTestOptions` API expose `isIncludeAndroidResources = true`. This setting adds resources/manifest preparation to host tests; it does not change production debuggability, release signing, optimization, or automatically select a test runner. `isReturnDefaultValues` is a separate setting and remains false. Any fix still needs a successful task listing and the existing app JVM suite.

The first actual release resolution must add the newly exercised release configurations to existing dependency locks without changing approved dependency pins. After reviewing that diff, repeat only checks justified by resulting fixes. Capture the unsigned APK's SHA-256, byte size, package/version/SDK metadata, non-debuggable manifest state and merged R8 outputs (`mapping.txt`, `configuration.txt`, `usage.txt` where generated). Check missing-class diagnostics instead of pasting generated suppression rules without understanding the missing dependency.

For emulator testing, sign a copy of the unsigned APK outside the repository using the already established local test signer, without adding signing material or passwords to source, tool output or documentation. Keep the unsigned artifact and its digest separate from the signed test copy. Do not replace the user's phone build with an incompatible signer or erase app data to force installation.

## Verification status

- Implemented: release optimization configuration and focused source/consumer-rule audit.
- Verified by inspection: current Android guidance and the pinned AGP API expose the selected DSL; no custom keep or warning-suppression rules were introduced.
- Attempted: initial release command stopped at missing-task selection; it produced no passing build/test evidence.
- Awaiting supervisor execution: task inventory, corrected release resolution/build/lint, merged configuration and APK metadata, then optimized-app emulator workflows.
- Still separate: production signing/distribution, distribution-specific third-party notices, physical-device/minimum-SDK coverage and live Gemini verification. An optimized internal APK is not a release-readiness sign-off.
