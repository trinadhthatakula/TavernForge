# Execution plan

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). Recovered 31 October 2026 after interruption. This is a plan, not a completion claim; [current status](../STATUS.md) records executed work.

## Immediate sequence

1. Preserve the requested dependency baseline and compile an isolated representative probe. The probe must actually exercise Compose compilation, Koin generation, Room 3 processing, serialization, and Android packaging; resolving plugin metadata alone is insufficient.
2. Review [architecture/contracts](ARCHITECTURE.md), [dependency evidence](TECH_STACK.md), and [upstream format contracts](FORMATS.md). Record accepted boundaries before production modules are created.
3. Start the pure format foundation: bounded JSON intake, source envelope, raw tree and typed projections, patch operations, diagnostics, and exact no-op preservation. Do not build an editor that reconstructs imported documents from a lossy DTO.
4. Complete strict V2 first with attributable positive/negative fixtures; stage V3 and standalone World Info separately. V1 remains an import-only candidate until the supervisor decides scope from fixture evidence.
5. Follow the approved hybrid: C’s library, A’s native controls/navigation, and B’s expanded inspector. The user choice is recorded in [the design decision](../DESIGN_DECISION.md); the compatibility and format gates still apply.

## Milestones and acceptance

| Milestone / owner | Depends on | Concrete work | Exit evidence |
| --- | --- | --- | --- |
| M0 bootstrap / Agent 2; Agent 1 evidence; Agent 4 checks | Compatibility discovery | Version catalog, wrapper checksum, minimal modules, CI configuration; exact compiler/processor probe | Reproducible local/CI command and result; generated Room/Koin output verified; documented unsupported combinations; no unresolved blocker hidden by a version substitution |
| M1 format foundation / Agent 2 + Agent 4 | M0, pinned sources | Parsing, per-version adapters, validation phases, raw preservation, explicit conversion plan, PNG reader/writer and World Info adapter | Positive and adversarial fixture suites; no-op and one-field edits preserve unknown fields; strict validation and round-trip/export tests pass per claimed version |
| M2 local foundation / Agent 2 + Agent 4 | M0, canonical contract; M1 for saved validated documents | Room 3 schema/repository, managed assets, DataStore preferences, Keystore credential store, atomic writes | Offline persistence/restart; schema export and migration tests; rollback on failed writes; no secrets or image blobs in project rows/exports; device evidence for Keystore and storage |
| M3 combine / Agent 2; approved UI support by Agent 3 | M1 + M2; design decision for UI | Independent JSON/PNG/World Info intake, inspection, association, validation/review, JSON and PNG export | All partial-input combinations work; missing artwork blocks only PNG export; scoped-storage and malformed-file device tests; export imports into pinned SillyTavern |
| M4 manual / Agent 2 + Agent 3 | M1 + M2; design decision | Basic/advanced character form, lore entry creation/duplicate/reorder, artwork attachment, completeness feedback | Offline creation and export through shared pipeline; required types/fields distinguished from creative suggestions; undo/draft state checks |
| M5 Gemini / Agent 2 + Agent 4 | M1 + M2; design decision | Key lifecycle, Ktor provider boundary, capability discovery, preview, independent permissions, structured drafts and review | Mock permission/follow-up/cancellation matrix; no call when denied; invalid/unaccepted output never replaces project; provider metadata shown without fabricated costs; device credential tests |
| M6 UI integration / Agent 2 + Agent 3 + Agent 4 | User design choice + M3/M4/M5 | Navigation, state restoration, accessibility, compact/expanded/foldable behavior | Three workflows usable end-to-end; keyboard/screen reader/text scale/insets/IME/rotation checks; no lost drafts on recreation |
| M7 hardening / Agent 4; fixes Agent 2 | M1–M6 | Large/malformed input budgets, security and offline regression, loss disclosure, failure/recovery | [Acceptance test matrix](../testing/TEST_STRATEGY.md) executed; no known critical data-loss/credential defects; unresolved risks explicitly listed |
| M8 FOSS release / Agent 0 | M7 + identity/license/distribution decisions | License/notices, supported-format matrix, backup/restore instructions, changelog, release pipeline | Supervisor release review with test artifacts; third-party fixture/source licensing accounted for; only verified support advertised |

M0→M1 and M0→M2 can overlap after shared contracts are accepted. M3 and M4 share the same format/repository foundation. M5 depends on the validation/review boundary, not on permission to implement AI UI. M6 cannot bypass the design decision. No calendar estimates are assigned before the probe and representative adapters reveal actual complexity.

## First implementation work packages

| ID | Inputs and output | Acceptance / owner |
| --- | --- | --- |
| BOOT-01 | Exact matrix → isolated dependency/compiler smoke project and report | Requested versions unchanged; actual generated code/compilation; Agent 2, reviewed by Agent 4 |
| MODEL-01 | Architecture contract → pure canonical source/document/diagnostic types | No Android or Compose dependency; explicit source vs export target; absent/null/type distinctions retained; Agent 2 |
| FORMAT-01 | Pinned V2 definition + source envelope → bounded parse and patch | Duplicate keys/oversize/depth handled before lossy tree conversion; original bytes on untouched export; sibling unknown fields survive known edits; Agent 2 + Agent 4 |
| FORMAT-02 | FORMAT-01 + V2 fixtures → strict V2 validation/serialization | Required field/type checks, embedded book constraints, JSON-pointer diagnostics; errors block strict export; Agent 2 + Agent 4 |
| STORE-01 | Accepted MODEL-01 → Room 3 project and managed-asset repository | Atomic source/patch/revision updates, failed-write recovery, offline read; processor schema check; Agent 2 |
| REVIEW-01 | Existing actual proposals → user decision record | **Resolved:** user selected C library + A native controls + B expanded inspector; [decision](../DESIGN_DECISION.md); Agent 0 |

Each assignment owns named paths; shared build/catalog files have one owner. Agents save incremental artifacts and include exact checks, assumptions, blockers, and handoff contracts. Do not mark a milestone complete just because its documentation exists.

## Open decisions

- Requested toolchain support exceptions and exact dependency pins after smoke results; any baseline change requires user approval.
- V1 import-only vs deferred; precise V3 optional features and standalone World Info settings covered by MVP.
- minSdk/targetSdk, default generation-history retention, license and hosting/distribution. UI direction is resolved by [the approved hybrid](../DESIGN_DECISION.md).
- Gemini model IDs are discovered at runtime where supported. Do not bake a currently available model into the product contract or infer image support from a text capability.
