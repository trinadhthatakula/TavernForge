# TavernForge verification plan

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), especially sections 04, 05, 08 and 10. Status: discovery plan, 31 October 2026. No Android app, format adapter, Gradle build or application test suite existed when this plan was prepared.

After the power failure, the saved fixture audit was rerun and its upstream reference bytes were independently verified. See [recovery verification](RECOVERY_VERIFICATION.md) for exact results and limitations. Application cases below remain planned; passing the fixture audit does not change their status.

## Evidence and support policy

Use [the pinned fixture inventory](../../format/fixtures/README.md) and [manifest](../../format/fixtures/manifest.json). A fixture audit verifies provenance and JSON readability, not TavernForge behavior. Every adapter support claim requires import, edit, no-op round-trip, strict target validation, export and observed import into the pinned SillyTavern revision. Publish the specific versions and limitations tested; do not infer full V3 support from successful format detection.

Track each test as **planned**, **implemented**, **passed**, **failed**, or **awaiting device validation**. A passed JVM/mock test cannot mark a document-picker, Keystore, lifecycle, accessibility or live interoperability case passed. Test execution reports record code revision, fixture manifest checksum, toolchain versions, command, result, and emulator/device + API level when relevant. No target hardware/API coverage is claimed until minimum/target SDK and supported devices are decided.

## Charter acceptance traceability

The row numbers follow the 16 checklist items in charter section 10. All application checks below are currently **planned**.

| Charter item | Acceptance evidence | Cases / layer | Gate |
|---|---|---|---|
| 1. Independent and combined JSON/lorebook/PNG import | Every available-input subset creates a recoverable draft with correct available exports | IO-01–04; domain + Android + UI | M3 |
| 2. Extract and validate PNG metadata | Correct payload selection, decoding and strict validation; corrupt files fail safely | PNG-01–09; pure JVM + interoperability | M1/M3 |
| 3. Associate and export all supported outputs | Persisted links survive reopen; exported assets import into pinned upstream | IO-05–08, DB-02; integration + Android | M3 |
| 4. User BYOK, no shared key | Add/update/verify/delete lifecycle; no embedded key | SEC-01–05; Android + repository audit | M2/M5 |
| 5. Separate text/image permissions, image off | Independent default-off gates enforced at request boundary | AI-01–04; unit + mocked HTTP + UI | M5 |
| 6. Transparent requests and usage | Preview exact model, purpose, data and limits; actual returned usage only | AI-05–07; unit + HTTP + UI | M5 |
| 7. Concept to character + lore | Mock structured response creates reviewable drafts for both document types | AI-08; HTTP + domain + UI | M5 |
| 8. Validate and explicitly accept AI drafts | Invalid, rejected or stale drafts never overwrite saved data | AI-09–11; domain + DB + UI | M5 |
| 9. Manual authoring without AI/network | Create, persist, reopen and export with network denied and no credential | IO-09, DB-01; Android + UI | M4 |
| 10. Required vs recommended fields | Schema-required presence/type distinct from optional creative quality hints | FMT-03, UI-01; unit + UI | M4/M6 |
| 11. Shared canonical model/engine | Equivalent import/manual/accepted-AI content uses same save/export contracts | FMT-01, IO-10; domain + integration | M3–M5 |
| 12. Unknown fields survive no-op | Nested unknowns, explicit null, types and arrays preserved; edit only owned paths | RT-01–06; JVM + persistence | M1/M2 |
| 13. Strict invalid export blocked | Error never produces destination bytes; risky conversion needs exact loss review | FMT-04–06, IO-08; unit + Android | M1/M3 |
| 14. Projects/assets accessible offline | Restart/process recreation, persisted file access and managed assets work offline | DB-01–05, IO-09; Android | M2/M3 |
| 15. No credential leakage | Scan logs, DB/preferences, backups and exports using runtime-generated canary | SEC-01–07; Android + CI audit | M2/M5/M7 |
| 16. Full regression suite | Recorded focused, integration, format, PNG and security evidence; no critical data-loss defect | All cases, release report | M7/M8 |

## First foundation batch: pure format engine

These tests require accepted parser/serializer contracts and a resolved toolchain. Implement before editors so fixtures exercise one preservation model.

| ID | Test inputs / action | Required assertion |
|---|---|---|
| FMT-01 | Import original bytes, detect format, validate, serialize without migration | Original version/provenance retained; parsing, detection, validation, conversion and serialization remain separate operations |
| FMT-02 | V1/V2/V3 pinned fixtures plus ambiguous V1 root + V2 envelope, unknown version, array/scalar root | Unsupported/ambiguous input has a diagnostic and preserved source; no silent version selection or conversion |
| FMT-03 | Remove every specification-required field; replace string/array/object/bool/number with wrong type; present empty strings | Required structure/types enforced from the pinned version; empty-but-permitted creative content is not falsely a missing field; array item types checked |
| FMT-04 | Parse failure, schema failure, semantic conflict, compatibility warning, quality suggestion | Distinct phase, severity and stable path; error blocks strict export, info does not; warnings identify actual risk |
| FMT-05 | Envelope-only upstream V3 helper; `data: []`; string `3.0`, numeric `3`, unsupported minor, invalid numeric version text | Strict V3 schema is independent of upstream's permissive detector; export support limited to explicitly implemented versions; newer imports retain source and receive compatibility diagnostics |
| FMT-06 | V1→V2, V3→V2, embedded↔standalone lore conversion | Explicit source/target and changes/lost paths are previewed; cancel leaves source untouched; no broad promise of lossless conversion |
| RT-01 | Unknown fields at root/data/book/entry/extensions, nested arrays/objects and Unicode | No-op export retains all values and value types; array ordering preserved |
| RT-02 | Absent field vs explicit null vs empty string/object/array, false and zero | Distinctions survive parse, save/reload and serialization; defaults are not inserted into imported raw data merely for display |
| RT-03 | Edit one known field in an imported document | Only intended path changes; unknown siblings, root properties and source metadata survive |
| RT-04 | Unknown nested extension plus known extension edits | Merge/patch preserves untouched keys; explicit user removal deletes only requested path |
| RT-05 | Large integers, decimal/exponent numbers, escaped text and surrogate/Unicode edge cases | No precision loss in unknown JSON numbers; semantic preservation contract documented; formatting differences not mislabeled as content loss |
| RT-06 | Duplicate JSON member names, malformed UTF-8, excessive depth or document size | No silent map overwrite or replacement-character decoding; preserve original input and reject strict import/export when ambiguous; configured bounds enforced |
| LORE-01 | Empty/non-empty embedded character book and standalone World Info | Each native shape uses its own adapter; object-indexed World Info entries are not mistaken for a card entry array |
| LORE-02 | Primary/secondary keys, selective logic, enabled/constant/case flags | Exact native spelling/type/default behavior derived from upstream; missing values remain missing in preserved raw data |
| LORE-03 | Insertion order/position/depth, probability boundaries, recursion, world settings | Relationships and numeric bounds use pinned source; unsupported optional fields preserved with precise compatibility notes |
| LORE-04 | Duplicate/reorder/delete entries, stable IDs, shared lorebook linked to two characters | Order changes only intended semantics, stable references remain valid, deleting a link does not silently delete shared content |

No maximum JSON size, depth, PNG chunk size or decoded payload limit is invented here. Agent 1/2 must define constants and error behavior before implementation; test each limit at `limit−1`, `limit`, `limit+1`, and cancellation during streaming.

## PNG and import/export batch

PNG fixtures must include genuinely exported upstream artifacts after license/provenance review. The current corpus has no PNG binary fixture.

| ID | Test inputs / action | Required assertion |
|---|---|---|
| PNG-01 | Metadata-free PNG; `chara` only; `ccv3` only; both equal and conflicting | Detect metadata-free image safely; report all candidate payloads; apply documented `ccv3` precedence without silently discarding conflicting data |
| PNG-02 | PNG signature, chunk length/type/CRC, missing/truncated IEND, corrupted IDAT | Safe parse error, no crash/unbounded allocation; diagnostics distinguish image/container and metadata failure |
| PNG-03 | Base64 corruption, bad UTF-8, invalid JSON, invalid card schema | Each decoding/validation stage reports failure; no permissive decoder hides malformed data |
| PNG-04 | Duplicate recognized chunks, mixed-case keywords, unrelated text chunks | Deterministic documented behavior; preserve raw alternatives for review; compatibility recognizes upstream case-insensitive lookup |
| PNG-05 | `tEXt` vs `iTXt`/`zTXt` or trailing JSON bytes without a recognized chunk | Claim only verified conventions; unsupported metadata reported; renaming/appending bytes never counts as card export |
| PNG-06 | Replace metadata in a valid image | Original IDAT and unrelated chunk payloads/order preserved where feasible; changed chunks have correct CRC; exported PNG decodes |
| PNG-07 | Huge declared length, many small chunks, huge base64 payload, compressed metadata bomb | Bound file/chunk count/chunk length/decoded payload and decompression if supported; cancellation releases resources |
| PNG-08 | Export V2 and V3 to image then open in pinned SillyTavern | Target chunk(s), decoded spec and displayed content match requested format; mixed-version output never hides discrepancies |
| PNG-09 | Malformed preferred `ccv3` + valid `chara` | No silent successful fallback or deletion; show exact conflict and require documented recovery selection |
| IO-01 | Character only, lore only, image only, PNG card only and combinations | Partial inputs remain usable; show missing inputs and eligible export types |
| IO-02 | User cancels picker, denies/revokes URI access, process recreation during import | No partial project corruption; retry and cancellation preserve previous project |
| IO-03 | MIME/extension mismatch, unreadable file, null filename, cloud document offline | Inspect actual bytes and report actionable error; never assume extension is proof |
| IO-04 | Import same file twice or same asset linked to two projects | Stable identity/link policy; no accidental overwrite or orphan asset cleanup |
| IO-05 | Export character JSON without artwork | Succeeds without fabricating an image or requiring network/key |
| IO-06 | Standalone lore export + character/lore/image association | Preserve exact native semantics and clearly disclose embedding/conversion differences |
| IO-07 | Export picker cancelled, storage full, permission revoked, write failure | Original project unchanged; incomplete destination handled as documented; no false success notification |
| IO-08 | Export after validation error or unacknowledged risky loss | No output write occurs; acknowledgment tied to exact document revision/target/loss report |
| IO-09 | Manual and import workflows with network blocked and no configured key | Complete save/reopen/export works; network mock records zero requests |
| IO-10 | Same document via import/manual/accepted AI | Same versioned validator and serializer invoked; no workflow-specific bypass |

## Persistence, security and Gemini batch

| ID | Test inputs / action | Required assertion |
|---|---|---|
| DB-01 | Create/edit/save/reopen, force-stop, process death and restart offline | Room is saved structured source of truth; recoverable drafts follow explicit policy |
| DB-02 | Shared lorebook/project links; duplicate project; trash/restore | Referential integrity and documented copy/shared behavior; no collateral deletion |
| DB-03 | Large artwork import, checksum validation, DB inspection | Only references/metadata in rows; app-private files present and correct |
| DB-04 | Failure between asset copy and DB transaction; cancelled import; disk full | Atomic or recoverable operation; no dangling links/data loss; orphan cleanup tested |
| DB-05 | App upgrade migration + backup/export/restore with missing asset | Schema migration evidence, raw data preservation, precise missing-file report; credentials excluded |
| SEC-01 | Runtime-generated non-secret canary supplied to credential store | Encrypted material only outside Keystore; no ordinary DataStore/Room/plaintext key |
| SEC-02 | Add/update/verify/delete key, reboot, process death | Old encrypted values removed per policy; deleting disables future requests; verify is explicitly user-triggered |
| SEC-03 | Keystore unavailable/invalidated, decryption failure, restore onto another device | Fail closed, request new credential safely, no plaintext fallback or crash |
| SEC-04 | Inspect logs/errors/HTTP logging/crash reports/analytics in every failure path | Canary absent; provider errors and URLs cannot leak key or unnecessarily expose user content |
| SEC-05 | Inspect built resources/source/sample configs/repository history | No developer/shared credentials; scanner version/rules/results recorded, no real key in scanner test data |
| SEC-06 | Export project, backup/share, app backup rules and preferences snapshot | Credential/ciphertext excluded from portable project data; no secret in generated prompt/history |
| SEC-07 | UI masking, clipboard policy, screenshots/accessibility and key replacement | Deliberate credential visibility policy tested on device; never imply UI masking proves encrypted storage |
| AI-01 | Fresh settings; all eight text/image/auto-followup combinations | Each is independently off by default and independently enforced at network boundary |
| AI-02 | Text granted, image off; image request or image response configuration attempted | No image-generation request is sent, even through a multi-step workflow or reused client |
| AI-03 | Image granted but selected model lacks capability; unknown/stale discovery | Show unavailable; no silent model substitution; capability and consent both required |
| AI-04 | Auto-followup off; retry/repair/multi-step proposed; user cancels | Each additional call needs authorization; automatic repair never silently adds billed requests; revoke during queue prevents dispatch |
| AI-05 | Request preview changed model/content/limits before dispatch | Preview covers exact request purpose/model/data/image flag/limits/call count; material changes invalidate approval |
| AI-06 | Returned full/partial/no usage; unknown price or estimate | Display supplied usage only; estimates labeled, no invented exact cost; provider pricing/quota link accessible |
| AI-07 | Timeouts, cancellation, rate limit, auth error, offline, malformed/oversized provider body | Safe retry policy with no hidden calls; key/content redaction; user data unchanged |
| AI-08 | Mock valid character and lore structured output from concept | Both enter same strict format pipeline as manual/import and remain unsaved drafts until accepted |
| AI-09 | Mock invalid JSON, missing fields, wrong types, unknown extension, model text attempting save instructions | Untrusted output cannot bypass validation or application controls; unknown data handled by same preservation policy |
| AI-10 | Accept/reject/cancel result, partial-field acceptance, app restart during review | Reject/cancel never persist replacement; accepted content applied transactionally; draft lifecycle explicit |
| AI-11 | Project edited after generation starts; concurrent responses; retry duplicates | Acceptance targets correct revision or resolves conflict; no stale overwrite or duplicate save |
| AI-12 | Optional generation history disabled/enabled | Defaults await product decision; retain only approved metadata, never keys or unnecessary prompt content |

Use Ktor's mock engine behind the provider boundary after its version is approved. Live Gemini calls are not necessary to test request gating and must not use developer credentials in CI. Provider integration verification later needs a user's configured test account, explicit request previews and tracked actual usage; mock results do not establish live model availability.

## UI and release evidence

| ID | Check | Evidence |
|---|---|---|
| UI-01 | Basic/advanced mode, missing-format fields vs authoring suggestions | Semantics/UI assertions + approved design review |
| UI-02 | Three workflow entry points and partial-asset states | End-to-end navigation tests for same canonical project |
| UI-03 | Error paths, warning review, AI request preview and explicit accept/reject | UI tests plus persistence/network assertions |
| UI-04 | TalkBack labels/order, keyboard and pointer operation, contrast, large font, touch target size | Device accessibility review, automated checks where available |
| UI-05 | Compact/medium/expanded windows, rotation, fold posture, multi-window, keyboard/IME and insets | Screenshots and interaction checks on chosen supported matrix after design approval |
| REL-01 | Full export/import interoperability with pinned SillyTavern | Files + checksums + imported field comparisons, version/build identity |
| REL-02 | Release variant, minification, dependency notices and credential scan | Actual release build artifacts/check results after toolchain and license decisions |
| REL-03 | Large-file/cancellation regression, no unresolved critical data loss/key handling defects | Reproducible defect reports and final supervisor release review |

## Execution order and handoff

1. **Now:** verify fixture hashes and read the upstream/spec discrepancies. This requires only Python 3 and does not validate the app.
2. **M0 gate:** Agent 1 resolves requested versions or presents a concrete approved adjustment. Agent 2 creates wrapper/modules/CI; then tester records actual build commands and smoke evidence. No guessed Gradle task exists yet.
3. **M1:** add pure JVM parser/validator/round-trip/PNG cases; acquire complete positive V3, real PNG and World Info exports before advertising support. Run focused module tests on every relevant change.
4. **M2–M5:** add repository/Room integration, Android file/Keystore checks and mocked Gemini boundary tests as interfaces land. Keep test doubles separate from device evidence.
5. **M6–M8:** execute approved UI/adaptive matrix, interoperability, release variant and security regression. Device gaps stay pending rather than being signed off from unit tests.

Defect record: ID, severity/data-loss risk, app and upstream revision, fixture checksum, environment, exact steps, expected vs observed result, redacted logs, smallest reproduction and owner. Acceptance blockers require an explicit resolution or documented excluded support scope before release.
