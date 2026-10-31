# Independent compatibility probe review

Baseline: [TavernForge kickoff charter](../../TavernForge_Project_Kickoff.html), section 07. Scope: read-only review of [the isolated compatibility probe](../../tools/compatibility-smoke/README.md), not application correctness or final UI approval. Completed 31 October 2026 after recovery.

## Result

**The final local compile/code-generation probe passes, with documented coverage limits and vendor-support exceptions.** The final Coil Compose build log reports `BUILD SUCCESSFUL in 16s`, 32 executed tasks. Its JUnit XML contains exactly the three intended tests, with zero failures, errors, or skips, timestamp `2026-10-31T14:36:45.852Z`.

The tester independently inspected the sources, final test XML, generated Room source/schema, AAR contents, lockfile, and resolved compiler/build-plugin reports. A Python standard-library artifact audit checked the test names/results, schema columns/key, generated class presence, and selected dependency coordinates; all checks passed. No second Gradle build was launched and no claim is made that the tester independently reran the JVM tests.

The build owner's exact commands, environment, and earlier failures are recorded in [COMPATIBILITY_SMOKE.md](../build/COMPATIBILITY_SMOKE.md).

## Effective versions verified

The reports are local ignored artifacts under `tools/compatibility-smoke/build/`. The selected library/compiler graph is retained in [probe/gradle.lockfile](../../tools/compatibility-smoke/probe/gradle.lockfile).

| Component | Effective result | Evidence |
| --- | --- | --- |
| Gradle | 9.8.1 | Checked wrapper URL/checksum and build reports from that version; wrapper execution recorded by owner |
| AGP | 9.4.1 | Root `:buildEnvironment`, `effective-build-plugins.log`, resolved `com.android.tools.build:gradle:9.4.1` |
| Kotlin Gradle plugin | 2.4.21 | Root build classpath upgrades AGP's transitive 2.2.10 request to 2.4.21 |
| Kotlin compiler | 2.4.21 | `kotlinBuildToolsApiClasspath` report and lock select compiler-embeddable, runner, and build-tools implementation 2.4.21 |
| Compose / serialization compiler plugins | 2.4.21 / 2.4.21 | `kotlinCompilerPluginClasspathDebug` report and locks |
| Koin compiler / runtime | 1.2.1 / 4.2.2 | Build/compiler-plugin reports, locks, transformation log and passing constructor-resolution test |
| Room 3 plugin / compiler / runtime | 3.0.3 | Build report, processor/runtime locks, generated Kotlin and schema |
| KSP Gradle plugin | 2.3.13 | Root build classpath and compiler-plugin report; KSP used for Room, not Koin |
| Material 3 / Compose BOM | 1.5.0-alpha29 / 2026.09.00 | Catalog and selected graph |
| Actual AndroidX Compose libraries | 1.13.0-alpha01 for runtime/UI/foundation/animation | Final lockfile; the regular BOM does not prevent alpha dependencies selecting newer versions |
| Coil Compose / DataStore preferences | 3.6.3 / 1.3.0 | Final Android compile/runtime locks; source calls `AsyncImage` |
| Ktor client core / coroutines / serialization JSON | 3.6.1 / 1.11.0 / 1.12.0 | Final compile/runtime locks and source references/serializer test |
| Build environment | Homebrew JDK 27.0.1.0.3; compile SDK 37.0; Build Tools 37.0.0; Java/Kotlin bytecode 17 | Daemon report and probe configuration |

Gradle's embedded Kotlin 2.4.10 is distinct from the project's verified compiler 2.4.21. Processor/tool classpaths may contain other library versions without changing the application's selected compile/runtime versions.

## What the final evidence proves

| Concern | Probe mechanism and observed result | Limit |
| --- | --- | --- |
| Koin transformation | `org.koin.plugin.module.dsl.single<T>()` calls are intercepted in compiler logs; the test resolves a consumer and checks injected singleton identity | The compiler explicitly reports graph/compile-safety validation skipped because the main compilation has no Koin entry point. No complete application dependency graph is validated. |
| Room code generation | KSP generated `ProbeDatabase_Impl` and `ProbeDao_Impl`; both are in the final AAR; test loads the database implementation; schema has the expected `id` primary key and `payload` text column | No database is opened. Transactions, migration, persistence, lifecycle and device behavior remain untested. |
| Serialization | Generated serializer class is in the AAR and the JVM encode/decode test passes | Does not establish unknown-field or raw-number preservation. |
| Compose / Material 3 / Coil Compose | Final composable calls `MaterialTheme`, `Text`, and `AsyncImage`; AAR compilation passes | No rendered screen, image request, accessibility, adaptive layout, or expressive-component runtime test. |
| DataStore / Ktor / coroutines | API types resolve and compile; coroutine signatures participate in Room generation | No preference I/O, HTTP engine/request, provider integration, cancellation, or networking test. |
| Reproducibility | Wrapper has exact distribution checksum; catalog and generated locks exist; resolved compiler and dependency versions were inspected | This is local evidence for the configurations actually built, not a clean second-machine/CI or release build. |

The initial core-only Coil coverage gap was corrected by the owner: the final probe includes `coil-compose` and an `AsyncImage` call. The initial module-scoped `:probe:buildEnvironment` contained no plugin dependencies; the subsequent root-scoped report supplies the actual AGP/KGP evidence. No unresolved blocking source defect was identified in this bounded probe review.

## Warnings and remaining gates

- Koin warns that Kotlin 2.4.21 is newer than its newest verified version, 2.4.20, and uses that adapter. Passing this probe does not erase the warning or extend vendor guarantees.
- The [dependency matrix](../planning/TECH_STACK.md) records that requested AGP 9.4.1 and Gradle 9.8.1 exceed Kotlin's documented fully supported maxima. No requested baseline version was silently changed.
- The build reports deprecated Gradle features incompatible with Gradle 10. This does not fail the pinned Gradle 9.8.1 probe; a future upgrade needs its own investigation.
- Compile SDK 37.0 was needed for selected AAR metadata. Probe minimum SDK 26 remains a test setting, not an approved application support policy.
- This library probe does not package or run an APK, exercise D8/R8 release packaging, test Android Keystore/document picker/storage, or establish any SillyTavern format compatibility.
- The supervisor still owns acceptance of the local matrix evidence and documented support exceptions. Final UI direction and product implementation gates remain separate.

## Final artifact fingerprints

These identify the artifacts reviewed; ignored build outputs can be regenerated and may change on another build.

| Artifact | SHA-256 |
| --- | --- |
| Final JUnit XML | `2dcf3d3f5fc639c02301799ee9b27d4def025291ad6bf1a99ef9fd5622eac51e` |
| Exported Room schema `ProbeDatabase/1.json` | `27e118e10fa830d51fcd62eeeb9ea5ba1062d8763cc18fa35dbff9821b2c6d69` |
| `probe-debug.aar` | `0a5c368041f1fc739b89f3fee7f27d5ee33f5e794fafe3c1623ead181989eae5` |
| `probe/gradle.lockfile` | `7a9a389789c8b52d395d3e909665319e5fc87c261b8971838f28e80ba19f0c27` |
