# TavernForge test-build guide

Baseline: [project kickoff charter](../TavernForge_Project_Kickoff.html). [Current status](STATUS.md) and [verification evidence](testing/AUTONOMOUS_VERIFICATION.md) identify the exact tested build and remaining limits. This guide covers the 0.5 feature branch as it is integrated.

## Your local library

TavernForge works without an account. Imports and manual editing need no network or Gemini key. Saved JSON, original images and local metadata stay in the app's private storage. Keep a library backup before uninstalling the app or clearing its data; Android automatic backup and device transfer are disabled.

Search matches library titles, filenames and local tags. Sort by recent, title or oldest. Expand Filters to select formats, validation state or tags; clear filters to return to the full library. Renaming a library title does not rename the character inside its JSON. Local tags also remain separate from the character's own `tags` field.

## Create manually

1. Choose **Create manually**, select a source format and enter a library title.
2. Review the template and create the local draft. Supported templates are Character Card V1, V2, V3, V2 CharacterBook, standalone V3 lorebook and SillyTavern World Info.
3. Edit fields in Basic mode, or use Advanced mode for exact JSON values. Required format fields are identified separately from creative guidance. Empty required text or arrays can be valid; a required array still needs to exist.
4. Apply each field change, review validation and choose **Save changes**. Undo/Redo applies to the current unsaved editing session.
5. Export the saved revision. Validation errors block strict export until fixed. Saving an invalid draft is allowed so you can finish it later.

Lorebook controls add, duplicate, delete and reorder entries. Reordering the array does not silently rewrite insertion priorities or IDs. The exact-value editor handles additional supported settings and preserved extensions.

## Import and combine

**Inspect JSON** opens Android's document picker. Inspect the detected format and its diagnostics before saving. V1, V2 and V3 characters use their own rules. TavernForge preserves unknown fields, null values, number spellings and the source version. It does not silently upgrade a V2 card to V3 or insert a missing field.

**PNG cards and artwork** imports PNG or JPEG independently. A PNG can contain more than one card payload. When metadata conflicts or the preferred payload is invalid, select the intended candidate explicitly. Its original PNG and extraction provenance remain saved. An image without card metadata can be kept as artwork without inventing a character document.

Open a character's artwork/lorebook controls to attach an image or link a separately saved lorebook. Linking keeps the book independently editable. Same-version embedding copies a reviewed snapshot into the card; editing the linked book later does not silently update that snapshot. V2 books embed in V2 cards, and V3 books in V3 cards. Other combinations can remain linked and be exported separately.

JPEG originals remain byte-exact and export as JPEG. Creating a PNG from JPEG requires a conversion review: orientation is applied, pixels are encoded as PNG, and JPEG metadata is omitted from the derived PNG. The original JPEG remains available. Attach the derived image explicitly when it is the desired card artwork.

PNG export keeps the card's source version and unrelated PNG chunks. Replacing conflicting card metadata requires review. Static PNG export is supported within the documented size and image limits. APNG export, CHARX bundles and unresolved embedded assets are blocked; exporting JSON does not bundle missing media. See [PNG implementation and limits](build/PNG_COMBINE_IMPLEMENTATION.md).

## Recover unfinished edits

During a saved project's editing session, TavernForge writes a private recovery snapshot shortly after changes. The latest keystrokes can be newer than that snapshot. After interruption, **Recover for review** checks the original file and saved revision before restoring pending changes or unfinished field input. Recovery does not save changes automatically.

An unfinished value still requires Apply or Discard. Recovered applied changes form one undoable batch. If another operation changed or deleted the base project, recovery refuses to overwrite it. Discard the obsolete recovery to reopen the current saved project.

## Optional Gemini

Open the Gemini/key settings screen and enter your own Google AI Studio key. The field is masked, excluded from saved state and protected from screen capture. Store/update/delete actions are local; they do not validate the key with Google. The encrypted key is kept using Android Keystore. Deleting it locally does not revoke it at Google.

Text, image and automatic-follow-up permissions are independent and start off in each app process. Model discovery is a separately reviewed metadata request. The available choices are the intersection of Google's returned models and the app's pinned capability evidence. An unfamiliar model name is not proof of image output support.

Choose a purpose, model, concept and limits. Review the exact provider, model, purpose and request data before sending. Including project context sends the displayed current JSON; leaving it off avoids that context. Token counting is another reviewed provider call. This version still requires approval for each call and performs no automatic retries.

Generated text/images are drafts. Review validation and the displayed changes, then explicitly accept or reject. Accepting new JSON creates a local draft; field/lore changes are applied against the reviewed project revision. Changed projects require a fresh review. Artwork is saved independently and is not attached automatically.

Usage shows only provider-supplied counts. Missing counts remain unknown, and no exact charge is invented. Google's account terms, quotas and pricing apply. If a request is interrupted after dispatch, the provider may have processed it; retrying is a separate decision. Deterministic tests use synthetic responses; live provider compatibility and account access have separate verification limits.

## Backup and restore

Library backup uses Android's document picker to write a TavernForge ZIP. It contains saved original/current JSON, revisions, artwork, associations and local tags. Keys, permissions, preferences and edit-recovery journals are excluded. The backup is not encrypted, so choose a storage location appropriate for your project content.

Restore validates the full archive and displays its contents before acceptance. It adds new copies with fresh IDs and preserves links between those copies. Existing projects are not replaced. Restoring the same archive twice deliberately creates two sets of copies. Cancelling review leaves the library unchanged.

Archives are limited to 256 MiB, 1,000 projects, 1,000 artwork records and 4,096 ZIP entries. Per-file limits are 4 MiB JSON and 32 MiB artwork. Only TavernForge's versioned archive profile is supported. A failed picker write may leave a partial output file; it is not reported as a successful backup.

## Reporting test results

Record the app version, Android version, operation, expected result and visible error. Use a minimal synthetic file when possible. Never include an API key, private character content or unredacted logs in a public issue. Security reports follow [SECURITY.md](../SECURITY.md).
