# Three directions for TavernForge

Status: **the user approved C's library + A's native controls + B's expanded inspector**; see [the recorded decision](../DESIGN_DECISION.md). Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), sections 02–06 and 08. These files remain offline HTML review prototypes, not an Android implementation or evidence of supported formats.

Open [A — Material Expressive Native](proposal-a.html), [B — Studio Workspace](proposal-b.html), or [C — Hybrid Tavern](proposal-c.html). Each has the same Mira Vale project and four navigable views: library, character editor, import inspection, and Gemini request review. The sample draft review is also interactive. All data is fictional and changes reset when the page reloads. No network requests, file writes, or credentials are used.

## A — Material Expressive Native

**Idea:** a welcoming task hub with large, clearly labelled actions and a focused editor. Soft lavender surfaces, expressive shapes, spacious cards and a bottom navigation bar make the Android mental model familiar.

- **Information architecture:** Library is the home destination. Combine files, Build manually, and Generate with Gemini have equal prominence. A selected project opens a full editor; Settings would be a top-level destination in the app. Prototype navigation exposes the four review views for comparison.
- **Combine:** choose documents → inspect each input → select associations → review validation → choose an eligible export. A missing image does not block JSON export.
- **Manual:** start from a template → edit Basic fields → optionally reveal Advanced and lore → validate → export.
- **Gemini:** configure a key/model and independent permissions → concept/request preview → confirmed call → validated draft diff → accept into the same project.
- **Components:** large action cards; segmented Basic/Advanced control; contained fields; persistent validation summary; full-width primary action on compact screens. Destructive actions belong in menus with confirmation/undo.
- **Strengths:** easy onboarding, touch comfort, strong accessibility baseline, and the least navigation invention.
- **Weaknesses:** more screen changes for large editing sessions; rounded spacious content fits fewer fields at once.
- **Adaptive behavior:** compact single pane and bottom navigation; medium navigation rail; expanded library-detail/editor panes. Preserve selection, scroll position and unsaved drafts on size changes. Avoid fixed portrait assumptions.

## B — Studio Workspace / editor-first

**Idea:** a document workspace with a persistent project rail, central editor and validation inspector. Ink/slate surfaces, a restrained mint accent, tabular library and compact section navigation prioritize sustained authoring.

- **Information architecture:** Workspace contains projects, linked lorebooks and artwork; the current document remains central. Import is a source inspection panel, and generation is a review panel attached to the selected document rather than a separate content silo.
- **Combine:** use the Import workspace action → inspect source list and association panel → validate in inspector → export from the project toolbar.
- **Manual:** create a document from the same toolbar → edit sections with the project context visible → review inspector → export.
- **Gemini:** open request panel from the current document → explicitly choose sent fields → preview/confirm → inspect validated diff alongside the existing text → accept.
- **Components:** project table, left document navigation, field sections, outlined controls, right validation/asset inspector. The table becomes cards on compact width; actions never depend on hovering.
- **Strengths:** best multi-document and keyboard workflow; source/version/validation remain visible; efficient for long lorebooks.
- **Weaknesses:** highest learning and visual density; a desktop workspace translated directly to a phone would overwhelm. Compact layout needs its own hierarchy.
- **Adaptive behavior:** one pane with document switcher on compact; rail plus editor on medium; list/editor/inspector on expanded when width permits. Keyboard focus follows opened panes; Back restores the source context. Minimum 48 dp targets remain even where text density increases.

## C — Hybrid Tavern / character-library-first

**Idea:** a warm character library that opens into a calm, precise editor. Parchment tones, dark blue surfaces and small gold accents provide identity without putting decorative fantasy language into functional labels.

- **Information architecture:** Characters and Lorebooks are the library's two collections. Three visible creation actions feed the same project. A character page groups Story, Lore, Artwork and Export, with format details available when needed.
- **Combine:** visible Combine files action → inspect sources → attach to an existing/new character → validation review → export.
- **Manual:** visible Build manually action → template and character draft → edit Story/Lore → validate → export.
- **Gemini:** visible Generate with Gemini action → configure and preview → validated draft review → accept into a character; image remains a separate off-by-default permission.
- **Components:** portrait-inspired character cards, restrained serif display headings, ordinary sans-serif form text, collection filters, linked lore summary and a clearly worded validation panel.
- **Strengths:** memorable product identity and a useful library destination for repeat users; balances collection and editing.
- **Weaknesses:** artwork can dominate perceived completeness; must make artwork-free projects and standalone lorebooks equally usable. More custom theming to maintain.
- **Adaptive behavior:** one/two-column character cards on compact as space permits; rail plus collection on medium; collection/detail on expanded. Use real window width and hinge-aware panes rather than device-name detection.

## Shared behavior and accessibility contract

- One project identity and export pipeline for every entry workflow. The UI must retain source format/version and unknown fields; a pretty editor must not silently replace them.
- Distinguish **format errors**, **conversion warnings**, and **authoring suggestions**. The prototypes label demo projects as awaiting validation, so they do not imply real engine checks. Runtime labels for required fields come from the verified target adapter. Empty creative fields are not automatically format failures.
- Show Basic content first; Advanced exposes target-specific data and preserved extensions. Convenience fields such as speech style must disclose their actual export mapping when implemented.
- Strict export stays unavailable while errors remain; risky losses need an explicit, itemized acknowledgement. Missing artwork only affects image-dependent exports. Imported lore can remain standalone.
- Text, image and automatic follow-up permissions start off independently. Selecting a model never enables a permission. Show actual provider/model ID, purpose, sent data, image inclusion, request count and limits before sending. Models shown as unselected in these prototypes avoid pretending a live model was discovered.
- API usage displays provider-returned numbers only. Estimates are labelled; missing usage/cost stays unavailable. Key update/verify/delete belong in Settings; keep keys out of project content, history and exports.
- AI draft comparison and accept/reject are separate from making a request. Unaccepted output never replaces saved content. Permission revocation, cancellation and app restart must preserve that boundary.
- Every actionable touch target should be at least 48 × 48 dp; support text enlargement, screen readers, switch access, keyboard traversal, visible focus and reduced motion. Do not encode validation status through color alone. Use a 4.5:1 text contrast target and 3:1 control/non-text contrast target, then verify on actual themes/devices.
- Prototype pages reflow at 700 px and 1100 px as design demonstrations. These CSS values are **not Android breakpoint contracts**; implementation uses actual window size classes and available content area. Insets, IME avoidance, fold hinges and lifecycle/state restoration need Android validation later.

## Recommendation and decision

The recommended **C's character-library hierarchy with A's native controls and navigation**, plus B's optional expanded inspector, was explicitly approved by the user after reviewing the comparison choices. It gives TavernForge a recognizable home while keeping forms, consent and validation familiar. Implement the primary compact workflow before the optional expanded inspector.

The supervisor's comparison page preserves A/B/C references and lets the user propose changes. The approved direction is recorded in [DESIGN_DECISION.md](../DESIGN_DECISION.md). This choice does not override the dependency compatibility gate or establish format support.

## Prototype review checklist

Open each page at compact and expanded widths; navigate all four views; toggle Basic/Advanced; inspect independent permission controls; reveal the sample AI draft and reject/accept it; verify keyboard focus is visible and all local links resolve. Review prototypes have no production persistence, actual SAF import, validator, provider integration or export engine. Android accessibility/device tests remain pending.
