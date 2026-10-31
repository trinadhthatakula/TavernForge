# JSON inspector update

Baseline: [TavernForge kickoff charter](../../TavernForge_Project_Kickoff.html). Implemented and locally verified 31 October 2026. Format contracts and limits are recorded in [JSON format support](JSON_FORMAT_SUPPORT.md).

## Behavior

The Android inspector now calls the engine's detected-format validator. It displays Character Card V1/V2/V3, a bare V2 CharacterBook, a V3 lorebook envelope, or SillyTavern World Info by name. Character and book titles use their corresponding source location; available lore-entry counts and declared versions appear in the inspector. Unsupported or future versions receive a distinct unsupported result rather than a V2 failure or a false success. Malformed JSON retains its parsing diagnostics.

The approved hybrid layout, document picker, bounded background reading, and in-memory session behavior continue. Display titles and declared versions are capped at 240 characters without changing source data. Product copy describes JSON inspection; PNG/media handling, saving, editing/export UI, and Gemini remain unfinished.

`InspectionSummaryTest` adds 12 app-level checks for V1/V2/V3 dispatch, malformed V3, unknown/future versions, malformed JSON, standalone V2/V3 books, World Info, embedded entry counts, source fingerprints, and display-name bounds. These use synthetic inputs and the actual format engine. Adapter provenance and preservation/export evidence belong to the independent [format verification report](../testing/JSON_FORMAT_VERIFICATION.md).

## Verification

Exact final command, using cached dependencies and JDK 27:

```sh
ANDROID_HOME=/Users/trinadhthatakula/Library/Android/sdk ./gradlew :core:model:test :format:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon --console plain
```

Result: **BUILD SUCCESSFUL in 16s**. Final JUnit XML and lint XML were read after the build:

| Module/check | Result |
| --- | --- |
| `:core:model:test` | 4 tests passed |
| `:format:test` | 81 tests passed |
| `:app:testDebugUnitTest` | 17 tests passed |
| Total | 102 tests; zero failures, errors, or skips |
| `:app:lintDebug` | 0 issues |
| `:app:assembleDebug` | APK generated |

The first run exposed missing World Info enum bounds. Independent tests and source review led to exact numeric enum validation and a document-kind preservation regression before the final run. The final artifact includes those fixes. Requested dependency versions are unchanged. Existing Koin compiler support-range and Gradle deprecation notices remain non-blocking; they are not new compatibility guarantees.

Artifact: `app/build/outputs/apk/debug/app-debug.apk`.

- Package: `com.valhalla.tavernforge`.
- Version: `0.2.0-internal`, code `2`; min SDK 26, target SDK 37.
- Launcher: `com.valhalla.tavernforge.MainActivity`.
- Size: 13,520,958 bytes.
- SHA-256: `2ffaa0ebb4410bb0ef93b19acf69368aaa8bb6ba993f27b59932ab7d621a2df3`.

Build Tools 37.0.0 `aapt2 dump badging` verified the packaged metadata. `apksigner verify --verbose` exited 0 and verified its v2 debug signature with one signer. `shasum -a 256` and `stat -f '%z bytes'` produced the artifact fingerprint and size above. This is an internal debug APK.

## Limits

These JVM, build, and lint results establish the local code path, not physical document-provider behavior, UI rendering, accessibility, process-death restoration, or complete upstream interoperability. Device installation and physical checks are owned and recorded by the supervisor. The app still inspects source JSON in memory; engine patch/export support is not yet exposed as an editing or export screen.
