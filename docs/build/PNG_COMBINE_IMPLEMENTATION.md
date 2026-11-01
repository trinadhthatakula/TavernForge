# PNG, artwork and lore association implementation

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), especially §§02–04 and 06; design and source pins: [PNG/combine contract](../planning/PNG_COMBINE.md). Implemented in the isolated `codex/authoring-assets-ai` worktree on 1 November 2026. The supervisor records final build, CI and device results in [current status](../STATUS.md). This document is implementation evidence, not a claim that every planned PNG/interoperability gate passed.

## Pure engine

[`PngCardEngine`](../../format/src/main/kotlin/com/valhalla/tavernforge/format/png/PngCardEngine.kt) handles static PNG signature, unsigned bounds, CRC, chunk type/order, IHDR color/depth/methods, PLTE requirements and contiguous IDAT/IEND. Streaming zlib validation checks exact scanline lengths, legal filters, checksum/completion and extra compressed data, including Adam7 pass sizes. It allocates no bitmap. Common ancillary chunks receive ordering, singleton, length and palette checks. Opaque ancillary bytes remain preserved; this is not a complete color-profile, EXIF, compressed-text, all ancillary semantics or animation decoder.

Card `tEXt` candidates use strict canonical RFC 4648 base64 and the existing bounded UTF-8/JSON/schema engine. Candidate identity includes PNG hash, chunk ordinal and decoded JSON hash. Mixed-case card keywords follow pinned SillyTavern reader behavior. The first `ccv3` family is preferred; differing duplicates, malformed preferred content and keyword/version mismatches require explicit selection. A metadata-free PNG is an artwork asset. Schema-invalid character JSON can be saved as a draft. Malformed JSON cannot become a character project.

`planExport` obtains strict current character JSON and emits the same source version: V1/V2 `chara`, V3 `ccv3`. It does not relabel versions or generate a V2 compatibility payload. Exact unambiguous no-op export retains the complete PNG; edited output preserves every non-card chunk's complete bytes/order and inserts a new CRC-correct `tEXt` before IEND. Exact review strings bind source/candidate identity, target JSON hash, document revision and policy. A plan owns immutable output bytes; later edits require preparation of a new plan by the caller.

Strict PNG output is blocked for unknown critical chunks, APNG and unresolved `embeded://` or `__asset:` references. Those source containers and JSON remain retained. This deliberately tightens the staged plan: preserving an opaque source-container chunk alone does not establish that a required embedded asset exists or decodes. Remote/data URI values remain unchanged and are never fetched. JSON export remains separate from image bundling. Full embedded-asset resolution and CHARX are unsupported.

Default budgets: 32 MiB input, 40 MiB output, 16 MiB chunk, 4,096 chunks, 16,384 per dimension, 16,777,216 pixels, 16 card candidates, 4 MiB per decoded card (existing JSON limit), 8 MiB aggregate candidate JSON, and 160 MiB inflated scanlines. Values are constructor-configurable; enforcement precedes bounded decoding/allocation. They are application policy, not upstream restrictions.

## Persistence and associations

[`ArtworkModels`](../../core/model/src/main/kotlin/com/valhalla/tavernforge/core/model/ArtworkModels.kt) defines real standalone artwork and composition projections. [`CompositionRepository`](../../data/src/main/kotlin/com/valhalla/tavernforge/data/CompositionRepository.kt) extends the existing project repository contract. Production methods run under the same repository writer mutex and IO dispatcher as JSON saves/edits; all workflows retain one Room database and file ownership system.

[`CompositionDatabase`](../../data/src/main/kotlin/com/valhalla/tavernforge/data/CompositionDatabase.kt) adds schema v3 tables `artwork_assets`, `project_compositions` and `lore_links`. `MIGRATION_2_3` creates these tables/indexes without modifying existing original/current JSON rows or files; `MIGRATION_1_2` remains registered. Root owns generated Room schema verification. The subsequent additive v4 migration is described below.

PNG files are immutable SHA-256-addressed private `.png` files, using the same checked/synced managed-file implementation with an explicit extension/media type and PNG byte budget. The v4 extension adds equally immutable original `.jpg` assets, selected by sniffed MIME rather than provider filename. Incoming provider names are metadata only. Artwork-only saves never invent `{}` characters. PNG-card import stages both files before a Room transaction creates the artwork, extracted JSON project, primary association and source candidate provenance together. A reported late commit failure triggers a complete reference reread; an ambiguous/incomplete read leaves possible references intact. Recovery reads both full JSON and artwork reference sets before pruning either store.

Composition mutation has a separate conditional revision. One primary artwork can be selected or detached; replacing it does not erase the immutable source PNG or extraction provenance. Multiple characters can link the same independently editable lorebook. Repeated links are idempotent. Duplicate characters share artwork/source references and lorebook links while their JSON edits remain independent. Foreign keys cascade only outgoing links on character deletion; deleting artwork or a referenced lorebook returns `IN_USE` until links are reviewed/detached. Removing displayed artwork does not erase PNG-source provenance.

## Same-schema embedding

[`LorebookEmbeddingEngine`](../../format/src/main/kotlin/com/valhalla/tavernforge/format/combine/LorebookEmbeddingEngine.kt) plans direct V2 CharacterBook→V2 character and V3 lorebook `data`→V3 character snapshots. It preserves the raw book tree and numeric lexemes. Native World Info, V1 and cross-version combinations return `CONVERSION_REQUIRED`; linking and separate JSON export remain available.

Replacing a current embedded book requires exact review bound to both content hashes/revisions. Unknown outer V3 lorebook fields are listed as omitted from the embedded slot while the standalone project remains intact. Repository acceptance reloads both documents, verifies the plan and composition revision, stages the current JSON, and transactionally commits the new character revision with the link's embedded-source revision/hash. A later edit to the linked book does not silently refresh the embedded snapshot. Current implementation stores snapshot provenance on the link; unlinking removes that link/provenance record but leaves the embedded JSON unchanged. Durable embedding-history records independent of links are not yet implemented.

## JPEG originals and reviewed derived PNGs (v4)

The supervisor additionally authorized JPEG artwork so accepted generated JPEGs can join the same library without silent conversion. [`ManagedArtworkFiles`](../../data/src/main/kotlin/com/valhalla/tavernforge/data/ManagedArtworkFiles.kt) keeps PNG and JPEG originals in the same private asset directory with independent validated suffixes. `ArtworkSummary.mediaType` determines truthful export MIME; `.sourceArtworkId` and `.transformation` describe a derived asset without modifying its source.

[`AndroidArtworkCodec`](../../data/src/main/kotlin/com/valhalla/tavernforge/data/ArtworkCodec.kt) checks JPEG byte limits, SOI/EOI markers, MIME, bounds, and a complete platform pixel decode before storage. Budgets are 32 MiB, 16,384 per dimension and 16,777,216 pixels. This is tested platform decoding, not a new pure JPEG bitstream validator; deliberately recovered malformed JPEG behavior remains platform-dependent. JPEG metadata is not parsed during plain original import. No image is silently re-encoded then.

`prepareArtworkPngConversion(assetId)` creates an immutable proposed PNG, and `acceptArtworkPngConversion(plan, exactReviewItems)` persists it only after review. The plan binds original identity/hash, output hash/dimensions and policy. Conversion uses **pinned AndroidX ExifInterface 1.4.2**, applies EXIF orientation, decodes to sRGB pixels and writes bounded PNG. The review explicitly discloses pixel re-encoding and omission of JPEG EXIF/ICC/XMP/IPTC/comments/thumbnails from the PNG. The original JPEG, including those bytes, remains separately saved/exportable. A self-referencing foreign key blocks deletion while a derived PNG still refers to it. There is no implicit character attachment switch or native JSON asset-array rewrite.

The v4 migration adds `mediaType` defaulting to `image/png`, nullable original-asset reference and transformation columns without rewriting old artwork. It retains every PNG/source-composition reference. Root added the cached ExifInterface dependency after lint identified older platform implementation security concerns; the adapter uses AndroidX rather than suppressing the warning. WebP and other artwork originals remain unsupported.

## Local tags and filtering (v4)

[`LibraryMetadata`](../../core/model/src/main/kotlin/com/valhalla/tavernforge/core/model/LibraryMetadata.kt) defines library entries, queries and independent tag revisions. [`LibraryMetadataDatabase`](../../data/src/main/kotlin/com/valhalla/tavernforge/data/LibraryMetadataDatabase.kt) adds `project_metadata` with a cascading project foreign key. `setProjectTags` normalizes surrounding whitespace and accepts at most 32 distinct tags of 1–64 characters, rejecting control characters and case-insensitive duplicates. It commits against the expected metadata revision; JSON content and its revision do not change.

`observeLibrary(query)` composes title/filename/tag search, source-format selection, draft/valid filtering, all-selected-tags matching and recent/title/oldest sorting. Empty format/tag selections mean all. Existing projects start with empty local tags at revision zero. Duplicate copies local tags transactionally into an independent metadata record; deleting a project removes only its own local metadata. Native character `tags` fields remain separate explicit JSON edits.

## Tests and evidence boundaries

New tests owned by this increment:

- `PngCardEngineTest`: 15 JVM tests for independent ImageIO decode, every byte truncation, CRC, V1/V2/V3 no-op and edited export, unchanged image/ancillary chunks, unknown values, duplicate/version conflicts, exact receipts, malformed preferred metadata, encoding/resource/scanline failures, APNG/critical/embedded-reference gates and a one-pixel Adam7 case.
- `LorebookEmbeddingTest`: five JVM tests for raw V2/V3 preservation, unknown-envelope omission review, replacement review, invalid source books and blocked native/cross-version conversion.
- `ArtworkRepositoryTest`: nine JVM tests for independent artwork, source provenance, staged/ambiguous failures, incomplete-reference cleanup, idempotent/stale/shared links, independent duplicates and embedding revision checks. Its in-memory index is a contract double, not Room evidence.
- `PngReferenceArtifactsTest`: three JVM tests against two exact pinned-upstream writer PNG fixtures, including explicit recovery of its invalid V2→V3 backfill, plus six real engine output artifacts for the independent Node reader.
- `LibraryMetadataTest`: four JVM tests for local tag/source isolation, CAS/normalization/limits, composed filters/sorts and corrupt tag data.
- `ManagedArtworkFilesTest`: two JVM tests for mixed PNG/JPEG immutable files and complete-reference cleanup safeguards.
- `JpegMetadataDeviceTest`: three instrumentation tests for exact JPEG/EXIF originals, reviewed orientation conversion and omission/FK behavior, corrupt JPEG rejection, and frozen schema3 PNG/provenance→v4 migration with independent tag duplication/filtering.
- `ArtworkDeviceTest`: three isolated instrumentation tests covering frozen v2 edited-state migration, PNG/shared-reference close/reopen and foreign keys, and atomic embedding provenance/stale-composition rollback. Existing frozen-v1 instrumentation exercises the full 1→2→3 path when the supervisor runs it.

Synthetic PNGs are original 1×1 RGBA test artwork generated in test code using specification primitives, JDK CRC32 and DeflaterOutputStream, under the project's GPL-3.0-or-later license. These unit-test images are not upstream-exported PNG fixtures. The separate reference corpus now includes two exact pinned-upstream writer outputs, described below. Character/lore JSON tests reuse the existing attributed corpus; the rich V3 preservation derivative explicitly removes its CHARX-only asset, while a separate test proves unresolved asset rejection. No downloaded character or private user content is included.

The implementation agent did not run Gradle or device commands. The supervisor owns those exact results and generated schemas. The agent ran `git diff --check`, local document-link checks, and the following independent reference workflow:

```sh
npm ci --ignore-scripts --no-audit --no-fund --fetch-retries=1 --fetch-timeout=30000 --prefix format/fixtures/reference/png
node format/fixtures/reference/png/check.mjs --generate-upstream
python3 format/fixtures/verify_manifest.py
node format/fixtures/reference/png/check.mjs format/build/png-reference
```

Results: four exact reference-only npm packages installed; two unmodified pinned-writer fixtures generated; **15 pinned files** pass integrity audit; **six actual TavernForge V1/V2/V3 no-op/edited PNG outputs pass** exact upstream-reader JSON comparison, independent CRC/chunk checks, same-version keyword checks, unchanged non-card chunks and exact no-op assertions on Node **v26.13.0**. The supervisor generated those actual engine outputs using `PngReferenceArtifactsTest`. Full pins, SRI values, generation provenance and reproduction instructions are in [the reference harness](../../format/fixtures/reference/png/README.md). The harness copies unmodified source bytes into an isolated temporary ES-module context and never patches the upstream reader.

Still required before broad support claims: receiver application UI imports (the pinned reader and writer-module fixtures now have separate evidence); independent multi-pass/multi-size Adam7 references; exhaustive configurable-limit edges/fuzzing; full ancillary/compressed-text semantics where advertised; APNG validation and frame oracle; embedded asset resolver; wider real provider/cancellation/disk-full and UI lifecycle checks. No PNG support is certified solely by structural parsing or a mock repository test.

## Reimportable export budget correction

The exporter now caps emitted PNG bytes at `min(maxOutputBytes, maxBytes)`, including exact no-op output. This keeps the configured 32 MiB input/storage limits unchanged and prevents metadata insertion from creating a successfully exported file that TavernForge cannot import again. The separate output cap can still be stricter. `PngExportBudgetTest` covers metadata growth past the input cap, exact/under-cap round trips, stricter output budgets and no-op plans passed to an engine with tighter limits. The implementation agent inspected the changes but did not run Gradle; the supervisor owns execution evidence.
