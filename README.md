# TavernForge

Create characters. Build worlds. Export for SillyTavern.

TavernForge is a FOSS Android companion for importing, manually authoring, validating, organizing, and exporting character cards and lorebooks. Optional Gemini assistance uses the user's own key. All workflows share a local project model and strict, version-aware format engine.

The [kickoff charter](TavernForge_Project_Kickoff.html) is the source of truth for scope and requirements. Start with [current project status](docs/STATUS.md) for assignments, discovery results, and pending decisions. Contributors and agents should read [AGENTS.md](AGENTS.md).

## Current phase

The **0.6.0 internal test build** supports offline manual creation, JSON/PNG import, editing, validation and export; PNG/JPEG artwork and lorebook associations; local tags and filters; unsaved edit recovery; and portable library backup with additive restore. Optional Gemini uses a Keystore-encrypted user key, independent default-off permissions, exact request previews and explicit draft acceptance. Start with the [test-build guide](docs/USER_GUIDE.md).

Supported JSON kinds are character **V1/V2/V3**, V2 CharacterBook, standalone V3 lorebook and native SillyTavern World Info. Original bytes, unknown fields, extensions and numeric values are preserved; edits do not silently convert source versions. Static PNG export and reviewed JPEG-to-PNG conversion have bounded support. APNG export, CHARX bundles, embedded-media resolution and cross-version lorebook conversion remain unsupported. See [JSON support](docs/build/JSON_FORMAT_SUPPORT.md), [PNG boundaries](docs/build/PNG_COMBINE_IMPLEMENTATION.md), and [Gemini boundaries](docs/build/GEMINI_IMPLEMENTATION.md).

The [approved hybrid UI](docs/DESIGN_DECISION.md) now combines C’s library/shelves and artwork, A’s native navigation and controls, and B’s optional expanded draft inspector. Settings includes offline open-source licenses and backup/restore. [Explore the design references](docs/design-comparison.html).

[GitHub repository](https://github.com/trinadhthatakula/TavernForge) is **public** under the user’s 1 November decision. [GitHub Actions](.github/workflows/android.yml) checks pull requests and main; [Dependabot](.github/dependabot.yml) proposes weekly dependency updates for review. See the [security configuration](docs/planning/GITHUB_SECURITY.md) for scanning and provenance workflows. Verification includes **348 JVM tests**, **26 API 36 AVD tests**, clean Android lint, exact pinned SillyTavern PNG-reader checks, and real picker backup/restore, editing, artwork and layout checks. Details and artifact/device status are maintained in [current status](docs/STATUS.md). Actual [pinned SillyTavern receiver checks](docs/testing/SILLYTAVERN_INTEROP.md) cover named character imports/exports and identify lorebook/version/numeric limitations. Live Google calls, receiver chat activation and broad physical-device accessibility testing remain separate checks; the internal build is not a general compatibility certification.

## Build and check

Use JDK 27 and Android SDK platform 37.0 with Build Tools 37.0.0 for the development configuration. The user-approved Dependabot batch updates the wrapper to Gradle 9.8.2, AGP to 9.5.0, and Material 3 to 1.5.0-beta02; Kotlin remains 2.4.21. See [current status](docs/STATUS.md) and [toolchain evidence](docs/build/COMPATIBILITY_SMOKE.md) for verification and support-range caveats.

```sh
python3 format/fixtures/verify_manifest.py
./gradlew :core:model:test :format:test :data:testDebugUnitTest :app:testDebugUnitTest :app:testReleaseUnitTest :app:assembleDebug :app:assembleRelease :data:lintDebug :app:lintDebug :app:lintRelease --no-daemon --console plain
./gradlew -p tools/compatibility-smoke :probe:testDebugUnitTest :probe:assembleDebug --no-daemon --console plain
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. Use `--offline` only after the required dependencies are cached. [GitHub Actions](.github/workflows/android.yml) runs the test suites and builds debug and optimized unsigned release APK artifacts, with matching R8 metadata. Production signing is separate. Artifacts follow the public repository’s access rules.

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
