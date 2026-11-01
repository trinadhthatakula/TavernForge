# Local library backup and additive restore

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), local library backup/export requirement. Related: [architecture](ARCHITECTURE.md), [current status](../STATUS.md).

**Approved implementation contract, 1 November 2026.** The supervisor approved this bounded ZIP32/STORED-only proposal. Following recovery from the interrupted temporary checkout, the supervisor owns production archive code and the existing repository/Room bridge. The independent test owner restored archive tests and this document in the durable checkout. The supervisor owns builds, Git and AVD execution; the UI owner connects Android document pickers.

## Scope and archive contract

Version 1 is a local TavernForge library backup, not a character/lorebook interchange format. Its ZIP contains exactly `manifest.json` plus `blobs/<lowercase SHA-256>` entries. Entry names derive from content hashes, never display filenames, filesystem paths or project IDs. Identical bytes are stored once. Original/current JSON and immutable PNG/JPEG assets remain byte-exact; backup never parses and reserializes payloads.

The manifest identifies `TavernForgeLibraryBackup`, integer version 1, creation time, projects, artwork, compositions, lore links and local tags. Project records retain original/current hash and size, original media type, document revision and library summary. Artwork retains bytes, media/dimensions/animation metadata, source-artwork derivation ID and transformation policy. Composition revisions, original PNG/candidate provenance and embedded-lore revision/hash stamps remain distinct. Project and artwork IDs use separate fresh-ID maps during restore.

Only saved-project data is enumerated by the repository gateway. Credentials, keys, generation grants, preferences, request/draft journals, caches, signing material and unrelated private files have no archive entries. Native JSON unknown fields, extensions, future version markers, value types and number lexemes remain inside exact opaque payload bytes. Unknown backup versions/manifest fields are rejected rather than silently dropping backup metadata.

| Bound | Version 1 maximum |
| --- | --- |
| Complete archive / aggregate unique blob bytes | 256 MiB each; ZIP overhead must fit archive bound |
| ZIP entries | 4096 |
| Projects / artwork records | 1000 each |
| Original or current JSON blob | 4 MiB |
| Immutable artwork blob | 32 MiB |
| Manifest | 2 MiB |

ZIP uses STORED entries only. Deflate, encryption, ZIP64, data descriptors, comments, directories, extras, duplicate names, split archives and unknown paths are rejected. Local headers, central directory and end record must agree; truncated directories and trailing data fail. CRC-32 and SHA-256 are checked against bounded actual bytes. Aggregate and entry counts are checked before allocation and while staging. STORED-only removes decompression amplification; size/hash checks still protect oversized and corrupt input.

## Snapshot and restore boundaries

`LibraryArchiveGateway.withArchiveSnapshot` holds the repository's single-writer snapshot while export lazily reads one bounded blob at a time. Readers become unavailable after the callback. The exporter does not load the whole library into RAM or resolve arbitrary archive paths into application files. It validates every blob against its descriptor before completing the archive. Picker cancellation/output failure may leave a partial user-selected destination, which must be reported as unsuccessful; saved projects remain intact.

Restore preparation copies a bounded candidate ZIP to one uniquely owned private staging file. It validates ZIP layout, strict manifest, graph references, metadata bounds and every blob before returning `VerifiedLibraryArchive`. No library rows change during preparation/review. The handle exposes an immutable manifest and bounded hash-rechecked reads; no staging path escapes to UI. Closing attempts removal of only its owned staging file/directory. `cleanupAbandonedStaging()` is an optional startup hook and runs before preparation; it removes only recognizable inactive stages with no unexpected files, preserving live handles and unrelated directories. OS cleanup failure can leave an orphan for a later attempt.

After explicit acceptance, the repository validates project roles and restoration semantics, allocates fresh project/artwork IDs, stages all validated managed files, and inserts assets/projects/compositions/links/tags in one Room transaction using ABORT conflicts. Existing records are never replaced. Parent artwork precedes derived artwork. Missing dependencies, cycles, invalid JSON/restoration invariants or invalid assets fail the complete batch. PNG source/candidate provenance must identify the archived original JSON. Unknown character/lore versions remain exact unchanged drafts under existing repository rules.

Cancellation before commit leaves no visible partial library. Once atomic commit begins, it must complete or roll back. On ambiguous post-commit failure, cleanup rereads complete committed references before pruning and never removes a file merely because commit was assumed to fail. Physical orphan recovery remains with managed-file storage. Repeated accepted restores deliberately create new independent IDs. The gateway consumes each review handle once, and the service closes it in every outcome.

## Implementation and verification

Archive contracts, strict manifest/ZIP codec, lazy export and verified staging are implemented in `LibraryArchiveContracts.kt`, `ArchiveManifestCodec.kt`, `StoredZipArchive.kt` and `LibraryBackupService.kt`. The repository/Room bridge implements snapshot lifetime, validated staging and atomic insertion. The reconstructed independent test inventory contains **27 JVM tests and four Room instrumentation tests**.

| Test source | Tests | Coverage |
| --- | --- | --- |
| `LibraryBackupServiceTest` | 13 JVM | Exact opaque original/current bytes, revisions, provenance and tags; deduplicated blobs; excluded state; consume/close; corruption, limits and staged-read integrity; cancellation; caller-owned streams; empty archive; abandoned staging |
| `StoredZipArchiveTest` | 8 JVM | Unsupported ZIP features, header/directory mismatch, traversal/absolute/backslash/duplicate/unlisted names, strict JSON manifest/version/types, broken references/derivations, limits, filenames and separate ID namespaces |
| `ArchiveRepositoryTest` | 6 JVM | Exact restored content and metadata, additive repetition, future drafts, callback lifetime, role preflight, semantic draft rejection, consume-once, cancellation preserving shared files |
| `ArchiveRoomDeviceTest` | 4 Android | PNG/JPEG provenance and derivation/FKs, lore stamps and tags, reopen/repeated restore, staged hash/draft rejection, late metadata ABORT rollback across every inserted table, cancellation cleanup |

`StoredZipArchiveTest` reconstructs the earlier eight-method `ArchiveValidationTest` suite. Service/codec image fixtures deliberately contain opaque synthetic bytes; they establish archive fidelity, not valid image decoding. The Room suite uses actual synthetic PNG/JPEG images and isolated UUID-named database/file stores. JVM fake indexes do not establish Room transaction atomicity.

The supervisor reports the four restored Room tests passed as part of the raw **`OK (26 tests)`** isolated AVD suite in **0.985 seconds**. The reconstructed codec/service JVM sources are awaiting the supervisor's new complete run; prior checks of the lost temporary checkout are not substituted for this verification. Exact execution commands and consolidated evidence belong in [ongoing verification](../testing/AUTONOMOUS_VERIFICATION.md).

Source review identified and the supervisor fixed metadata remapping through the immutable `ProjectMetadata` constructor, character/lore role checks before staging, and escaped snapshot readers outliving their protected callback. Passing JVM or AVD repository tests do not establish real document-provider behavior, all lifecycle/accessibility paths, physical power-loss durability, interoperability, or community release readiness.
