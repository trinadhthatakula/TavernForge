# Local library UI increment

Baseline: [TavernForge kickoff charter](../../TavernForge_Project_Kickoff.html). Scope: [local JSON project library](../planning/PROJECT_LIBRARY.md). This is the 1 November 2026 implementation checkpoint before integration with the newly approved Dependabot updates.

The Android app now offers explicit saving of parsed imports, a local character/lorebook library, title/filename search, recent/title sorting, reopening, library-title renaming, duplication, and confirmed deletion. Sorting uses non-secret DataStore preferences. Unsaved replacement requires confirmation; saving a schema-invalid or unknown-format document retains it as a draft without changing its JSON. Malformed JSON cannot be saved in this increment. The original provider document is never modified or deleted.

The approved library/native-controls/expanded-inspector direction is retained. Room and managed files are injected through the repository; the inspector still uses the shared format engine. The ViewModel retains original owned bytes privately until save, rejects repeated busy operations, and prevents stale import completions from replacing newer selections.

Pre-dependency-update verification: 134 JVM tests passed (4 model, 81 format, 21 data, 28 app); both Android lint reports contain zero issues. App tests include save/reopen, malformed/schema-invalid distinctions, source preservation, failures, duplicate/delete/rename and stale asynchronous state. The six isolated Room device tests passed separately through the raw AndroidJUnitRunner on device 1da5425f, API 36. See [test evidence](../testing/PROJECT_LIBRARY_VERIFICATION.md) for commands and the initial installer restriction.

The APK is 0.3.0-internal, code 3. Its checkpoint SHA-256 is `30a4e72bf56c717fde447c3369ddf028f20bbcc482982c17992bc826f5ece2eb`, size 14,790,204 bytes. This hash is superseded if dependency integration changes the artifact. Actual user-facing save/reopen rendering checks and the final dependency-integrated build are recorded in current status; a JVM test or Room test alone is not visual/accessibility verification.

Full editing/export UI, PNG/asset association, links between projects, backup/restore, lifecycle recovery of unsaved imports, and Gemini remain later work. Saved library items survive database close/reopen; unsaved in-memory imports still need reopening after process death.
