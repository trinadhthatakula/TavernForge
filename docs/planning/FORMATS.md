# Pinned format contracts and implementation scope

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), section 04. Sources retrieved at immutable revisions on 31 October 2026. This document records pinned contracts and planned container/interoperability work. JSON adapters are now implemented for character V1/V2/V3, bare V2 CharacterBook, standalone V3 lorebook, and native World Info; see the [JSON support matrix](../build/JSON_FORMAT_SUPPORT.md) and [JSON verification evidence](../testing/JSON_FORMAT_VERIFICATION.md). Full SillyTavern interoperability remains unverified. See [fixture inventory](../../format/fixtures/README.md) for provenance.

## Authoritative source pins

| Contract | Repository revision / source | Observed SHA-256 of retrieved file |
| --- | --- | --- |
| Card V1 | [`malfoyslastname/character-card-spec-v2@8083fb388615ccbce768e97cbbd49d2b3214632c/spec_v1.md`](https://github.com/malfoyslastname/character-card-spec-v2/blob/8083fb388615ccbce768e97cbbd49d2b3214632c/spec_v1.md) | `65a11460e8733f1529f87cacc47a27301dfe0392ea8a99f2a97099348a98e759` |
| Card V2 / embedded CharacterBook | [same revision, `spec_v2.md`](https://github.com/malfoyslastname/character-card-spec-v2/blob/8083fb388615ccbce768e97cbbd49d2b3214632c/spec_v2.md) | `e39e1da4f7a912c965af373846f4ac5c6cfb6207ea3fd5d542060905631acb46` |
| Card V3 / V3 Lorebook | [`kwaroran/character-card-spec-v3@f3a86af019fbd99f788f7a1155f399655b34ab35/SPEC_V3.md`](https://github.com/kwaroran/character-card-spec-v3/blob/f3a86af019fbd99f788f7a1155f399655b34ab35/SPEC_V3.md) | `3c472a16eeda5d018837e90d30fce2816b0982f07f4dba14c8fcc89aa11fe76c` |
| SillyTavern PNG behavior | [`SillyTavern/SillyTavern@06bde939fb1e9c4c8d8641d810f0a916b5bce127/src/character-card-parser.js`](https://github.com/SillyTavern/SillyTavern/blob/06bde939fb1e9c4c8d8641d810f0a916b5bce127/src/character-card-parser.js) | `b74541cd54bb3fe3ab39b3e040f6dba824b263ed9344e4dcf98363e4e148e07c`; tester rechecked exact vendored bytes |
| World Info UI/defaults/conversion | [same SillyTavern revision, `public/scripts/world-info.js`](https://github.com/SillyTavern/SillyTavern/blob/06bde939fb1e9c4c8d8641d810f0a916b5bce127/public/scripts/world-info.js) | `111c7f47945839e75b021e09bdbb112a54f0a9b7857d2b95435f573efc7cd9a5` |
| World Info storage endpoint | [same revision, `src/endpoints/worldinfo.js`](https://github.com/SillyTavern/SillyTavern/blob/06bde939fb1e9c4c8d8641d810f0a916b5bce127/src/endpoints/worldinfo.js) | `1c7bcf4fc54b41beaaa69a88f0398978be048d735a15891e93691930d31991d2` |

These are source/definition pins, not a claim about every current SillyTavern release. Keep the tested application revision attached to fixtures and compatibility results. The upstream detector's acceptance must never replace strict schema validation.

## Card adapters

**V1:** flat mandatory string fields `name`, `description`, `personality`, `scenario`, `first_mes`, `mes_example`. Empty strings are valid defaults; absent or null is not the required structure. The JSON adapter now supports validation, patching, and same-format strict export, retaining the flat source and unknown keys. V1→V2 is an explicit conversion with a new envelope and required default values, never an implicit overwrite. The V1 document's historical “EXIF” wording does not override the actual PNG chunk implementation used by SillyTavern.

**V2:** strict target envelope `spec: "chara_card_v2"`, `spec_version: "2.0"`, object `data`. Inherited V1 fields move under `data`; also require `creator_notes`, `system_prompt`, `post_history_instructions`, `creator`, `character_version` as strings; `alternate_greetings` and `tags` as string arrays; `extensions` as an object. `character_book` is optional. Preserve arbitrary extension values and unknown fields at every level; validate before conversion or known-field editing.

The embedded V2 CharacterBook has required `extensions` object and `entries` array. Entry required fields are `keys` string array, `content` string, `extensions` object, `enabled` boolean, and numeric `insertion_order`. Other fields are optional according to the pinned definition: book name/description/scan depth/token budget/recursive scanning; entry case sensitivity/name/priority/id/comment/selective/secondary keys/constant/position. Do not make all editor controls format-required. V2 native `position` is `'before_char' | 'after_char'`; richer SillyTavern positions require their documented extension mapping.

**V3:** strict export target uses `spec: "chara_card_v3"`, `spec_version: "3.0"` and inherited fields, plus mandatory `group_only_greetings` string array. The pinned interface also defines optional assets, nickname, multilingual creator notes, source strings, creation/modification dates and the changed embedded lorebook model. V3 is not V2 with renamed envelope values. The JSON adapter validates asset metadata, URI shape, main-icon relationships, and V3 lore entry fields with specification-derived fixtures. Asset resolution/rendering and runtime semantics remain outside this JSON inspector.

V3's specification recommends accepting future-version imports with appropriate handling of unknown features. Separate permissive inspection/source preservation from strict export to the known 3.0 target. Do not erase or silently downgrade a newer document. Unsupported media/asset behavior must be disclosed; retaining an opaque unknown payload is preferable to inventing a conversion.

The existing V3 helper fixture has only an envelope and empty `data`; it is a **negative strict-schema fixture** even though SillyTavern's detector accepts it. Detector code also permits some wrong-shaped values through JavaScript coercion. Tests must cover these differences; see the tester's recovery report.

## PNG/APNG metadata

V3 specifies UTF-8 JSON → base64 in a PNG `tEXt` chunk with keyword `ccv3`; when `chara` and `ccv3` coexist, V3 should take precedence. The pinned SillyTavern reader searches case-insensitive keyword matches and selects the first `ccv3`, then the first `chara`. Its writer adds `chara` and attempts a `ccv3` payload after relabeling the envelope; a stale comment describes only `chara`. Do not copy that relabeling as proof of strict V3 validity.

Implement PNG signature/chunk length/type/CRC/IEND checks, valid base64 and UTF-8 decoding, decoded JSON inspection and target schema checks as separate phases. Bound total image bytes, chunk count, payload size and decoded allocation. Inventory all recognized metadata, flag duplicates/conflicts, preserve source bytes and unrelated chunks, and require an explicit policy for malformed preferred metadata rather than silently falling back to a different card. PNG without card metadata is valid artwork, not a character parse crash.

Editing metadata should retain compressed image data and unrelated chunks where possible. Exported V2 and V3 metadata must each pass their actual target adapter. Backfilling a V2 compatibility payload from V3 is a reviewed conversion; preserve V3-only data in `ccv3` and disclose losses to V2 readers. APNG animation/chunk preservation, PNG extra asset chunks and CHARX are not automatically covered by basic PNG-card tests. The charter requests JSON/PNG, so CHARX and additional embedded assets are deferred unless separately scoped and verified.

## Standalone World Info is a distinct adapter

At the pinned revision, standalone worlds use a top-level `entries` **object keyed by ID**, while embedded character books use an **array**. The storage endpoint's empty object is `{ entries: {} }`; it is not a full schema. `createWorldInfoEntry()` allocates `uid` and inserts into `data.entries[newUid]`. `newWorldInfoEntryDefinition` and `newWorldInfoEntryTemplate` define editor defaults; inspect the definition and actual exported files before making every default mandatory.

Important observed native fields include `uid`, `key`, `keysecondary`, `comment`, `content`, `constant`, `selective`, `selectiveLogic`, `order`, `position`, `disable`, `probability`, `useProbability`, `depth`, `scanDepth`, `caseSensitive`, `matchWholeWords`, `excludeRecursion`, `preventRecursion`, and `delayUntilRecursion`. Preserve unknown entries/settings and null overrides. Local document names, global application settings, and per-world exported data are not interchangeable.

The pinned `world_info_logic` maps `AND_ANY=0`, `NOT_ALL=1`, `NOT_ANY=2`, `AND_ALL=3`. `world_info_position` maps before=0, after=1, author's-note top/bottom=2/3, at-depth=4, example-message top/bottom=5/6, outlet=7. These observed enum values are tied to this source revision; UI labels must not invent native JSON strings.

`convertCharacterBook()` is evidence of a transformation, not a lossless shared schema: `keys→key`, `secondary_keys→keysecondary`, `insertion_order→order`, `enabled→!disable`; richer values can come from entry `extensions`. It assigns missing entry IDs, applies defaults, and keeps `originalData`. TavernForge must plan this conversion and preserve provenance/unknowns rather than running a native world through a bare V2 serializer. Probability/recursion/depth/selective relationships need valid, invalid and inherited-null fixtures before semantic support is claimed.

## Fixture plan and acceptance

The inventory now includes specification-derived positive V3 and standalone lorebook JSON plus synthetic native World Info, alongside the original upstream references and helper objects. Local tests exercise validation, edits, preservation, and same-format export. Real pinned-application exports, PNG cards, and application-level round trips remain pending; a fixture checksum audit alone is not interoperability evidence.

1. Capture original synthetic V2/V3/World Info exports from the pinned application, recording source authorship, export steps, application revision, format target, checksum and licensing. Include empty-allowed and richly populated examples.
2. For each adapter, create one-variable negative derivatives for missing/null/wrong types/enums, invalid encoding, duplicate keys/chunks, conflicting metadata, large payloads, depth and non-finite/coerced versions. Keep expected diagnostic phase/path/severity alongside derivation.
3. Add nested unknown sentinels at envelope/data/book/entry levels and check no-op import/export, single-field patch, reorder/duplicate/delete, and cross-format conversion plans independently.
4. Round-trip exported files back into pinned SillyTavern, inspect meaningful values and test strict serialization again. Positive JSON syntax and metadata integrity alone are insufficient.
5. Publish an explicit support matrix listing import/edit/export/container/optional-feature coverage and tested revisions. Unsupported features remain raw-preserved with diagnostics or explicit loss review.

All quoted field names and behavioral observations derive from pinned definitions/source, not third-party summaries. Specifications are linked rather than copied wholesale. Vendored SillyTavern reference bytes/helper derivatives retain their AGPL notice; V3 repository is MIT; V1/V2 repository license suitability and the project's own license remain release decisions. No production license is selected by this research.
