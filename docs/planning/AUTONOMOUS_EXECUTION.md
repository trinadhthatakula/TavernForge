# Autonomous completion plan

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). On 1 November 2026 the user authorized completing the full planned app without supervision, maintaining progress documentation, and keeping keys, secrets and local environment files out of the repository. The user also authorized testing on any existing AVD. This extends the earlier increment-by-increment work authorization; the approved hybrid design and preservation/security requirements remain in force.

## Execution and evidence

The supervisor owns integration, commits, CI, device/emulator verification and [status](../STATUS.md). Independent agents own bounded production/UI/test paths. Existing AVDs may be used without wiping their data or changing unrelated apps. Work continues through implementation and tests without waiting for the user. Actual provider and hardware behavior must still be distinguished from mocks, JVM tests and emulator evidence.

1. Finish [saved editing/export](JSON_EDIT_EXPORT.md): revisioned current snapshots, immutable originals, Room v1→v2 migration, native editor and strict JSON CreateDocument export.
2. Complete offline manual authoring: create supported character/lorebook documents, required vs optional guidance, lore entry creation/duplicate/reorder, current validation, undo/discard and export through the shared engine.
3. Complete import/combine: bounded PNG metadata reader/writer with pinned upstream behavior, independent artwork/lore intake, managed assets and project associations, preservation and conversion/loss review where supported. CHARX/APNG/media support must have exact tested boundaries; unsupported variants remain explicit errors rather than data loss.
4. Complete optional Gemini: Keystore-backed user-key lifecycle, provider/model capability discovery, Ktor boundary, separate default-off text/image/follow-up grants, exact request review, supplied usage or labeled estimates, structured untrusted drafts and explicit acceptance. Use synthetic mocks for CI; never require or fabricate a real key to complete local testing.
5. Finish adaptive/accessibility and lifecycle integration across the three workflows; add process-death recovery for unsaved drafts without serializing large content through activity saved-state bundles.
6. Execute hardening/release checklist: malformed/oversized formats, cancellation/offline behavior, migrations, provenance/license checks, security scans, regression and emulator workflow evidence. Produce a usable test build with accurate support and limitation documentation; public community readiness requires the corresponding evidence rather than a green compilation alone.

## Secret handling

Do not print environment contents, read local credential files into tools/prompts, embed keys in code/fixtures/prompts/exports, or commit `.env` files, signing keys or local configuration. User-entered Gemini keys belong only in Android Keystore-backed encrypted storage, with masked UI and no logging. CI uses scoped platform tokens by reference, never literal credential values. Check staged changes for accidental secrets before every push; do not publish private test documents or device captures.

## Remaining decisions

The supervisor may resolve routine implementation choices within the user's broad authorization and records them in scope/status documents. Preserve pinned versions unless an actual compatibility need justifies and documents a change. Dynamic model capability discovery avoids hardcoded availability claims. Release signing, distribution channels, unsupported format transformations and any feature requiring an unavailable external service must retain explicit evidence/limitations rather than invented success.
