# JSON version and lorebook verification

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). Scope: the JSON format expansion requested 31 October 2026, building on [foundation verification](FOUNDATION_VERIFICATION.md). Contracts and interpretation decisions are recorded in [JSON format support](../build/JSON_FORMAT_SUPPORT.md).

**The full 31 October verification passed 102 JVM tests with zero failures, errors or skips, and zero Android lint findings.** The sole build owner assembled `0.2.0-internal`; the tester independently inspected the XML, fixture audit, APK metadata, permissions and signature. The 1 November diagnostic clarification and its focused rerun are recorded below separately.

## Independent expectations and fixtures

The tester read the actual immutable V1, V2 and V3 specifications before writing new assertions. Downloaded source SHA-256 values match the pinned planning records:

| Source | Revision | SHA-256 |
| --- | --- | --- |
| V1 `spec_v1.md` | `8083fb388615ccbce768e97cbbd49d2b3214632c` | `65a11460e8733f1529f87cacc47a27301dfe0392ea8a99f2a97099348a98e759` |
| V2 `spec_v2.md` | `8083fb388615ccbce768e97cbbd49d2b3214632c` | `e39e1da4f7a912c965af373846f4ac5c6cfb6207ea3fd5d542060905631acb46` |
| V3 `SPEC_V3.md` | `f3a86af019fbd99f788f7a1155f399655b34ab35` | `3c472a16eeda5d018837e90d30fce2816b0982f07f4dba14c8fcc89aa11fe76c` |
| Native `world-info.js` | `06bde939fb1e9c4c8d8641d810f0a916b5bce127` | `111c7f47945839e75b021e09bdbb112a54f0a9b7857d2b95435f573efc7cd9a5` |
| Native `worldinfo.js` endpoint | `06bde939fb1e9c4c8d8641d810f0a916b5bce127` | `1c7bcf4fc54b41beaaa69a88f0398978be048d735a15891e93691930d31991d2` |

The [fixture manifest](../../format/fixtures/manifest.json) contains the three retained upstream helper objects and five independently authored positive fixtures: minimal/rich V3, standalone `lorebook_v3`, bare V2 CharacterBook, and native SillyTavern World Info. Rich fixtures include unknown fields, nulls, large numeric values and original numeric lexemes. Synthetic content is explicitly identified; no fixture is claimed to be a live upstream export. The V3 envelope-only upstream helper remains a **negative** strict-export case.

The new suites contain 26 JUnit methods with tables covering multiple individual cases:

| Suite | Methods | Coverage |
| --- | ---: | --- |
| `JsonVersionValidationTest` | 13 | V1/V2/V3 dispatch and byte-identical no-op export; unrelated edits preserve source version, unknowns and numeric lexemes; V1 six required strings; V3 inherited required fields and group greetings; missing/null/wrong types; future versions inspectable but strict export blocked; optional assets, ISO language keys, sources and timestamps; main icon/background cardinality; discriminator changes blocked |
| `LorebookJsonValidationTest` | 13 | Bare V2 book, official V3 envelope and native keyed World Info round trips/edits; required V3 `use_regex`; string or number V3 IDs; optional book/entry fields; native sparse defaults, nullable overrides, nested filter types and exact pinned numeric enums; implicit-kind conversion patches rejected; unknown fields retained |

Existing 59 model/format tests remain unchanged, including bounded parsing, patch atomicity/ownership, numeric-lexeme preservation, long-path resource behavior and explicit V2-only wrappers rejecting other versions. The application owner separately tests source-aware summary/validation dispatch.

## Contract interpretation review

- V3 is validated against its own contract, including required `group_only_greetings` and per-entry `use_regex`. Optional metadata is checked when present. Unknown data and raw decorators remain preserved.
- The official standalone V3 shape is `{ "spec": "lorebook_v3", "data": Lorebook }`; no `spec_version` property is required. The V2 standalone shape is a bare CharacterBook accepted by SillyTavern, without an invented `lorebook_v2` envelope.
- V3 prose describes `secondary_keys` as comma-separated strings while its explicit TypeScript shape says `Array<string>`. The adapter and independent tests follow the explicit interface and inherited V2 representation. `selective` and `constant` remain optional properties under that interface.
- Native World Info uses an object keyed by entry IDs. Upstream backfills defaults during editing; TavernForge preserves absent values instead of making those defaults mandatory or silently adding them. Known nullable overrides accept null. Pinned `position` values are 0–7 and `selectiveLogic` values are 0–3.
- The pinned native converter also emits a boolean `delayUntilRecursion` and can preserve string IDs, although new-entry defaults differ. The adapter retains both shapes. This source review is separate from test execution.
- Final review caught numeric enum rounding risk: `7.0000000000000000001` must not become position 7, and `1e-1000` must not underflow into position 0. Regression cases reject both while accepting equivalent exact integers `7.0` and `7e0`. The implementation uses exact decimal comparison.

## Executed checks

`python3 format/fixtures/verify_manifest.py` passes: **11 pinned files, 3 materialized helper objects**, all checksums and JSON verified. The audit also confirms all eight JVM JSON resources are byte-identical to the manifested fixture cases. It makes no network requests and does not execute vendored JavaScript.

The sole build owner ran this exact command and reported `BUILD SUCCESSFUL in 16s`:

```sh
ANDROID_HOME=/Users/trinadhthatakula/Library/Android/sdk ./gradlew :core:model:test :format:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon --console plain
```

The tester independently parsed final JUnit XML under `core/model/build/test-results/test`, `format/build/test-results/test` and `app/build/test-results/testDebugUnitTest`: **4 model + 81 format + 17 app = 102 tests**, with zero failures, errors or skips. The final XML includes the exact-enum and implicit-kind-change regression cases added after the preceding 101-test run. `app/build/reports/lint-results-debug.xml` contains **zero issues**. No second Gradle process was launched by the tester; command output was retained in the build owner's tool session, not a checked-in plain-text log.

Independent artifact commands:

```sh
/Users/trinadhthatakula/Library/Android/sdk/build-tools/37.0.0/aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk
/Users/trinadhthatakula/Library/Android/sdk/build-tools/37.0.0/apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk
```

The APK is `com.valhalla.tavernforge`, version code 2 / `0.2.0-internal`, 13,520,958 bytes, SHA-256 `2ffaa0ebb4410bb0ef93b19acf69368aaa8bb6ba993f27b59932ab7d621a2df3`. APK Signature Scheme v2 verifies with one signer. The SDK signer emits a JDK native-access warning and exits successfully. The only requested permission is the app-scoped `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`; Internet and broad-storage permissions are absent. This is internal debug-artifact verification, not release signing.

## 1 November diagnostic clarification

The missing V3 `group_only_greetings` diagnostic now states: “V3 requires this array; it may be empty [] when no group greetings are used.” Schema requirements are unchanged. The existing required-fields test now checks the helpful message, confirms validation does not insert a missing array or alter the source bytes, and confirms explicit `[]` validates and exports byte-for-byte unchanged. Null and other wrong types still fail.

The sole build owner ran:

```sh
ANDROID_HOME=/Users/trinadhthatakula/Library/Android/sdk ./gradlew :format:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon --console plain
```

The command passed in 16 seconds. Independent XML inspection confirms **81 format + 17 app = 98 tests executed**, zero failures, errors or skips, and zero lint issues. The four unchanged model tests passed in the earlier full 102-test run and were not rerun for this wording change.

The updated `0.2.0-internal` / code 2 APK is 13,521,062 bytes, SHA-256 `2e134bc28b3e2c61fd1973e54445f3758eaa6472dbbe4e00cb113e7b22ecc9a0`, independently rehashed by the tester. The build owner verified metadata and signature. The supervisor reports successful replacement installation and cold launch on the connected device; the tester did not independently operate the device. This does not add document-picker or live upstream-interoperability evidence.

## Scope limits

These checks concern structural JSON import, in-memory inspection, and format-engine edit/export. The Android shell does not yet provide saved-project editing or export UI. PNG/APNG/CHARX containers, asset downloads or execution, lorebook prompt matching/decorators, automatic conversion, persistent storage, credentials and Gemini remain outside this scope.

Real user cards and actual upstream-export interoperability are not yet certified. Android document-picker behavior, lifecycle/accessibility and the device installation are reported separately by the supervisor; JVM results alone cannot prove those behaviors. This report does not establish community-release readiness.
