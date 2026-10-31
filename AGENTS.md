# TavernForge agent instructions

## Read first

The [project kickoff charter](TavernForge_Project_Kickoff.html) is the product and engineering baseline. Read it before changing architecture, contracts, dependencies, or workflows. Read [current status](docs/STATUS.md) and relevant discovery documents before beginning an assigned task. If requirements conflict, report the conflict to the supervisor instead of silently choosing a different baseline.

## Project

TavernForge (`com.valhalla.tavernforge`) is an offline-first, account-free FOSS Android tool for SillyTavern characters and World Info/lorebooks. Import/combine, manual creation, and optional Gemini generation are three first-class workflows that share one canonical project model, format engine, validation pipeline, and persistence system.

## Non-negotiable engineering rules

- Pin authoritative upstream sources and exact revisions for format adapters. Separate parsing, validation, migration, and serialization. Never advertise support before fixture, edit, round-trip, and export checks pass.
- Preserve unknown fields, extensions, value types, and source format information. Never silently convert versions or drop data. Block strict export on errors and disclose risky transformations for review.
- Keep the format engine independent of Android and Compose. Use MVVM/unidirectional data flow, repositories, Room 3 for saved projects, DataStore for non-secret preferences, and app-private files for large assets.
- Store API keys with Android Keystore-backed encryption. Never place secrets in source, logs, ordinary preferences, test fixtures, exports, prompts, or commits.
- Gemini text, image, and automatic follow-up permissions are independent and default off. Preview provider/model/purpose/data/limits before requests; report only supplied usage or clearly labeled estimates. Generated data is an untrusted draft and requires validation and explicit acceptance before saving.
- All file access follows scoped-storage/document-picker rules. Manual and import workflows must work without network access or an API key.
- The requested baseline is AGP 9.4.1, Gradle 9.8.1, Kotlin 2.4.21, Compose, explicitly pinned Material 3 expressive/alpha, Koin's Kotlin compiler plugin (not Koin KSP), and Room 3. Verify the complete compatibility matrix before implementation. Report conflicts and obtain approval for baseline changes.

## Agent workflow and ownership

The supervisor coordinates assignments, resolves conflicts, integrates artifacts, and maintains status. Each task must define scope, inputs, owned paths, outputs, acceptance criteria, assumptions, and blockers. One owner edits a file/module at a time; do not revert another agent's changes. Parallelize independent tasks only within available agent capacity.

1. **Agent 1 — planning:** scope, contracts, exact dependency matrix, upstream format pins, task graph.
2. **Agent 3 — design:** three distinct proposals: A Material Expressive Native, B Studio Workspace, C Hybrid Tavern. Include comparable previews, workflows, adaptive/accessibility considerations, trade-offs, and a recommendation.
3. **Agent 4 — testing:** fixture provenance, test strategy, incremental regression and failure-path evidence.
4. **Agent 2 — implementation:** start agreed non-UI foundations after contracts and dependency checks permit. Implement final UI only after the design decision.
5. **Agent 0 — supervisor:** assemble the comparison HTML from Agent 3's actual proposals and present it for the user's A/B/C or hybrid decision.

**Mandatory design gate:** no final Android UI implementation or locked UI direction before the user reviews the comparison and selects an approach. Review prototypes are allowed. A recommendation is not approval.

## Verification and reporting

- Run focused checks appropriate to each change and record exact commands/results. Do not report a plan, source inspection, or mock as a passing implementation test.
- Distinguish proposed, implemented, verified, and awaiting device verification. JVM tests alone do not prove Android Keystore, document picker, accessibility, or lifecycle behavior.
- Treat malformed/oversized JSON and PNG, preservation during edits, cancellation, offline operation, and permission isolation as core test cases.
- Use a version catalog and checked-in Gradle wrapper once the matrix is verified. Never invent build commands for modules that do not exist yet.
- Keep the kickoff HTML intact. Every derivative project document must link back to it. Record new decisions and unresolved work in `docs/STATUS.md`.
- Do not publish, create remote repositories, choose a license, or claim release readiness without the relevant decision/evidence. The public name/package and distribution choices are still open.

## Repository state

This file was initialized from the kickoff-only project. Consult `docs/STATUS.md` for current modules, assignments, runnable checks, decisions, and implementation progress.
