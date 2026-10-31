# TavernForge

Create characters. Build worlds. Export for SillyTavern.

TavernForge is a planned FOSS Android companion for importing, manually authoring, validating, organizing, and exporting character cards and lorebooks. Optional Gemini assistance uses the user's own key. All workflows share a local project model and strict, version-aware format engine.

The [kickoff charter](TavernForge_Project_Kickoff.html) is the source of truth for scope and requirements. Start with [current project status](docs/STATUS.md) for assignments, discovery results, and pending decisions. Contributors and agents should read [AGENTS.md](AGENTS.md).

## Current phase

Internal foundation build: **64 JVM tests pass, Android lint is clean, and [CI passes](https://github.com/trinadhthatakula/TavernForge/actions/runs/41225752764)**. The Android app imports a JSON file through the system document picker and displays format inspection and validation results. The pure Kotlin engine provides bounded parsing, raw-data preservation, revision-aware patches, and strict V2 JSON validation/export. Editing and export are engine APIs at this stage; the app UI is an inspector.

The user has [approved a hybrid UI direction](docs/DESIGN_DECISION.md): C’s library, A’s native controls, and B’s expanded inspector. [Explore the design references](docs/design-comparison.html).

[GitHub repository](https://github.com/trinadhthatakula/TavernForge) is **private** during internal testing. Community publication is a later decision. Persistence, manual editing UI, PNG/World Info support, Gemini, and device/interoperability validation remain pending. The internal build does not establish full SillyTavern compatibility.

## Build and check

Use JDK 27 and Android SDK platform 37.0 with Build Tools 37.0.0 for the tested development configuration. The wrapper pins Gradle 9.8.1; the project retains AGP 9.4.1 and Kotlin 2.4.21. See [toolchain evidence](docs/build/COMPATIBILITY_SMOKE.md) for support-range caveats.

```sh
python3 format/fixtures/verify_manifest.py
./gradlew :core:model:test :format:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --no-daemon --console plain
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Use `--offline` only after the required dependencies are cached. [GitHub Actions](.github/workflows/android.yml) runs foundation tests and builds a private internal APK artifact.

Modules: `:core:model` owns immutable source and diagnostic types; `:format` owns the version-aware engine; `:app` contains the Android inspector. `tools/compatibility-smoke` remains a separate dependency probe.

## Product principles

- Local ownership, offline import/manual workflows, and no mandatory account or backend.
- Version-specific SillyTavern compatibility and preservation of unknown data.
- Separate opt-in permissions for Gemini text, images, and follow-up calls.
- Private credentials, request transparency, and explicit acceptance of generated drafts.

The open-source license and distribution destination remain undecided.
