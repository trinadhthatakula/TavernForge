# Compatibility smoke evidence

Baseline: [TavernForge kickoff charter](../../TavernForge_Project_Kickoff.html), section 07. Probe: [`tools/compatibility-smoke`](../../tools/compatibility-smoke/README.md). Evidence collected 31 October 2026 after recovery from the power interruption.

## Result

**1 November dependency integration:** the user authorized merging Dependabot PRs #1–#6. Both catalogs/wrappers now use AGP **9.5.0**, Gradle **9.8.2**, and Material 3 **1.5.0-beta02**; the unchanged BOM resolves Compose UI/foundation/runtime **1.12.1**. Kotlin remains **2.4.21**, Room **3.0.3**, KSP **2.3.13**, and Koin runtime/compiler **4.2.2/1.2.1**. From the project root, `./gradlew -p tools/compatibility-smoke :probe:testDebugUnitTest :probe:assembleDebug --offline --no-daemon --console plain` passed in 16 seconds, 33 tasks executed. The generated JUnit XML confirms **3 tests, zero failures/errors/skips**. The root product separately passed 134 JVM tests, both lints and six physical-device Room checks; see [current status](../STATUS.md).

The new wrapper checksum matches the [official Gradle 9.8.2 checksum](https://services.gradle.org/distributions/gradle-9.8.2-bin.zip.sha256): `21bd5da2de49b642904077f38c0efdc7778e92b3ed0889392dca61b97e389607`. [AGP 9.5](https://developer.android.com/build/releases/agp-9-5-0-release-notes) requires Gradle ≥9.6 and JDK ≥17, satisfied here. Vendor range caveats remain: [Kotlin's table](https://kotlinlang.org/docs/gradle-configure-project.html) lists Kotlin 2.4.21 through Gradle 9.7/AGP 9.3.1; [Gradle's tested matrix](https://docs.gradle.org/9.8.2/userguide/compatibility.html) lists Kotlin through 2.4.20-RC2 and AGP through 9.5.0-alpha02. Koin still warns that its newest verified adapter is Kotlin 2.4.20. Passing checks do not expand these vendor claims.

The rest of this document preserves the original 31 October baseline evidence.

**The isolated Android library probe builds and all three JVM checks pass with the requested AGP 9.4.1 / Gradle 9.8.1 / Kotlin 2.4.21 baseline unchanged.** Final source includes Compose Material 3 and Coil Compose `AsyncImage`, Room 3 code generation, and Koin compiler DSL transformation. This establishes a local smoke result; it does not extend the vendors' documented support ranges or complete milestone M0.

Final command, from `tools/compatibility-smoke`:

```sh
./gradlew :probe:assembleDebug :probe:testDebugUnitTest --write-locks --no-daemon --console plain
```

Exit **0**; `BUILD SUCCESSFUL in 16s`; 32 actionable tasks executed. JUnit XML reports **3 tests, 0 failures, 0 errors, 0 skipped**:

- `koinCompilerTransformsConstructorResolution`: resolves a consumer and its singleton dependency using the compiler DSL. This would fail if its placeholder calls were not transformed.
- `serializationCompilerProducesWorkingSerializer`: executes the generated serializer and checks the decoded object.
- `roomGeneratedDatabaseImplementationIsCompiled`: checks that the generated database implementation class exists and extends the declared database. It does not open a database.

The final AAR is `probe/build/outputs/aar/probe-debug.aar` (33,087 bytes). Room generated `ProbeDatabase_Impl.kt` and `ProbeDao_Impl.kt` under `probe/build/generated/ksp/debug/kotlin/`; the exported [schema](../../tools/compatibility-smoke/probe/schemas/com.valhalla.tavernforge.compatibility.ProbeDatabase/1.json) is kept alongside the source. Test XML is under `probe/build/test-results/testDebugUnitTest/`.

## Effective toolchain and dependencies

These values were checked in resolved classpaths and the [dependency lock](../../tools/compatibility-smoke/probe/gradle.lockfile), not inferred solely from the [version catalog](../../tools/compatibility-smoke/gradle/libs.versions.toml).

| Component | Resolved version / evidence |
| --- | --- |
| Gradle wrapper | 9.8.1; generated wrapper `--version` passed |
| Android Gradle Plugin | `com.android.tools.build:gradle:9.4.1`, root buildscript classpath |
| Kotlin Gradle Plugin | 2.4.21; root classpath explicitly shows AGP's 2.2.10 dependency resolved to 2.4.21 |
| Kotlin compiler and runner | 2.4.21 in `kotlinBuildToolsApiClasspath` |
| Compose compiler | 2.4.21 in `kotlinCompilerPluginClasspathDebug` |
| Serialization compiler | 2.4.21 in `kotlinCompilerPluginClasspathDebug` |
| Koin runtime / compiler | 4.2.2 / 1.2.1; compiler log confirms interception of both `single<T>()` definitions |
| Room runtime / compiler / Gradle plugin | 3.0.3, all on `androidx.room3` artifact/package line |
| Room processing | KSP Gradle plugin 2.3.13; no Koin KSP processor |
| Compose BOM | 2026.09.00; Material 3 alpha override participates in normal dependency resolution |
| Compose UI / foundation / runtime | 1.13.0-alpha01; Material ripple remains 1.12.1 in the resolved graph |
| Material 3 | 1.5.0-alpha29 |
| Coil Compose | 3.6.3, including `coil-compose-android` and `coil-compose-core-android` |
| DataStore preferences | 1.3.0 |
| Ktor client core | 3.6.1 |
| Application coroutines / serialization JSON | 1.11.0 / 1.12.0; compiler tooling configurations also carry their own older dependencies |

Read-only evidence commands also passed offline after the build:

```sh
./gradlew :buildEnvironment --offline --no-daemon --console plain
./gradlew :probe:dependencies --configuration kotlinBuildToolsApiClasspath --offline --no-daemon --console plain
./gradlew :probe:dependencies --configuration kotlinCompilerPluginClasspathDebug --offline --no-daemon --console plain
```

Root-scoped `:buildEnvironment` is necessary here: `:probe:buildEnvironment` has an empty local buildscript classpath and does not establish the root plugin versions. Gradle's `--version` output reports embedded Kotlin 2.4.10 for Gradle itself; the project compiler is separately verified as 2.4.21.

## Environment and wrapper

- macOS 27.2, Apple aarch64, Homebrew OpenJDK `27.0.1.0.3` dated 2026-10-24.
- `/Library/Java/JavaVirtualMachines/openjdk-23.jdk` also reports Java 27.0.1.0.3; the directory name does not establish an available JDK 23 runtime.
- Probe compile SDK **37.0**, Build Tools **37.0.0**, Java/Kotlin bytecode target **17**. Probe `minSdk = 26` is not an application support-policy decision.
- Wrapper generated with Gradle 9.8.1 and the [published distribution SHA-256](https://services.gradle.org/distributions/gradle-9.8.1-bin.zip.sha256): `dce76f55f8e251a3a1f130eb120f30b3d271de2b76c9b0729d316b5a1b6dc01f`. URL validation and checksum validation remain enabled; network timeout is 60 seconds.

## Constraints and warnings retained

1. **Koin's verified adapter range:** the compiler warns that Kotlin 2.4.21 is newer than its newest verified version 2.4.20 and proceeds with that adapter. DSL transformation and runtime resolution pass here, but this is not a vendor compatibility guarantee. Preserve the support-range caveat from the planning matrix.
2. **Compile-time Koin graph validation was not exercised:** the compiler reports it was skipped because the main compilation has no Koin application entry point. Runtime dependency resolution is covered; full application graph validation must be checked in M0/M2 integration.
3. **SDK floor for these dependencies:** an earlier probe run with compile SDK 36 failed AAR metadata checks. Selected Material 3, Compose, and Coil artifacts require SDK 37 or newer. Increasing the probe to the already installed SDK 37.0 fixed that constraint without changing the requested AGP/Gradle/Kotlin baseline.
4. **Network recovery:** the first attempt failed before compilation when Google Maven terminated a TLS handshake. The exact POM returned HTTP 200 independently, and retrying the unchanged dependency versions resolved it. No TLS/security checks were disabled.
5. **Gradle deprecations:** the build reports deprecated features incompatible with Gradle 10. The tested wrapper is 9.8.1; Gradle 10 support is not claimed.
6. **Runtime and product boundaries:** no Android device tests, Room I/O, image loading, HTTP calls, Keystore behavior, Compose accessibility, project persistence, or SillyTavern compatibility are proven. There is no Activity, APK, production screen, or application build. The three tests are compiler integration checks, not product acceptance tests.

## Reproduction and next work

Use the wrapper and included dependency locks for subsequent builds; omit `--write-locks` unless intentionally changing dependencies. Ignored `tools/compatibility-smoke/build/` contains `final-probe.log`, `effective-build-plugins.log`, `effective-toolchain.log`, `effective-compiler-plugins.log`, and earlier failure logs. The commands above regenerate the evidence after another interruption.

The next M0 step can reuse these exact pins, built-in Kotlin setup, and SDK requirement in the planned product modules. Add product CI and run its own build/tests before marking M0 complete. Keep the isolated probe separate, and do not mistake its sample Room entity or compile-only UI function for canonical project contracts or the approved hybrid UI.

## Official implementation references

- [AGP built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin): do not apply `org.jetbrains.kotlin.android` alongside AGP's built-in support.
- [Koin compiler setup](https://insert-koin.io/docs/setup/compiler-plugin/): plugin ID and compiler DSL imports.
- [Starting Koin](https://insert-koin.io/docs/reference/koin-core/starting-koin): isolated `koinApplication` lifecycle for the JVM resolution test.
- [Room 3 setup/release](https://developer.android.com/jetpack/androidx/releases/room3): `androidx.room3` plugin, `room3` extension, and KSP-only Kotlin generation.
