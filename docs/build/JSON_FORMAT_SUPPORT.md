# JSON format support

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). This extends the earlier [pinned contract research](../planning/FORMATS.md) for the user's requested V1/V2/V3 JSON compatibility. See [current status](../STATUS.md) and [fixture provenance](../../format/fixtures/README.md) for verification evidence and remaining work.

## Implemented JSON contracts

| Input | Recognition | Strict validation and same-format export |
| --- | --- | --- |
| Character Card V1 | No discriminator; six flat character fields present | All six values must be strings; empty strings allowed |
| Character Card V2 | `spec: "chara_card_v2"` | Exactly `spec_version: "2.0"`, required V2 fields and embedded CharacterBook |
| Character Card V3 | `spec: "chara_card_v3"` | Exactly `spec_version: "3.0"`, inherited fields, required `group_only_greetings`, V3 metadata and embedded Lorebook |
| Bare V2 CharacterBook | No discriminator; root `entries` array | V2 CharacterBook fields; no new wrapper is inserted |
| Standalone V3 Lorebook | Official `spec: "lorebook_v3"` envelope | `data` contains a V3 Lorebook; upstream defines no required `spec_version` |
| Native SillyTavern World Info | No discriminator; root `entries` object | Entry objects and known field types from pinned native definitions; missing defaults remain missing |

These are JSON structure and preservation contracts, not a claim that every upstream feature or every JSON dialect is supported. Bare CharacterBook acceptance is a container for the pinned embedded V2 schema, not an upstream specification of a `lorebook_v2` envelope. Unknown discriminator strings, explicit null/numeric/malformed `spec`, and orphaned `spec_version` are not relabeled as V1 or as books. No `lorebook_v2` envelope is invented.

`CharacterCardEngine.validate()` and `export()` dispatch by source kind. Existing `validateV2()` and `exportV2()` remain V2-only compatibility APIs. `createV2()` remains the manual creation template; this change adds no automatic version conversion. `CharacterDocument.isSupportedFormat` means the kind/version has a pinned contract; callers must also check validation before claiming structure is valid.

Unsupported/future-version objects can still be parsed and inspected with their original bytes retained. Strict generic export returns `UNSUPPORTED_SOURCE_FORMAT` or `UNSUPPORTED_SPEC_VERSION`; it does not downgrade or invent missing defaults.

## Validation boundaries

V3 checks inherited V2 fields, mandatory string-array group greetings, optional nickname/source/timestamps/multilingual creator notes, asset record types, lowercase dot-free asset extensions, URI scheme shape, main icon/background cardinality, and the changed Lorebook schema. ISO 639-1 language identifiers are checked against the JVM/Android runtime's ISO language list. Timestamps are finite JSON numbers; fractional seconds and the specified privacy value zero are retained.

V3 lore entries require `use_regex`; `constant` remains optional. IDs may be strings or finite numbers in V3; V2 IDs remain numeric. The pinned V3 TypeScript interface specifies `secondary_keys: Array<string>`, while its prose says comma-separated strings. This implementation follows the explicit TypeScript data model, consistently with V2, and retains string-valued input for inspection while blocking strict export. This discrepancy is not silently repaired. Decorator text and regex strings are retained without running a lore activation or regex engine.

Native World Info has no normative versioned schema in the pinned source. Its server only checks that `entries` exists; its editor fills defaults. TavernForge checks an object of entry objects, known array/string/boolean/numeric fields, supported nullable override types, and optional `characterFilter` object fields. Empty `entries` and sparse entries are accepted. It never inserts defaults, remaps IDs, converts entry arrays/objects, or rewrites `originalData`. Native `position` is checked against integer values 0–7 and `selectiveLogic` against 0–3 from the pinned mappings. Runtime activation semantics and other numeric value ranges are not certified.

Two native source inconsistencies are handled explicitly: `delayUntilRecursion` permits numbers and legacy booleans because the pinned CharacterBook converter emits `false` despite the numeric field definition; `uid` permits numbers and strings because the converter forwards CharacterBook IDs. Known nullable settings such as `scanDepth`, `caseSensitive`, `matchWholeWords`, `sticky`, `cooldown` and `delay` retain null. Unknown fields, including unknown settings, remain opaque.

## Preservation and resource limits

All adapters share the existing bounded UTF-8/JSON parser, duplicate-key rejection, byte/depth/string/number/node/collection limits, atomic revision-aware patches, and cumulative patch-work budget. Untouched valid documents export byte-for-byte; edited documents keep raw unknown fields, extensions, nulls, arrays/objects and numeric lexemes. Edits never rewrite version markers or source format. Patches that switch a detected kind or a discriminator return `CONVERSION_REQUIRED`. Strict export validates the retained source kind and rechecks the exporting engine's resource limits.

Asset metadata remains inert. The engine does not fetch URLs, decode base64 assets, read asset files, render media, execute code, or resolve `embeded://` paths. It preserves metadata and source/timestamp values during unrelated edits and export. JSON metadata support does not establish support for PNG/APNG, CHARX, embedded media, remote asset availability, V3-to-V2 backfill, or cross-version migration. Those need separate adapters and verification.

## Authoritative provenance

The following exact revisions and downloaded file hashes were rechecked for this implementation. Specifications are referenced, not copied into production code or fixtures.

| Definition/source | Immutable revision | SHA-256 |
| --- | --- | --- |
| [V1 spec](https://github.com/malfoyslastname/character-card-spec-v2/blob/8083fb388615ccbce768e97cbbd49d2b3214632c/spec_v1.md) | `8083fb388615ccbce768e97cbbd49d2b3214632c` | `65a11460e8733f1529f87cacc47a27301dfe0392ea8a99f2a97099348a98e759` |
| [V2 spec](https://github.com/malfoyslastname/character-card-spec-v2/blob/8083fb388615ccbce768e97cbbd49d2b3214632c/spec_v2.md) | same V1/V2 revision | `e39e1da4f7a912c965af373846f4ac5c6cfb6207ea3fd5d542060905631acb46` |
| [V3 spec](https://github.com/kwaroran/character-card-spec-v3/blob/f3a86af019fbd99f788f7a1155f399655b34ab35/SPEC_V3.md) | `f3a86af019fbd99f788f7a1155f399655b34ab35` | `3c472a16eeda5d018837e90d30fce2816b0982f07f4dba14c8fcc89aa11fe76c` |
| [World Info definitions](https://github.com/SillyTavern/SillyTavern/blob/06bde939fb1e9c4c8d8641d810f0a916b5bce127/public/scripts/world-info.js) | `06bde939fb1e9c4c8d8641d810f0a916b5bce127` | `111c7f47945839e75b021e09bdbb112a54f0a9b7857d2b95435f573efc7cd9a5` |
| [World Info endpoint](https://github.com/SillyTavern/SillyTavern/blob/06bde939fb1e9c4c8d8641d810f0a916b5bce127/src/endpoints/worldinfo.js) | same SillyTavern revision | `1c7bcf4fc54b41beaaa69a88f0398978be048d735a15891e93691930d31991d2` |

The newly added JSON fixtures are synthetic, specification-derived cases. Passing them establishes local structure, edit and round-trip behavior. Actual exported files reopened in the pinned SillyTavern runtime and Android document-picker/device paths require their own recorded evidence. Check current status for the exact commands and completed checks; source implementation alone is not a passing test.
