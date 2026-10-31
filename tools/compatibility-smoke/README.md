# TavernForge compatibility smoke project

This isolated build probe belongs to the [kickoff charter](../../TavernForge_Project_Kickoff.html), section 07. It is not the application, an approved UI implementation, or evidence of format support.

The probe uses the user-approved Dependabot upgrades: AGP 9.5.0, Gradle 9.8.2, Kotlin 2.4.21, and Material 3 1.5.0-beta02. Its purpose is to exercise Android compilation, Compose, Material 3, the Koin Kotlin compiler plugin, and Room 3 processing together before application foundations are accepted. See [current evidence](../../docs/build/COMPATIBILITY_SMOKE.md).

See [build evidence](../../docs/build/COMPATIBILITY_SMOKE.md) for executed checks, warnings, and limits. No passing build is claimed until that report records one.

## Run

Use an installed Android SDK with platform 37.0 and Build Tools 37.0.0, set `ANDROID_HOME` (or create an ignored `local.properties` pointing to it), and use a JDK compatible with Gradle 9.8.2. The recovered machine currently supplies JDK 27; Java/Kotlin bytecode targets 17. `minSdk = 26` is a probe setting, not the application's support policy.

From this directory:

```sh
./gradlew :probe:assembleDebug :probe:testDebugUnitTest --no-daemon --console plain
```

The first successful resolving build uses `--write-locks` to record exact transitive dependencies; subsequent runs use those locks. Do not add `--refresh-dependencies` or rewrite locks merely to hide a conflict. This project has its own settings and wrapper and is not a module of a product build.

## What the checks exercise

- Android AAR metadata/resource/Kotlin compilation using AGP's built-in Kotlin with KGP 2.4.21 selected on the plugin classpath.
- A compile-only Compose Material 3 function with Coil Compose `AsyncImage`; no Activity or user-facing app is created.
- Room 3 entity, suspend/Flow DAO, generated database implementation and schema.
- Koin compiler DSL constructor injection resolved in a JVM test, detecting untransformed placeholders.
- A working generated kotlinx.serialization serializer in a JVM test.
- DataStore, Ktor, Coil, and coroutines types on the Android compilation classpath.

Room database I/O, Android device behavior, actual HTTP requests, credential storage, and application workflows remain outside this probe.
