# First format foundation

Baseline: [TavernForge kickoff charter](../../TavernForge_Project_Kickoff.html), sections 04 and 06. Design: [canonical contracts](../planning/ARCHITECTURE.md). This is the initial JVM foundation, not a claim of full SillyTavern interoperability.

## Implemented boundary

`core:model` contains resource budgets, immutable original bytes/hash, diagnostic phases/severities, nullable character-field projections, and a minimal project identity/export-target model. `format` contains the document, parser, explicit patch operations, V2 validator, template factory and strict JSON exporter. Neither module depends on Android, Compose, Room, or a network provider.

The public entry point is `com.valhalla.tavernforge.format.CharacterCardEngine`:

| Operation | Behavior |
| --- | --- |
| `parse(bytes)` | Strict UTF-8 and JSON syntax, duplicate-name detection, bounded resources, source format inspection. Returns either an owned document or diagnostics. Parsing success does not assert schema validity. |
| `validateV2(document)` | Checks exact V2 envelope, every required known field/type, optional embedded-book/entry types and position enum. Returns a report; empty required strings are permitted. |
| `patch(document, expectedRevision, operations)` | Explicit RFC 6901 paths with add/replace/remove, array insertion/appending and revision conflict detection. The batch is atomic. Invalid draft fields can be edited, but export still validates. |
| `exportV2(document)` | Blocks schema errors and unsupported source formats. Untouched valid documents return original bytes after a source/tree consistency check. Edited documents serialize through the bounded writer. |
| `createV2(name)` | Creates all V2-required fields with valid empty defaults. Throws `IllegalArgumentException` if a supplied name/template cannot fit the configured budgets. |

Document construction is private. Original bytes, exported bytes, and raw-tree access use defensive ownership/snapshots. Patches first encode and parse caller values into an owned tree, rejecting cyclic containers and invalid unquoted literals. Callers cannot mark a modified document as an untouched import.

## Preservation and validation

Source format and export target remain distinct. V1/V3/unknown JSON can be inspected without relabeling it. Changing `spec` or `spec_version` through an ordinary patch returns `CONVERSION_REQUIRED`; explicit migrations are not implemented. V1/V3 source documents cannot become V2 exports by patching their fields.

Unknown JSON fields/extensions, nested arrays/objects, explicit nulls, missing fields, booleans and numeric lexemes are retained. Numeric tokens are never converted to floating point for storage or output. Schema-defined numeric fields are checked for finite-number compatibility; unknown extension numbers retain their original valid JSON spelling, including large integers and exponents.

After an edit, JSON whitespace and string-escape spelling may change; untouched values and number lexemes remain. No-op export is byte exact. Lossy conversions, migrations, metadata normalization, and inferred defaults on imported data are deliberately absent.

The implemented V2 field contract was read from the immutable upstream [specification at `8083fb388615ccbce768e97cbbd49d2b3214632c`](https://github.com/malfoyslastname/character-card-spec-v2/blob/8083fb388615ccbce768e97cbbd49d2b3214632c/spec_v2.md). The version-specific validator records this pin. It validates optional fields only when present; explicit null is not the same as absence. Selective matching without secondary keys produces a semantic warning unless the entry is constant. Unknown extension semantics are not invented or discarded.

## Resource policy

Default `JsonLimits` values:

| Limit | Default / meaning |
| --- | --- |
| `maxBytes` | 4 MiB UTF-8 source/output |
| `maxDepth` | 64; root value has depth 1 and each child value adds one |
| `maxStringChars` | 1 Mi UTF-16 code units per decoded name/string |
| `maxNodes` | 100,000 JSON values including containers, excluding member names |
| `maxCollectionEntries` | 10,000 members per object/elements per array, including lore entries |
| `maxNumberChars` | 1,024 characters in a number token |
| `maxPatchOperations` | 1,000 operations in an atomic batch |
| `maxPatchWorkBytes` | 64 MiB cumulative encoded patch values/intermediate documents per batch |

Limits are configurable within constructor safety ranges. The parser checks them before a map can lose a duplicate value; escaped-equivalent object names count as duplicates. Only ASCII hexadecimal digits are accepted in Unicode escapes. UTF-8 replacement decoding, BOM stripping, comments, trailing commas, non-finite number literals and unpaired surrogate strings are not accepted.

Oversized input returns `Failure(source = null)` before copying it into the model. The caller retains ownership of the original selected file; bounded malformed inputs retain copied source bytes for inspection/recovery. Document-picker code must also stop reading at `maxBytes + 1`, because the in-memory parser cannot undo an unbounded caller read.

Diagnostic paths use parent-linked segments while traversing a tree. They are escaped/joined only when a diagnostic is emitted, preventing a large ancestor name from being copied for every descendant. Final paths are exact RFC 6901 pointers rather than truncated display labels; their maximum size follows the bounded source/value input (escaping may expand names).

## Verification and remaining work

Independent tests are owned by the tester; executed results are recorded in [foundation verification](../testing/FOUNDATION_VERIFICATION.md) and the [product build report](PRODUCT_BOOTSTRAP.md). The implementation owner does not run a competing Gradle build while the bootstrap agent owns the shared build.

This foundation does not implement PNG/APNG/CHARX, standalone World Info, V3 validation/export, V1 migration, version conversion/loss review, saved-project persistence, asset associations, secure credentials, Gemini, or device workflows. Full V2 support is not advertised until representative upstream interoperability is observed in addition to the current parser/patch/schema tests. Typed projections and the small project identity will grow alongside those verified contracts.
