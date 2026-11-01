# Accessibility and approved-design review

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). The approved direction is [C's library, A's native controls, B's expanded inspector](../DESIGN_DECISION.md); the actual references are [the comparison](../design-comparison.html) and [Agent 3's proposals](../design/proposals.md). This review follows UI-04 and UI-05 in the [test strategy](TEST_STRATEGY.md). It started from the merged 0.5 internal build on 1 November 2026.

## Scope and evidence boundary

The accessibility agent reviewed the Compose screens, shared input controls and navigation/theme sources. It owns the `*Screen.kt` files and `ManualAuthoringControls.kt` for this increment. The supervisor owns navigation, theme, builds and device operation. No provider request, API key, private phone content, Gradle task or device operation was used by this review agent. Source review and implemented semantics are not a passing TalkBack test; runtime evidence must be recorded separately.

## Findings and implemented changes

| Finding | Change | Required runtime check |
| --- | --- | --- |
| Gemini permissions/context used separate label text and an independently interactive, unlabelled switch. | Each label and switch now share one full-width `toggleable` row with `Role.Switch`, a minimum 48 dp height and one interaction owner. The visual switch has no separate callback. Long labels wrap beside the switch. | Accessibility hierarchy exposes one named switch per permission/context choice; Tab/Space and TalkBack toggle only the intended value. |
| Gemini and artwork screens showed operation failures/notices only as static text. | Result/error text now uses a polite live region. The editor's input-limit feedback also uses a polite live region. No credential input is included in those regions. | Trigger a synthetic local error and confirm an announcement without moving focus or reading credential contents. |
| Repeated artwork/lorebook actions and library Open buttons had identical accessible names. | Accessible action names include the associated file/project name and retain the visible action text. | Screen-reader action navigation distinguishes two different library projects/artwork items and preserves the visible-label phrase for voice control. |
| Field editors used generic New value / Item labels even for known fields. | Labels include the selected field's existing human-readable name and the item position. | Focus a character text field and a tags/greetings array item and confirm both field and item context are announced. |
| Long backup restore and combined-file choice dialogs could exceed the available text area at large font or short window heights. | Their dialog content scrolls independently of the confirmation controls. | At 200% text and landscape height, every paragraph and chooser action is reachable; confirm/dismiss remain reachable. |

Existing native buttons, chips and text fields provide their framework role/state semantics. Existing screen headings and library feedback live regions remain in place. Artwork thumbnails already have text alternatives; the new purely decorative library monogram covers are excluded from accessibility because the adjacent title/format provide the same information. No fixed-height text fields or custom gesture-only actions were introduced.

## Comparison with the approved hybrid

The approved HTML is a design reference, not a requirement to reproduce fictional demo data or browser pixel measurements. The concrete information hierarchy and native-navigation expectations still apply.

| Approved requirement and reference | 0.5 source finding | This increment |
| --- | --- | --- |
| C: three visible creation actions feed the same model. | Five vertically stacked import/manual/artwork/Gemini/backup controls consumed the compact home before the collection. | Library now has three wrapping, equally styled actions: Combine files, Build manually, Generate with Gemini. Combine opens a native dialog using the existing JSON or PNG/JPEG callbacks. Backup moves out of the hero. |
| C: Characters and Lorebooks are first-class collections, with unknown/source-specific details retained. | Only source-version and validation filters existed. | Remembered All projects / Characters / Lorebooks shelf selector composes with existing search, tag, validation and source-version filters. Unknown formats remain visible under All projects. Changing a shelf does not alter documents or saved metadata. |
| C: portrait-inspired character cards, serif display headings, equal treatment for artwork-free projects and lorebooks. | Uniform text-only cards lacked the collection identity. | Warm serif project titles and distinct character/lorebook monogram covers use real project titles. No demo character or artificial artwork is inserted. Monograms use complete Unicode code points. Real attached-artwork thumbnails use bounded background sampled decoding (512 px maximum), with EXIF orientation for JPEG. |
| C: compact collection remains easy to reach. | Long hero and permanently expanded sort controls displaced saved items. | Shorter header; wrapping creation actions; sort controls move into the existing collapsible filter area. Search and collection choices remain visible. |
| A: compact bottom navigation and medium navigation rail. | The app scaffold had only a top app bar and page-level back buttons. | Native bottom navigation and medium/expanded rail are integrated; supervisor runtime checks below. |
| B: a validation inspector remains available beside the expanded editor. | Supporting-pane metadata applied to Library + Inspector, while the Editor was a separate single-pane destination. | Editor and live draft inspector now use Navigation 3 supporting scenes; Hide/Show inspector changes scene selection without altering draft state. |
| C: consistent parchment/ink/gold theme. | Base colors and serif headlines mostly matched C; Material 3 surface-container tokens were left at framework defaults. | Explicit surface-container/outline colors match the parchment and dark-green theme; rendered checks below. |

The library retains the existing callback contract and preservation rules. Source-format filtering, query, sort, tags, busy/error states and explicit project opening remain available. A separate collection selection only changes which already-filtered projects are displayed. Search-empty and genuinely empty-shelf messages are distinct. Backup is available through the integrated native Settings destination.

## Supervisor verification checklist

Use synthetic projects on the existing AVD; do not inspect the user's private phone projects or enter a real key for these checks.

1. Open Library with at least one character and one lorebook. At compact width, verify the three creation actions and collection controls are reachable without five stacked settings buttons. Select Characters, Lorebooks and All projects; confirm counts and contents agree. Combine a collection with an existing source-version filter and tag, then clear only the ordinary filters; confirm the selected collection remains selected.
2. Open Combine files. Exercise Cancel, JSON choice and PNG/JPEG choice using the system picker. Cancelling the picker leaves the library unchanged. Repeat the dialog at 200% font and landscape height; scroll its content if needed.
3. Navigate with a hardware keyboard or injected Tab and Space key events. The Gemini grant rows must expose separate names, checked state and a single activation target. Test the text grant only, then turn it off; image/follow-up values must remain unchanged. Do not send a provider request.
4. With TalkBack available, traverse each screen and inspect the grant switches, repeated Open/Delete/Link actions and edited-field labels. Capture only redacted synthetic evidence. Confirm live notices/errors are announced after an operation; the password field remains masked.
5. Test compact and expanded layout, both light/dark themes, 200% font, landscape, IME appearance and Back. Confirm the supervisor's rail/bottom navigation and editor inspector do not steal input focus, cover form controls, discard pending changes or change selections.
6. Run the app's focused compilation/lint/tests after integration, then record commands, counts and artifact identity in [status](../STATUS.md) and the final evidence section below.

## Runtime verification

Supervisor checks on the existing API 36 AVD used the optimized 0.6 APK and synthetic projects. Compact (1080×2400, 420 dpi), medium (800×1000, 160 dpi), and expanded (1600×1000, 160 dpi) layouts were exercised. The three creation actions, collection filters, actual PNG thumbnails, native bottom bar/rail, Story/Lore forms and expanded live inspector render correctly in the inspected screens. Hide removes the pane and centers the editor; Show restores it. A named edit survived expanded→compact resizing and Story→Lore→Story, saved as revision 2, and reopened after force-stop. The saved JSON name remained `Mira Vale AVD`.

Medium dark mode at system font scale 2.0 was visually inspected: field labels, values and actions wrap without overlap. Settings and offline license text are reachable and scrollable at that scale. Screenshots are retained outside Git in the sibling evidence directory (`ui-parity-final-expanded-editor.png`, `ui-parity-medium-dark-200.png`). The screenshot transition was allowed to finish before final visual inspection.

A source-review regression in new primary navigation was fixed: destination changes now pass through the existing unsaved-inspection guard. Further runtime checks are recorded below as completed. Broader TalkBack announcements, switch access, fold hinges, calibrated contrast measurements and hardware coverage remain unverified; these focused checks are not a full accessibility certification.
