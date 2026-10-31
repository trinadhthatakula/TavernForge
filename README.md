# TavernForge

Create characters. Build worlds. Export for SillyTavern.

TavernForge is a planned FOSS Android companion for importing, manually authoring, validating, organizing, and exporting character cards and lorebooks. Optional Gemini assistance uses the user's own key. All workflows share a local project model and strict, version-aware format engine.

The [kickoff charter](TavernForge_Project_Kickoff.html) is the source of truth for scope and requirements. Start with [current project status](docs/STATUS.md) for assignments, discovery results, and pending decisions. Contributors and agents should read [AGENTS.md](AGENTS.md).

## Current phase

The internal Android app imports JSON through the system document picker, displays format-specific validation, and saves original sources in an offline library. Saved projects can be searched, sorted, reopened, renamed, duplicated, and deleted. It supports character-card **V1, V2 and V3 JSON**, bare V2 CharacterBook, standalone V3 lorebook, and native SillyTavern World Info JSON. The pure Kotlin engine provides bounded parsing, raw-data preservation, revision-aware patches, and strict same-format validation/export without converting versions. Editing and export remain engine APIs. See the [support matrix](docs/build/JSON_FORMAT_SUPPORT.md) and [current verification results](docs/STATUS.md).

The user has [approved a hybrid UI direction](docs/DESIGN_DECISION.md): C’s library, A’s native controls, and B’s expanded inspector. [Explore the design references](docs/design-comparison.html).

[GitHub repository](https://github.com/trinadhthatakula/TavernForge) is **public** under the user’s 1 November decision. [GitHub Actions](.github/workflows/android.yml) checks pull requests and main; [Dependabot](.github/dependabot.yml) proposes weekly dependency updates for review. See the [security configuration](docs/planning/GITHUB_SECURITY.md) for scanning and provenance workflows. Public repository access does not establish release readiness. Manual editing/export UI, PNG/APNG and CHARX containers, media handling, Gemini, and full device/interoperability validation remain pending. The internal build does not establish full SillyTavern compatibility.

## Build and check

Use JDK 27 and Android SDK platform 37.0 with Build Tools 37.0.0 for the development configuration. The user-approved Dependabot batch updates the wrapper to Gradle 9.8.2, AGP to 9.5.0, and Material 3 to 1.5.0-beta02; Kotlin remains 2.4.21. See [current status](docs/STATUS.md) and [toolchain evidence](docs/build/COMPATIBILITY_SMOKE.md) for verification and support-range caveats.

```sh
python3 format/fixtures/verify_manifest.py
./gradlew :core:model:test :format:test :data:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug :data:lintDebug :app:lintDebug --no-daemon --console plain
./gradlew -p tools/compatibility-smoke :probe:testDebugUnitTest :probe:assembleDebug --no-daemon --console plain
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Use `--offline` only after the required dependencies are cached. [GitHub Actions](.github/workflows/android.yml) runs foundation tests and builds an internal APK artifact. Artifacts follow the public repository’s access rules.

Modules: `:core:model` owns immutable source, diagnostic and project types; `:format` owns the version-aware engine; `:data` owns Room 3 metadata and managed source files; `:app` contains the Android library and inspector. `tools/compatibility-smoke` remains a separate dependency probe.

## Product principles

- Local ownership, offline import/manual workflows, and no mandatory account or backend.
- Version-specific SillyTavern compatibility and preservation of unknown data.
- Separate opt-in permissions for Gemini text, images, and follow-up calls.
- Private credentials, request transparency, and explicit acceptance of generated drafts.

## License

Copyright © 2026 Trinadh Thatakula.

TavernForge is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version (**GPL-3.0-or-later**). See [LICENSE](LICENSE) for the full text, copied verbatim from the sibling Thor project.

TavernForge is distributed without any warranty; without even the implied warranty of merchantability or fitness for a particular purpose. Third-party reference material and dependencies retain their own licenses and notices; see [third-party notices](NOTICE.md). Distribution channels and community-release readiness remain open.
