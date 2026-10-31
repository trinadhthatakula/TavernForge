# Product foundation verification

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). Scope: first pure model/JSON format foundation and read-only review of the initial Android inspection shell and CI. This is not a full M1/MVP or SillyTavern interoperability certification.

## Result

**The final local product build passes 64 JVM tests with zero failures, errors, or skips.** The product build owner ran the tests and assembled the debug APK; the tester independently inspected the final XML, dependency locks, APK permissions/signature, and lint SARIF. No second Gradle process was launched by the tester.

The independent model/format suite contains 59 JUnit methods, several with tables of individual malformed or mistyped inputs. The application owner added five focused app-boundary checks:

| Suite | Methods | Independent assertions |
| --- | ---: | --- |
| `ModelOwnershipTest` | 4 | Input/output byte ownership and known SHA-256, diagnostic-list ownership, severity behavior, invalid resource-budget configuration |
| `BoundedJsonParsingTest` | 17 | Exact byte/depth/string/node/collection/number budgets, strict UTF-8, duplicate decoded keys, malformed syntax/numbers/Unicode, root object requirement, retained original failed input, exact diagnostic pointer under a long ancestor |
| `PreservationAndPatchTest` | 20 | Exact original no-op bytes, copied byte buffers, unknown/null/numeric-lexeme preservation during edits, patch-value ownership, revisions, atomic failures, JSON Pointer escapes/array operations, bounds, no implicit conversion, unsafe external literals/cycles, wide trees under a 512 KiB ancestor key, cumulative patch work budget |
| `V2ValidationTest` | 18 | Independently enumerated required V2 and embedded-book fields, missing vs null/wrong types, optional types/enums, valid empty fields and number types, untouched unknown fields, unsupported version export blocked |
| `BoundedInputTest` (app owner) | 4 | Exact input limit, empty input, oversize despite false `available()` metadata, and zero-length bulk-read progress |
| `ApplicationModuleTest` (app owner) | 1 | Runtime resolution of the shared format-engine service through the actual Koin compiler DSL binding |

The three helper resources are byte-identical copies of existing attributed upstream fixtures. [Resource provenance](../../format/src/test/resources/README.md) links the pinned SillyTavern commit/license and exact V2 specification. Negative inputs and unknown-field sentinels are derived in test code; they are not invented native format fields.

## Independent source review

- The V2 required-field and optional embedded-book expectations were read directly from `spec_v2.md` at `8083fb388615ccbce768e97cbbd49d2b3214632c`, independently of the implementation's field list.
- Review identified a Unicode-escape strictness risk: Kotlin `digitToIntOrNull(16)` can be more permissive than JSON's ASCII hex grammar. The implementation owner replaced it with explicit ASCII ranges; the passing regression table includes fullwidth and Arabic digits.
- The parser checks duplicates before building a `JsonObject`, reports bounded failures, and keeps original bytes only within the configured source budget. Oversized source failures deliberately avoid copying the oversized byte array.
- Patches are validated as a sequence against a revision and own their inserted JSON values. The source owner added protections against untrusted literals, cycles, unreviewed discriminator changes, and mismatched no-op provenance. The tests exercise the public behavior rather than internal helper implementations.
- Supervisor review found diagnostic-path amplification when a very long ancestor key preceded many descendants. The source owner replaced eager path-string copies with lazy segments. A passing test parses, no-op exports, edits and exports a document with a 512 KiB key and 10,000 descendants; a separate test checks one exact escaped error pointer. These are resource-shape regressions, not benchmark or universal memory guarantees.
- The new cumulative `maxPatchWorkBytes` budget is checked at an exact encoded-work boundary and one byte below; rejection leaves the original document unchanged.

## Android and CI source review

- File intake uses the system document picker; actual bytes are bounded instead of trusting reported size. Streams/cursors close through `use`; I/O and JSON parsing execute off the main thread. Optional display-name metadata failure falls back to a generic name.
- Name previews are capped at 240 UTF-16 units with a visible ellipsis before reaching Compose text layout. Diagnostic path previews are capped separately. These display choices do not rewrite imported source values.
- The shell clearly states that inspection is in memory, the original file is never written, and saving/editing/PNG/lorebook/Gemini workflows are unfinished. The initial screen does not present those workflows as working.
- CI grants `contents: read`, runs fixture auditing and product checks, and retains internal APK/test artifacts for seven days. It contains no repository-publication, release, or visibility-changing step. Remote CI execution is tracked by the supervisor; this report covers local evidence and workflow source review.

## Executed evidence

The final root verification ran `:core:model:test :format:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` offline. The log at ignored `build/product-verified.log` reports **`BUILD SUCCESSFUL in 18s`**, 60 actionable tasks (31 executed, 29 up-to-date). The preceding build with dependency locks written also passed in 13 seconds. See [the product build report](../build/PRODUCT_BOOTSTRAP.md) for the full reproduction command and environment.

The independent Python standard-library audit read all six final JUnit XML suites under each module's `build/test-results` directory and confirmed:

- `core:model`: 4 tests; `format`: 55 tests; `app`: 5 tests.
- No failures, errors, or skipped tests.
- The final test XML includes both long-path/resource regressions and the cumulative patch-work boundary test, rather than an earlier run's counts.
- Test resources match their original fixture bytes and expected manifest hashes.
- Lockfiles select project compiler/stdlib 2.4.21; `format` exposes serialization JSON 1.12.0, while `core:model` has no JSON library dependency. The app resolves the expected Material 3/Compose/Navigation 3/adaptive graph.

The owner initially ran `:app:lintDebug` separately (19 seconds), then included it in the final combined verification above. Independent inspection of the final `app/build/reports/lint-results-debug.sarif` found **zero lint findings**.

Independent artifact commands:

```sh
$ANDROID_HOME/build-tools/37.0.0/aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk
```

The packaged manifest lists only the app-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; no Internet or broad-storage permission is requested. The APK signature verifies with v2 signing, one signer. The SDK signer emits a JDK native-access warning; verification exits successfully. This is an internal debug artifact, not release-signing validation.

| Reviewed artifact | SHA-256 |
| --- | --- |
| `app-debug.apk` (14,040,904 bytes) | `56338290a727124dcf3dddccf5b08dca7048bcfd7e3259c020db8594d95c1305` |
| `core/model/gradle.lockfile` | `d8788497780d08da89f92809cae82084e1a9da45899d5f92d6a5870611460206` |
| `format/gradle.lockfile` | `02fb58b9f781bc014287e2afb84c45ed90edca9f0aeb05262f958636960e8780` |
| `app/gradle.lockfile` | `7c07052570dc0e65ed610f4015933cd7e21f48ce6fad9201553080dcfbad0869` |

## Remaining limits

Koin still warns that Kotlin 2.4.21 uses its newest verified 2.4.20 adapter. The build still reports Gradle 10 deprecations. The tested Gradle 9.8.1 baseline is unchanged; passing local checks do not extend vendor support guarantees.

Device SAF permissions/cloud providers, process recreation, screen readers/large text/insets, PNG handling, complete V3, standalone World Info, persistence/Room, credentials/Gemini, and live imports into pinned SillyTavern remain outside the passing scope of these JVM tests. No community-release readiness follows from a successful foundation build.
