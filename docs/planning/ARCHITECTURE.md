# Architecture and canonical contracts

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), sections 04–06. Recovered 31 October 2026. Proposed implementation contracts for supervisor review; no production API exists yet.

## Minimal modules

Start with `:core:model` and `:format` as pure Kotlin/JVM modules, then `:data` and `:app` as Android modules once the toolchain gate permits. Packages within these modules follow the charter's model/validation/character/lorebook/png, repository/file/security/network, domain, and feature boundaries. Split more Gradle modules when ownership/build isolation requires it; the charter's suggested package tree does not require dozens of empty modules.

`app presentation → domain use cases → repository interfaces / format contracts → data implementations`. `format → core:model` only; `data → core:model + format`. The app assembles Koin bindings. Pure format code imports neither Android/Compose nor Room/Ktor. Room's processor may use KSP; Koin uses its Kotlin compiler plugin, never Koin KSP.

## Canonical document model

| Type | Contract |
| --- | --- |
| `SourceEnvelope` | Original bounded source bytes or immutable managed-source reference, SHA-256, media type, encoding/container information, observed format discriminator, upstream contract revision, import timestamp. Retain the original even when parsing/validation fails. |
| `CharacterDocument` | Source envelope + immutable raw JSON tree + version-specific typed projection + explicit patch set/revision. Typed views are conveniences; raw source is the authoritative preservation representation. |
| `CharacterProject` | Stable project ID, document revision, selected export target, draft state, timestamps, tags/indexes, and links. Source format and chosen export target are separate values. |
| `Lorebook` / `LoreEntry` | Preserve standalone World Info raw object/keyed entries or embedded character-book raw array according to its own adapter. Stable local IDs do not overwrite upstream `uid`/`id` or object keys. Typed settings expose only verified semantics. |
| `AssetReference` | App-private stable asset ID/path, original URI provenance as appropriate, MIME, size, hash, dimensions and metadata status. Binary images are files, not Room blobs. |
| `ProjectLink` | Explicit character↔lorebook/artwork association. Shared lorebook edits affect the same referenced document; embedding/export uses a reviewed snapshot and versioned conversion. |
| `GenerationDraft` | Validated candidate + base project revision + requested purpose/model + diff + diagnostics. It is not saved project content until accepted; acceptance rejects stale base revisions or requires a fresh review. |
| `GenerationRecord` | Optional local purpose/model/timestamp/provider usage only. No key and no unnecessary prompt retention. History default remains a product decision; omit content by default while undecided. |

Do not turn missing fields into `null`, missing arrays into `[]`, strings into numbers, or unknown objects into known DTO defaults. Preserve numeric lexemes and JSON values where possible; original bytes provide exact no-op fidelity. Object key order/whitespace are not semantic equivalence, but untouched documents can return exact source bytes. Edited documents preserve untouched values, including nested unknown fields and explicit nulls.

Duplicate JSON object keys cannot be represented losslessly by a normal `JsonObject`; detect them in bounded parsing and reject strict editing/export or retain source as an opaque invalid document. Do not silently keep the last duplicate. Bound byte count, nesting, string size, entry count and decompressed metadata before allocating unbounded structures. Exact limits belong in an explicit configuration with boundary tests, not scattered magic numbers.

## Format pipeline contracts

| Operation | Result and invariant |
| --- | --- |
| `inspect(source, limits)` | Container inventory, observed version, diagnostics. No migration and no saved-state mutation. |
| `parse(source, adapter, limits)` | Parsed raw tree/projection or structured parse failure with source retained. Successful syntax parsing does not imply schema validity. |
| `validate(document, target)` | Issues identified by stable code, severity, phase, JSON pointer/container offset, explanation, and optional fix proposal. Parse/schema/semantic/compatibility are distinct. |
| `patch(document, expectedRevision, operations)` | New document/revision or conflict/invalid operation. Add/replace/remove at explicit paths; unchanged unknown siblings retained. No implicit normalization. |
| `planConversion(document, target)` | New candidate + itemized transformations/loss paths + acknowledgement requirements. Conversion is explicit and separately reviewed. |
| `serializeStrict(document, target, reviewedTransformations)` | Bytes only after target validation contains no errors and required acknowledgements match this exact revision/plan. No unreviewed fallback target. |

Validation severity is independent from writing quality. A specification-required string may validly be empty; a useful description suggestion is not automatically a format error. Unsupported optional semantics can be warnings while raw values remain intact. Arbitrary unknown extensions should not be silently removed or automatically block a no-op round trip; show meaningful compatibility warnings without pretending to understand their values.

PNG processing inventories and validates actual chunks/CRC/length/encoding. Preserve non-target chunks and image bytes when safe. `chara` and `ccv3` may coexist: conflict, precedence and conversion policies must be explicit and fixture-tested. Never overwrite a richer V3 payload with a relabeled V2 object.

## Persistence and application flow

Repositories own Room transactions and managed files. Persist original source plus current raw/patch state and revision, not only search-friendly typed columns. Write assets to temporary app-private files, validate/hash, atomically finalize, then commit stable references; recover orphaned/temp files after interrupted writes. Failed imports and drafts do not replace existing saved projects.

Import, manual forms, and Gemini all create/update this same representation and call the same validation/export use cases. Use Android document picker/scoped storage at the file boundary. Permissions/revoked URI access, cancellation and process death must leave an actionable draft or error rather than corrupt saved data.

Ktor lives behind a provider interface; domain policies decide whether a request may occur. A request preview captures exact model, purpose, selected input, limits, image use and call count. Text/image/follow-up grants are separate; check them immediately before every request. Changing content/model/permissions invalidates the prior preview approval. One explicitly approved call cannot authorize a retry or hidden follow-up. Generated output is untrusted and cannot invoke tools or mutate projects directly.

Credential storage exposes key availability/update/delete/with-key execution, never plaintext preferences or exported models. Keystore-backed encryption requires device checks, backup exclusion and graceful key invalidation handling. Ordinary preference storage holds only non-secret choices. Cancellation and response logging must not leak request headers, API keys or unnecessary prompt text.

## Foundation acceptance

Before editors or storage rely on the model, exercise: missing/null/type distinctions; unknown fields at envelope/data/book/entry levels; no-op bytes; known-field replacement preserving sibling data; duplicate-key/depth/size failure; version conversion loss disclosure; stale revision/draft acceptance; and strict export failure. See [the test strategy](../testing/TEST_STRATEGY.md) and [format references](FORMATS.md).
