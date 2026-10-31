# Manual JSON authoring and lore controls

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), sections 02–04 and milestone M4. Builds on [saved JSON editing/export](JSON_EDIT_EXPORT.md), [canonical architecture](ARCHITECTURE.md), [pinned format contracts](FORMATS.md), and the [approved hybrid UI](../DESIGN_DECISION.md). Implementation progress and executed evidence belong in [current status](../STATUS.md).

**Status: proposed implementation contract, 1 November 2026.** This document does not claim implemented manual templates, specialized lore operations, undo/redo, or new device verification. The supervisor assigns implementation ownership after reviewing these contracts. The existing JSON editor remains the integration baseline.

## Outcome and scope

Users can choose a character or lorebook template, author content offline with Basic/Advanced native controls, create and manage lore entries, review validation, save a local project, and export through the existing strict same-format pipeline. Import and manual authoring use the same preserved raw document, engine patches, repository, and export picker. No account, API key, network call, format conversion, dependency upgrade, or parallel persistence model is required.

This increment covers the six implemented JSON kinds: character V1/V2/V3, bare V2 CharacterBook, standalone V3 lorebook, and native SillyTavern World Info. Artwork/PNG, association between separate saved projects, conversion, Gemini, and durable recovery of unsaved edits continue as separate increments. Their absence must not block an artwork-free manually authored JSON project, nor be presented as completion of the charter's full combine/media workflow.

## Source evidence and creation defaults

The exact upstream revisions remain those in [FORMATS](FORMATS.md): V1/V2 `8083fb388615ccbce768e97cbbd49d2b3214632c`, V3 `f3a86af019fbd99f788f7a1155f399655b34ab35`, and SillyTavern `06bde939fb1e9c4c8d8641d810f0a916b5bce127`. Before planning, the cached V2, V3, and `public/scripts/world-info.js` bytes were checked with `shasum -a 256`; all three match the recorded hashes. This is source verification, not a new adapter test or live interoperability result.

| Selected template | Creation snapshot |
| --- | --- |
| Character V1 | Flat `name`, `description`, `personality`, `scenario`, `first_mes`, and `mes_example`, each an empty string. |
| Character V2 | Exact `chara_card_v2` / `2.0` envelope. Under `data`: the six inherited strings plus `creator_notes`, `system_prompt`, `post_history_instructions`, `creator`, `character_version`; `alternate_greetings: []`, `tags: []`, `extensions: {}`. |
| Character V3 | Exact `chara_card_v3` / `3.0` envelope with V3's inherited required fields and `group_only_greetings: []`. No fabricated artwork, source URLs, timestamps, or multilingual notes. |
| V2 CharacterBook | `{"extensions":{},"entries":[]}`. No invented envelope or mandatory name. |
| Standalone V3 lorebook | `{"spec":"lorebook_v3","data":{"extensions":{},"entries":[]}}`. The pinned standalone interface does not require `spec_version`; do not add one. |
| Native World Info | `{"entries":{}}`, retaining its keyed-object representation. The library title and suggested filename are separate metadata. |

Empty required strings are valid structure. Optional creative guidance is not a schema requirement. For V2/V3 characters, an embedded book is absent until the user chooses **Add lorebook**, which inserts the matching V2/V3 book with empty entries and extensions. V1 has no native embedded-book control; offer a separate lorebook project without implying association or conversion is implemented.

These are defaults for explicit new-document/new-entry operations only. Opening a field on an imported project never inserts them, changes `null` to missing, or repairs a wrong type. Malformed intermediate containers require explicit repair in the advanced editor before specialized child operations are enabled.

## Proposed engine APIs

Keep format semantics and patch generation in pure Kotlin `:format`; Android presentation owns labels, focus, navigation, and consent. Use existing `CharacterDocument`, `SourceFormat`, `JsonPatch`, `PatchResult`, diagnostics, and limits instead of a second DTO serializer. Proposed signatures are illustrative until the engine owner publishes the exact implementation contract:

```kotlin
fun CharacterCardEngine.createTemplate(format: SourceFormat): ParseResult

sealed interface LoreCommand {
    data object CreateEmbeddedBook : LoreCommand
    data class AddEntry(val after: EntryLocator?) : LoreCommand
    data class DuplicateEntry(val source: EntryLocator) : LoreCommand
    data class DeleteEntry(val source: EntryLocator) : LoreCommand
    data class ReorderEntries(val ordered: List<EntryLocator>) : LoreCommand
}

sealed interface EntryLocator {
    data class ArrayIndex(val index: Int) : EntryLocator
    data class NativeKey(val key: String) : EntryLocator
}

fun CharacterCardEngine.planLoreEdit(
    document: CharacterDocument,
    command: LoreCommand,
): LoreEditPlanResult
```

`createTemplate` rejects `UNKNOWN` and unsupported selections, builds a bounded snapshot, parses it through the normal detector, and requires a valid report for the selected format before returning success. It must not select a different version on failure. The new document starts at revision zero with the creation snapshot as its immutable original.

`LoreEditPlanResult.Success` should carry an owned immutable patch list, the resulting validation report, and a UI selection hint for the inserted/moved entry. Failure carries structured diagnostics and no partial edits. The planner chooses the book path from the document format, verifies the expected array/object shape, validates locator membership and indices, and runs the planned batch through the existing engine before handing it to the session. Locators are session references, not new exported identifiers. UI code must never accept arbitrary book paths or copy metadata into another format.

Apply each planned command atomically. Existing values use `Replace`, absent object fields use `Add`, and array insertion uses `Add` only intentionally. Reorder can use a single `Replace` of the entries container built from exact retained entry values; this avoids index-shift errors and thousands of patch operations. It must never serialize entries from a reduced form projection. Command failure leaves the current preview, pending patches, selection, and history unchanged.

## Entry creation, identity, and reorder

**V2/V3 arrays:** a new entry contains `keys: []`, `content: ""`, `extensions: {}`, `enabled: true`, and `insertion_order: 100`; V3 additionally contains `use_regex: false`. The value 100 is a documented TavernForge starting order, aligned with the pinned native default, not a mandated V2/V3 schema default. Optional IDs and other optional fields remain absent. Add appends, or inserts immediately after the explicitly selected entry. An empty key list receives authoring guidance, without falsely becoming a parse/schema error.

Duplicate retains every source entry value and unknown extension, with an explicit identity rule: an absent optional `id` remains absent; a present valid numeric V2/V3 `id` receives an unused nonnegative integer; a V3 string `id` receives an unused deterministic copy suffix. The review lists this replacement. Unsupported/wrong-type IDs must be explicitly repaired before this identity-changing duplicate operation; deletion and raw editing remain available. Existing IDs on other entries never change. Do not silently add a “copy” suffix to character content or names.

**Native World Info:** derive new-entry values from the pinned `newWorldInfoEntryDefinition` / `newWorldInfoEntryTemplate` (`world-info.js`, lines 4082–4129), excluding definition-only fields marked `excludeFromTemplate`. Constants at lines 96–97 establish depth 4 and group weight 100. Defaults include empty primary/secondary keys, comment/content, `selective: true`, logic 0, order 100, position 0, enabled via `disable: false`, probability 100, `useProbability: true`, and inherited-null overrides. Other template booleans/strings/arrays retain the pinned values. None of these optional defaults become mandatory on import.

New native entries get a fresh integer `uid` and matching decimal object key, consistent with `createWorldInfoEntry` at lines 4137–4148. The allocator must avoid both existing keys and existing UID values, including equivalent numeric values, without renumbering existing entries or coercing their IDs. Bound the search using the existing entry count/limits. If an unambiguous unused identifier cannot be obtained, reject the command with a diagnostic. Native duplication copies the exact source object and changes only its `uid` plus any explicitly reviewed display-order placement; it does not backfill absent optional defaults into the duplicate. This preservation policy is intentionally stricter than the upstream clone-then-template merge at lines 4019–4033.

**Reorder semantics are format-specific:**

- V2/V3 books: reorder the array values. Keep each entry's `id`, `insertion_order`, `priority`, and unknown values intact. Label it **Entry list order** and explain that insertion order is a separate setting.
- Native World Info: object member order is not a portable display-order contract. **Entry list order** updates only `displayIndex` for the explicitly ordered entries; it never changes object keys, `uid`, runtime `order`, or activation settings. This follows the pinned custom sort and drag behavior at lines 2171–2178 and 2656–2675. The proposed complete list order writes consecutive indices from zero in one reviewable command; missing display indices are explicitly added. Imported unknown numeric/null values are changed only on those displayed paths, with the diff visible. The user must see that this is a metadata change before save.
- A reorder command requires each current entry exactly once, with no duplicate, missing, or foreign locator. Sorting/search filtering is a view operation and does not write metadata. Disable persistent reorder while a filtered/incomplete list could hide entries; clear the filter before proceeding.
- Preserve any native `originalData` payload as an unknown value; do not silently run the upstream CharacterBook synchronization/conversion helper. No current strict exporter consumes it as an alternate source.

Delete removes only the chosen entry after an explicit confirmation naming that entry. Pending undo restores its exact raw values, location, missing fields and numeric lexemes. Delete does not compact IDs or insertion order. After an array mutation, remap session selection by the planner's result rather than retaining a stale numeric index.

## Basic and advanced controls

The new-project action opens a native template chooser showing the six precise output formats, a separate local title, and what the template contains. No version is chosen by inference from a title. Show a creation preview before **Create draft**. Cancel creates no repository row or managed file. The selected format remains fixed for that project.

Basic character mode exposes name, description, personality, scenario, first greeting, example dialogue, and supported creator/tags fields. Advanced mode exposes the additional prompt/creator fields, alternate and V3 group greetings, V3 metadata, and extensions. Required-but-empty fields show **Required by format; may be empty**. Creative completeness is a separate indicator and never blocks a strict export that passes schema validation.

Speech, vocabulary, mannerisms, appearance, clothing and visual-description helpers are writing guidance with an explicit destination field (such as description or personality) and insertion preview. They do not silently add nonstandard top-level JSON properties. Any future persistent structured guidance requires a documented app-metadata namespace and a separate contract review.

Lore controls use each adapter's actual names/types:

| Concern | V2/V3 book controls | Native World Info controls |
| --- | --- | --- |
| Identification/text | Optional name/comment, content | Comment/content; key and UID shown separately |
| Matching | `keys`, `secondary_keys`, `selective`, `constant`, `case_sensitive`; V3 `use_regex` | `key`, `keysecondary`, `selective`, `selectiveLogic`, `constant`, `caseSensitive`, `matchWholeWords` |
| Enabled state | `enabled` | Inverse presentation of `disable`, with the actual JSON change shown |
| Placement/budget | `insertion_order`, optional `position` before/after character, `priority` | `order`, numeric `position` 0–7, `depth`, `role`, probability and `useProbability` |
| Recursion and extra settings | Book `scan_depth`, `token_budget`, `recursive_scanning`; supported entry extensions only with a pinned mapping | `excludeRecursion`, `preventRecursion`, `delayUntilRecursion`, nullable scan/sticky/cooldown/delay and supported group/filter settings |
| Unknown/advanced values | Exact JSON-value editor and extensions | Exact JSON-value editor and extensions |

Optional booleans support **Absent**, true and false; fields with a native nullable override also support **Inherit (null)**. Absent and null are distinct, even if upstream runtime behavior sometimes agrees. A displayed upstream default is explanatory text until the user explicitly sets it. Numeric controls preserve the original number lexeme until edited and use bounded JSON-number parsing; a slider cannot silently round imported values. String-array editing preserves empty strings, commas and newlines inside each item; do not split/join on a delimiter as the authoritative representation.

Native position labels and selective-logic labels map to the exact enums in [FORMATS](FORMATS.md). Unsupported extension semantics remain raw-editable with clear scope; ordinary controls must not invent mappings. Global SillyTavern settings are not native per-world JSON settings. Cross-project associations remain separately scoped.

Use a paged/lazy entry list and a separate selected-entry editor, with accessible Move up/Move down actions as well as any drag affordance. Avoid materializing a control for every field of every entry. Retain compact single-destination navigation and expanded-width inspection, keep input in the ViewModel, and do not request focus merely because a pane becomes visible. Wrong-type fields retain exact JSON input and diagnostics.

## Session history and persistence

Reuse the saved-project edit session. Each explicit field Apply or lore command is one undoable patch batch with a human-readable label. Pending changes consist of ordered active command batches against one immutable persisted base/revision; Undo/Redo rebuilds the preview by applying the active flattened patch sequence to that same base. This avoids hand-written inverse operations losing a deleted subtree, explicit null, original ID, or numeric representation.

Proposed initial history limits: 100 command batches and 16 MiB of aggregate encoded patch payloads across undo and redo. Existing engine limits still apply: at most 1000 active patch operations, 4 MiB document bytes, and 64 MiB cumulative patch work. Check bounds before retaining another command or building a large preview. A limit rejects the new command while preserving its input, with **Save or discard changes to continue**; it never silently evicts unapplied input or drops an older pending command needed by save. This bounded first implementation can be optimized after evidence, without changing source semantics.

New accepted work after Undo clears the redo branch. Opening/typing a field is not itself an applied command; ask to apply or discard dirty input before an undo, redo, or structural entry mutation that would invalidate it. Pure selection/mode changes do not alter history. A successful save commits the active patch sequence using the original `expectedRevision`, reloads the new saved base, and clears session history. An empty active sequence is a no-op, not a new persisted revision. Conflicts and failures keep the session available for explicit recovery; they do not rebase or overwrite silently. The UI describes this as **Undo unsaved changes**, not historical revision recovery after restart.

Manual creation can call a domain `CreateManualProject` use case which obtains the engine template and passes its source through the existing `ProjectRepository.saveImported` storage path, then loads that project for the common editor. The method's current name is an internal detail; do not label a manual original as an imported file in the UI. A small repository naming refactor may be proposed if it helps, but no Room migration or separate manual storage is required merely to create templates. Original = selected template snapshot; current = subsequent authored revisions. Library title stays metadata, unless the user independently edits a native document name field.

Do not persist unsaved creation/undo content in saved-instance-state Bundles. Activity recreation retains the in-memory session; process death restores only the last committed draft and must be disclosed consistently. Export uses the existing revision-bound strict export preview and system CreateDocument flow; it does not save unsaved commands without acceptance.

## Assignments and acceptance

| Owner | Scope and owned paths | Output/acceptance |
| --- | --- | --- |
| Supervisor | Assignment/status, integration, all Gradle/Git/CI/device work | Confirm exact contracts/limits, integrate owners sequentially where paths overlap, record executed results and remaining media/association work. |
| Format implementer | `format/src/main` only, format build report | Publish exact template/lore-plan API first; validated six-template creation, identity-safe bounded atomic commands, no version conversion. |
| UI implementer | `app/**`, app unit tests and UI report | Native creation chooser, Basic/Advanced fields, selected-entry operations, bounded undo/redo, one common repository/edit/export flow; dependency files only if supervisor explicitly assigns them. |
| Independent tester | `format/src/test`, synthetic fixtures/manifest and testing report | Independent positive/negative/preservation/identity/history boundary evidence; coordinate any manifest owner before edits. |

Before claiming implementation complete, the supervisor's focused suites must cover all six templates through create → strict export → parse → field edit → export → parse. Include both empty-valid and populated manual examples, V3 empty group greetings, and no generated timestamps/assets. Verify schema-invalid drafts still save while strict export remains blocked.

Lore acceptance includes add/duplicate/delete/reorder for embedded V2/V3, standalone V2/V3 and native World Info; Unicode/escaped native keys; missing/null/unknown nested sentinels; unusual number lexemes; reused and string IDs; wrong-type containers; stale locators; reordered arrays with preserved insertion values; and native display order with unchanged runtime order. One failing command must not apply any earlier operation from its batch. ID allocation, complete-order validation, collection/document limits and command-work limits need boundary cases.

History acceptance includes delete → undo → exact semantic tree restoration, redo, branch invalidation, grouped changes, pending input protection, save/conflict behavior, and bounded failure without state loss. Verify a reordered selection cannot edit a different entry accidentally. Repository reopening must retain both creation-original and authored-current bytes/revision without any special manual serializer.

On an authorized device/AVD, exercise offline new-project creation for a character and lorebook, specialized array/keyed entry controls, saved restart/reopen, undo/redo, rotation, IME, narrow/expanded resize, large text and touch/accessibility alternatives to drag. Export through the real picker and independently compare parsed values with the accepted revision. AVD checks do not establish physical-device provider, accessibility or power-loss coverage. Pinned SillyTavern re-import/runtime checks remain a distinct interoperability gate.

## Assumptions and integration risks

The approved UI and toolchain remain in force. No new user design or license decision is required for these ordinary implementation choices. The supervisor may refine provisional API names/history limits before code starts, but source-version preservation, exact optional-field semantics and runtime/display-order separation are required.

The format owner's review must resolve any disagreement about entry identity or native optional semantics against the pinned source before exposing those specialized controls. Raw preservation is not proof that a runtime meaning is understood. Typed controls may ship incrementally only with the support matrix accurately recording what remains advanced/raw editing. Full M4 completion additionally depends on any charter authoring/association/artwork work still outstanding; this JSON increment alone must not be called the complete app.
