# PNG cards, artwork and linked lorebooks

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), sections 03, 04 and 06. This is a **proposed staged contract**, prepared on 1 November 2026 while the supervisor verifies [JSON editing/export](JSON_EDIT_EXPORT.md). It extends the [canonical architecture](ARCHITECTURE.md), [pinned formats](FORMATS.md), [local library](PROJECT_LIBRARY.md) and [approved hybrid UI](../DESIGN_DECISION.md). No PNG support or passing implementation tests are claimed here. The supervisor records acceptance and execution in [current status](../STATUS.md).

## Deliverable and scope

Complete the charter's combine workflow: independently import character JSON, lorebook/World Info JSON, metadata-free PNG artwork, or PNG cards; inspect available content; explicitly select conflicting sources; save associations offline; export valid current character JSON, same-version PNG cards, and independent lorebook JSON. Inputs remain usable when another input is missing. All document edits use the existing raw-tree/patch/validation pipeline and original/current revision storage.

The work is split into gates below so basic PNG support can be tested before links and animation are advertised. CHARX, remote asset fetching, arbitrary media containers and cross-version card conversion are separate optional work. JPEG/WebP artwork may be a later extension: do not rename or silently re-encode it into PNG. The first complete PNG combine flow accepts PNG artwork and accurately reports other media as unsupported.

| Available input | Saved representation | Available outputs after validation |
| --- | --- | --- |
| Character JSON only | Existing document project | Same-format character JSON; PNG unavailable until artwork is chosen |
| Lorebook/World Info JSON only | Existing independent lorebook project | Same-format lore JSON; may later be linked to characters |
| Metadata-free PNG only | An artwork asset in the local library, without invented character JSON | Original artwork copy; may later be linked to a character |
| PNG card only | Original PNG asset + extracted original JSON project + extraction provenance + artwork link | Character JSON and PNG card; schema-invalid extracted JSON remains a draft |
| Character + artwork | Original JSON project with explicit primary artwork association | Character JSON and PNG card; adding artwork does not rewrite character fields |
| Character + lorebook | Independent documents linked by stable IDs | Both original-format JSON outputs; embedding only through a reviewed compatible-schema plan |
| Character + lorebook + artwork | The same document/asset/link records | All eligible outputs, with explicit choice of embedded versus separate lore |
| JSON character + PNG carrying another character | Two candidates shown with source filename, kind/name and diagnostics | User chooses the character source, or saves both separately; no last-import-wins replacement |

An artwork-only asset is not a fake `{}` character project. It is an `AssetReference` within the same Room/file repository and library. No second workflow-specific format/storage system and no new TavernForge interchange format are introduced.

## Authoritative evidence checked for this plan

The retrieved definitions are references, not copied production implementations. Hashes identify exact bytes observed on 1 November 2026. The existing [fixture manifest](../../format/fixtures/manifest.json) remains the source of truth for committed fixtures; new binary fixtures need their own provenance entries before support is claimed.

| Source | Pin / retrieved SHA-256 | Contract used |
| --- | --- | --- |
| [PNG specification, Third Edition](https://www.w3.org/TR/2025/REC-png-3-20250624/) | W3C Recommendation **24 June 2025**; `a3ac82bb9eb8664b93961a2a9ff6def3d70e3a9f63d92721143c1f391d04edec` | §§5.2–5.6 signature/chunks/CRC/order, §11 critical/text/APNG chunks, §14 editor preservation |
| [RFC 4648](https://www.rfc-editor.org/rfc/rfc4648.txt) | **October 2006**; `84e14418f795d503be5f34bf23ce4ebaa119e9ec7c9f667d8caeb111385b178f` | §§3–4 alphabet, padding and rejection of non-alphabet input |
| [V3 specification](https://github.com/kwaroran/character-card-spec-v3/blob/f3a86af019fbd99f788f7a1155f399655b34ab35/SPEC_V3.md) | `f3a86af019fbd99f788f7a1155f399655b34ab35`; `3c472a16eeda5d018837e90d30fce2816b0982f07f4dba14c8fcc89aa11fe76c` | UTF-8→base64 `tEXt`/`ccv3`, optional `chara` backfill, V3 precedence, APNG, asset references, lorebook envelope |
| [V2 specification](https://github.com/malfoyslastname/character-card-spec-v2/blob/8083fb388615ccbce768e97cbbd49d2b3214632c/spec_v2.md) | `8083fb388615ccbce768e97cbbd49d2b3214632c`; `e39e1da4f7a912c965af373846f4ac5c6cfb6207ea3fd5d542060905631acb46` | CharacterBook shape, preservation of unknown/optional fields, character-book versus global-world relationship |
| [SillyTavern card parser](https://github.com/SillyTavern/SillyTavern/blob/06bde939fb1e9c4c8d8641d810f0a916b5bce127/src/character-card-parser.js) | `06bde939fb1e9c4c8d8641d810f0a916b5bce127`; vendored file rehashed to `b74541cd54bb3fe3ab39b3e040f6dba824b263ed9344e4dcf98363e4e148e07c` | First case-insensitive `ccv3`, otherwise first `chara`; actual reader/writer behavior |
| [SillyTavern World Info](https://github.com/SillyTavern/SillyTavern/blob/06bde939fb1e9c4c8d8641d810f0a916b5bce127/public/scripts/world-info.js) | Same ST revision; `111c7f47945839e75b021e09bdbb112a54f0a9b7857d2b95435f573efc7cd9a5` | `convertCharacterBook` at lines 5617–5672 is a defaulting, ID-assigning conversion, not a lossless inverse or common storage shape |
| [SillyTavern package lock](https://github.com/SillyTavern/SillyTavern/blob/06bde939fb1e9c4c8d8641d810f0a916b5bce127/package-lock.json) | Same ST revision; `5ee4095a82d2b326e30290480b33529553cba60d2f418bc086ed3c08d874d888` | Reference reader dependencies `png-chunks-extract` **1.0.0**, `png-chunk-text` **1.0.0**, with integrity values in that lock |

Two upstream differences need explicit policy. PNG text keywords are normally case-sensitive, but the pinned SillyTavern reader folds card-keyword case; TavernForge recognizes only `chara`/`ccv3` case-insensitively for compatibility and emits lowercase canonical names. The ST writer's comment says V3 is removed, while its implementation actually relabels the supplied object to V3. That relabeling is not an adapter and must not be copied. V3's specification makes V2 backfill optional, so valid **ccv3-only** export needs no V3→V2 conversion.

## A. Bounded pure PNG inspection

Add pure Kotlin components under `format/.../png`, using JDK CRC/zlib primitives and the existing `CharacterCardEngine`. No Android, image loading, network or Compose dependency enters the engine. A single owned bounded byte buffer may back immutable chunk views; do not copy an image for every chunk/candidate. Android streams provider input into a bounded staged private file before inspection, and cancellation discards only its own staging file.

Proposed explicit internal defaults, **not upstream file-size restrictions**:

| Limit | Proposed default |
| --- | ---: |
| PNG input bytes / output bytes | 32 MiB / 40 MiB |
| Individual chunk payload / total chunk count | 16 MiB / 4,096 |
| Width or height / default-image pixels | 16,384 / 16,777,216 |
| Decoded bytes for one card / cumulative decoded card candidates | Existing JSON limit (4 MiB) / 8 MiB |
| Recognized card candidate count | 16 |
| Encoded card bytes | `4 * ceil(maxJsonBytes / 3)`; 5,592,408 with the default JSON limit |
| Inflated image scanline bytes, including filter bytes | 160 MiB, counted while streaming rather than allocated as one buffer |
| APNG frames / cumulative frame pixels, when enabled | 256 / 33,554,432, also constrained by the inflated-byte limit |

Use checked `Long` arithmetic for lengths, offsets, row sizes, output sizes and pixel products before allocation. Limits must be constructor values, enforced on actual streamed bytes as well as advertised sizes, and tested at boundaries. A limit failure retains the source/provider document and identifies the rejected budget without echoing user content.

Inspection phases are distinct:

1. Validate signature `89 50 4e 47 0d 0a 1a 0a`; walk chunks with unsigned lengths, reject lengths above 2³¹−1 or configured limits, truncation, invalid type bytes and CRC mismatch. CRC covers type+data, including empty chunks. Require one 13-byte IHDR first, legal color/bit-depth/method combinations, valid PLTE requirements/order, contiguous IDAT, one zero-length IEND last, and no trailing bytes. Unknown critical chunks block strict rewriting. A reserved third-letter bit is reported as unsupported, not reinterpreted.
2. Inventory every chunk in original order with type, offset, length and digest. Recognized metadata does not make an invalid image container valid. Preserve unrelated text, color/EXIF, unknown ancillary and asset chunks as opaque original bytes. Known structural/order violations block rewriting instead of being silently repaired.
3. Validate image-stream shape independently from card data: bounded zlib decode of concatenated IDAT, legal scanline filter-byte values, exact expected decompressed length, complete stream/checksum and no extra compressed stream. Handle non-interlaced and Adam7 row sizes explicitly; unsupported cases stay explicit until tested. This uses small streaming buffers and does not allocate a full bitmap. Android additionally performs bounded thumbnail decoding for display. Chunk/scanline checks are not a claim to implement every viewer/color-management behavior.
4. For `tEXt`, find the keyword separator within the PNG limit of 1–79 keyword bytes and enforce keyword character/spacing rules. Only recognized card keywords are base64-decoded. Validate RFC 4648 alphabet, padding, final pad bits and encoded/decoded limits before allocating decoded bytes; reject whitespace/junk that Node's permissive decoder would ignore. Then use the existing strict UTF-8/JSON parser and version-aware validator. Report base64, UTF-8, JSON syntax and card-schema errors separately.
5. `iTXt`/`zTXt` carrying card-like keywords are inventoried as unsupported conventions, not silently treated as `tEXt`. Do not inflate compressed text, follow URLs, execute embedded code or decode extra binary assets in this stage. Compressed-text bombs therefore have no decompression path; total/chunk bounds still apply.

## B. Selection, conflict review and PNG serialization

Every card candidate has a stable selection identity bound to PNG SHA-256, original chunk ordinal, keyword and decoded payload SHA-256 (where decoding succeeds). Never identify a candidate by display name or an unchecked list index after the source changes.

| Inventory | Selection behavior |
| --- | --- |
| No recognized metadata | Valid artwork, not a failed character import |
| One `ccv3` | Preferred V3 candidate; schema-invalid JSON can be explicitly saved as a draft |
| No `ccv3`, one `chara` | Preferred legacy candidate; classify its actual V1/V2 JSON, never infer version only from keyword |
| Both keyword families | Prefer `ccv3`, display retained alternatives and any differing fields; explicit review before resolving/removing alternatives |
| Repeated identical payloads | Select the first compatible candidate with a duplicate warning; retain every original chunk |
| Different payloads for the preferred keyword | Require explicit candidate selection; show which one pinned ST would read first |
| Malformed preferred `ccv3`, valid `chara` | No silent fallback. Retain both and expose an explicit recovery choice to import the legacy candidate |
| Keyword and actual JSON kind disagree | Compatibility diagnostic; do not label it conforming PNG metadata. Explicit JSON recovery may preserve the payload; corrected PNG emission requires a reviewed replacement plan |
| Future/unknown JSON kind | Preserve/inventory and optionally save unchanged JSON as an unsupported draft; strict PNG/JSON export stays blocked |

The initial exporter writes the current **source version**, not a new target version: V1/V2 use `chara`, V3 uses `ccv3`; lorebook documents alone do not become PNG character cards. Each advertised V1/V2/V3 PNG path needs its own fixture/edit/export/upstream checks. V1 `chara` is based on the pinned ST convention and existing V1 JSON adapter, not the old specification's ambiguous “EXIF” wording.

Export first obtains strict JSON bytes from `CharacterCardEngine.export`. A PNG plan then identifies every card chunk to remove/replace, the output keyword, changed metadata hashes and warnings. For an edited V3 card it emits one `ccv3` and removes stale `chara` backfill only after review; no backfill is generated. For a V1/V2 card it emits one `chara` and never manufactures `ccv3`. If chosen artwork contains another character, its old card metadata appears in the removal review. The retained original PNG and all alternative payloads remain locally available.

An unambiguous no-op export using the original image and exact selected JSON bytes returns the original PNG bytes. Conflicting candidates or explicitly selected non-preferred candidates require a replacement plan so the output cannot be read as a different character by pinned ST. An edited output copies every non-card chunk verbatim and in the same order, changing no IHDR/PLTE/IDAT/image bytes and inserting new `tEXt` before IEND. Newly emitted chunks have recomputed CRCs. Existing unrelated chunks retain their original complete byte sequence. Preserve unknown unsafe-to-copy ancillary chunks because only ancillary metadata changes; never move them relative to critical chunks. Unknown critical chunks block output, as required by PNG §14.

Plans and review receipts are bound to image digest, current document revision/hash, selected metadata identity, complete removal list and policy version. A public boolean `allowLoss=true` is insufficient. The serializer rechecks the bound inputs and refuses stale or incomplete acknowledgement. No bytes are sent to the document provider until strict validation, bounds and review succeed.

### Proposed pure API surface

These are proposed signatures for assignment; the implementer must publish concrete sealed result fields before UI/storage consumers start. All returned sources/bytes/collections are owned immutable snapshots; engine-created handles have non-public constructors.

```kotlin
class PngCardEngine(val limits: PngLimits, val cards: CharacterCardEngine) {
    fun inspect(bytes: ByteArray): PngInspectionResult
    fun select(container: PngContainer, choice: MetadataChoice): PngSelectionResult
    fun planExport(container: PngContainer, document: CharacterDocument,
                   selection: SelectedPngCard?): PngExportPlanResult
    fun export(plan: PngExportPlan, review: PngReviewReceipt): PngExportResult
}
```

`PngInspectionResult` is `Success(container, candidates, issues)` or `Failure(issues)`; successful artwork may have no cards or metadata errors. `PngContainer` owns original bytes, SHA-256, dimensions, animation inventory and chunk views. `MetadataChoice` is `Preferred` or `Explicit(candidateId)`; `PngSelectionResult` distinguishes `ArtworkOnly`, `Selected(document, provenance, issues)`, `NeedsChoice(candidates, issues)` and `Failure`. `PngExportResult` owns output bytes plus output SHA/selected format and preservation facts, or structured issues. JSON errors retain their existing paths; PNG diagnostics also carry phase/chunk ordinal/byte offset without conflating these with JSON pointers.

## C. Asset and association persistence

Keep the existing `projects` original/current JSON model intact. Add Room tables through a tested v2→v3 migration: immutable `artwork_assets`; one primary-artwork association per character; character↔lorebook links; and PNG-extraction provenance. An optional document `compositionRevision` starts at zero and advances on association changes. Existing content revisions remain content revisions. All imports, manual projects and future accepted AI drafts share these records.

Asset rows contain local UUID, original display filename, sniffed MIME/container kind, app-private file key, size, SHA-256, dimensions, animation status and timestamps. Large binary bytes never enter Room. Provenance binds an extracted JSON original to its original PNG asset and selected chunk identity; replacing displayed artwork cannot erase the source-container reference. The extracted JSON original remains `StoredProject.source`, and the original PNG remains an independent immutable asset. No provider URI is required after save, and private provider paths are not exported.

Stage files on the repository IO dispatcher with bounded streaming; verify actual bytes/hash, sync and atomically finalize before the Room transaction inserts assets, projects, provenance and links. A PNG-card save commits the extracted JSON and PNG references together; failure must not leave a visible half-import. Cleanup derives a complete reference set including original/current JSON, assets and provenance, and never guesses after DB failure. An ambiguous commit retains possible referenced files. No external document is modified or deleted.

Linking a lorebook preserves its independent project/revision; edits to a shared lorebook are visible through that link, while an already embedded snapshot is not silently refreshed. Duplicate characters share immutable artwork and linked lorebook IDs by default, with that behavior shown in the confirmation; current character JSON remains independently editable. Unlink removes only the relationship. Deleting a referenced lorebook/artwork is blocked with `IN_USE` until the user reviews detach consequences; do not silently cascade-delete other projects or their source provenance. Replacing artwork creates/selects another immutable asset rather than rewriting existing asset bytes.

Proposed repository extensions (same Room/file ownership; no separate store):

```kotlin
suspend fun saveArtwork(fileName: String, png: PngContainer): ArtworkSummary
suspend fun savePngCard(fileName: String, png: PngContainer,
                        selected: SelectedPngCard): StoredProject
suspend fun loadArtwork(assetId: String): StoredArtwork
suspend fun loadComposition(characterId: String): ProjectComposition
suspend fun setArtwork(characterId: String, expectedCompositionRevision: Long,
                       assetId: String?): ProjectComposition
suspend fun linkLorebook(characterId: String, expectedCompositionRevision: Long,
                         lorebookId: String): ProjectComposition
suspend fun unlinkLorebook(characterId: String, expectedCompositionRevision: Long,
                           lorebookId: String): ProjectComposition
suspend fun deleteArtwork(assetId: String)
```

`ProjectComposition` includes character identity, `ProjectVersion(documentRevision, compositionRevision)`, optional primary artwork, original PNG provenance and linked lorebook identities/revisions. Repository methods reverify engine handles/digests, character-versus-lorebook roles and foreign keys; UI-supplied metadata is not authority. Repeated link insertion is idempotent without duplicate rows. Relation mutation, detach review and delete use conditional revision checks inside a transaction. Export preparation captures an immutable version vector for character, associations and selected lorebooks; changes before approval require a fresh preview.

## D. Lorebook association and embedding

Association is always available for the implemented lorebook JSON kinds and does not change their raw schemas. Native World Info's keyed `entries` object is never relabeled or converted to a CharacterBook array just because it is linked to a character. Multiple linked books remain multiple projects; the single embedded `character_book` slot requires an explicit source choice. Exporting linked lorebooks separately satisfies the independent-input flow without a lossy implicit merge.

Direct embedding plans support bare V2 CharacterBook→V2 character and V3 standalone `data`→V3 character. Both preserve every raw book/entry value. If the standalone V3 envelope has unknown fields outside `data`, list them as unrepresentable in the embedded slot; retain the original standalone project and require exact omission review. Replacing an existing embedded book previews its removal, including unknown fields. The operation creates an explicit patch at `/data/character_book`, revalidates, commits against the character's expected content revision, and records the linked source revision/hash used for the snapshot.

```kotlin
class LorebookEmbeddingEngine(val cards: CharacterCardEngine) {
    fun plan(character: CharacterDocument, lorebook: CharacterDocument): EmbeddingPlanResult
    fun accept(plan: EmbeddingPlan, review: EmbeddingReviewReceipt): PatchResult
}
```

Plans are bound to both document revisions/hashes and contain the exact patch plus changes/omissions. A repository `applyEmbedding(characterId, expectedVersion, lorebookId, expectedLoreRevision, plan, review): StoredProject` rechecks those bindings and commits JSON/provenance atomically. It must not accept a stale linked lorebook revision or silently overwrite a new embedded book.

V1 has no specified embedded-book field; export linked books separately. Cross-version lorebook embedding and native World Info conversion return `CONVERSION_REQUIRED`, while linking/separate export continue to work. The pinned `convertCharacterBook` mutates missing IDs, supplies defaults, changes field names/types and retains `originalData`; it cannot be treated as a reversible serializer. A later conversion adapter needs a separate pinned mapping and itemized loss tests for positions, selective logic, nullable overrides, recursion/probability/extensions, duplicate IDs and unknown fields. No conversion is necessary for the initial complete combine flow described here.

## E. APNG and embedded-asset boundaries

Stage A detects `acTL`/`fcTL`/`fdAT` and preserves the original, but strict metadata rewriting returns `APNG_NOT_YET_VERIFIED` until the APNG gate passes. Do not decode a first frame and silently export a static PNG. The later preservation gate validates acTL length/order/frame count, fcTL length/dimensions/offsets/dispose/blend fields, fdAT minimum length, shared sequence numbers starting at zero, frame ordering and bounded frame zlib streams. Metadata-only output preserves all animation chunks, sequence numbers, IDAT/fdAT payloads and order byte-for-byte; it neither renumbers nor re-encodes animation. Thumbnail-only display must say it is a still preview when animation playback is unavailable.

V3 `chara-ext-asset_:{path}` text chunks, `__asset:` and `embeded://` references remain opaque in the initial adapter. On the original PNG base, non-card asset chunks are retained exactly. A plan using replacement artwork must report unresolved embedded references and block a supposedly self-contained PNG output if doing so would silently omit required source-container assets. It may still export the original-format JSON or keep the old PNG base. Do not invent an embedded-asset resolver, follow HTTP/data URLs, or mutate the V3 `assets` array when selecting artwork. In particular, an explicit remote main icon may mean a downstream V3 application uses that URL instead of the selected PNG image; surface the distinction and offer an explicit field edit, not a hidden `ccdefault:` rewrite.

CHARX is not a core-charter dependency. Detect ZIP/`.charx` inputs and explain the current unsupported-container scope; do not unpack arbitrary paths or treat its `card.json` as a file selected by the user. Full CHARX media resolution, ZIP path confinement, decompression budgets and container export need their own later contract. Original bytes may be retained as an opaque unsupported asset only if that capability is separately scoped and bounded.

## UI/export integration

Add independent import controls for character/lore JSON and PNG, with a preview showing actual sniffed kind, candidate metadata, diagnostics, available exports and missing inputs. Preserve the approved library/native controls/expanded inspector direction. Save/import selection, attach/detach and embedding are explicit actions. Draft replacement protection, busy-state guards, rotation, cancellation and stale-result handling extend the existing ViewModel rules.

Use Android OpenDocument/CreateDocument for scoped access. Prepare immutable validated output bytes and an exact review before launching the output picker. Each character JSON, PNG or lorebook output has a distinct truthful extension/MIME and selected source version. A multi-output operation records success/failure/cancellation per document; it cannot promise an atomic transaction across external providers, delete arbitrary failed destinations, or imply all outputs succeeded when the user cancels one picker. Artwork-free JSON export remains available. A JSON export does not bundle images or resolve external/embedded V3 asset references; show companion-file dependencies where relevant.

## Assignable stages and acceptance

| Stage / owner | Owned implementation scope | Required exit evidence |
| --- | --- | --- |
| PNG-1 / pure-engine implementer + independent tester | PNG limits, inventory, CRC/order/IHDR/IDAT stream checks, strict card decoding/selection | Metadata-free + V1/V2/V3 fixtures; all truncations/limits/bad CRC/base64/UTF-8/JSON/schema cases; conflicts and malformed preferred metadata; no Android imports |
| PNG-2 / same engine owner + tester | Same-version export plans, exact review receipts, metadata splice | Exact no-op bytes; one-field edit; all non-card chunks and IDAT identical; deterministic CRC/output bounds; stale receipt rejection; resulting PNG decodes and pinned ST reads the intended card |
| STORE-3 / storage owner + independent tester | v2→v3 asset/link/provenance migration and repository methods | Existing v1→v2→v3 upgrade with real saved edits; original/current/PNG preservation; staged-file failures; CAS, duplicate/shared links, detach/delete/IN_USE and complete-reference recovery |
| COMBINE-1 / UI owner + tester | Independent inputs, artwork library, candidate review, association and typed exports | Every input subset in the first table works offline; real pickers, cancellation, revoked access, disk-full/partial-output reporting, process restart and no lost saved sources |
| LORE-1 / format/storage owners + tester | Direct same-schema embedding plans and source-revision provenance | V2/V2 and V3/V3 unknown/null/number preservation; existing-book replacement review; outer-envelope omissions; stale source/character rejection; native/cross-version conversion explicitly blocked |
| APNG-1 / PNG owner + tester | Animation structure/stream validation and unchanged metadata rewriting | At least default-frame-in-animation and separate-default-image fixtures; multiple frames/IDAT/fdAT; invalid sequences/limits; all frame bytes and decoded reference frames unchanged. Only then enable APNG rewrite claims |
| Supervisor | Scope integration, fixture provenance, final device/upstream check and STATUS | Exact commands/counts/APK hash; pinned ST import of emitted V1/V2/V3 PNG and lore outputs; no unverified feature advertised; no community release inferred |

Tests must cover byte/offset overflow, zero/oversized dimensions, malformed PLTE/IHDR/IEND, discontinuous IDAT, truncated zlib and extra decompressed bytes, unknown critical versus safe/unsafe ancillary chunks, repeated/mixed-case/conflicting metadata, unsupported compressed text, arbitrary unrelated chunks, large/invalid JSON, wrong keyword-kind pairs, no-op fidelity and changed-image asset loss review. Test every configured bound at limit−1/limit/limit+1. Fuzz/property tests may mutate a small valid synthetic PNG, but an oracle must independently verify lengths/CRC/chunk equality rather than call the same parser twice.

Binary fixtures need original synthetic artwork authorship/license, generator and version, source/export steps, exact upstream revision and checksums; retain AGPL notices for any derived ST material. The current corpus has no true upstream-exported PNG fixture, so interoperability remains an acceptance task. Use the pinned ST checkout/parser and its locked reader dependencies for a reference read; also perform an application import to verify visible values. Node's permissive reader passing is not evidence that strict CRC/encoding/schema checks passed. APNG additionally needs an independently versioned reference decoder/frame comparison; byte preservation alone must not be described as tested playback.

## Decisions, assumptions and blockers

The above sizes, static-PNG-first order, standalone artwork-library representation, shared-link duplicate semantics and separate-export policy for native/cross-version lore are supervisor proposals ready for assignment, not new upstream rules. The user has authorized continued implementation; no new credential, network service or Android dependency is required for PNG/card parsing. Ordinary local workflow decisions can be finalized by the supervisor within that scope.

Before production edits, the supervisor must accept concrete sealed API fields, bind owners to paths and ensure migration/export tests are assigned. Open technical evidence: true upstream PNG fixtures, APNG reference decoder, exact receiving application behavior for V1/V2/V3 emissions, and provider/device checks. Until these gates pass, report the corresponding container capability as implemented-but-unverified or unsupported; do not silently narrow the support matrix. This planning task changed only this document and ran no Gradle, application, migration or PNG implementation tests.
