# Pinned SillyTavern receiver verification

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), format fidelity and M3/M7 acceptance. [Current status](../STATUS.md) records integration; [harness instructions](../../tools/sillytavern-interop/README.md) reproduce these checks. These are actual local-server HTTP observations, distinct from the earlier reader-only oracle and from unperformed browser/chat tests.

## Environment and provenance

Executed 1 November 2026 with **Node 26.13.0** and **SillyTavern 1.19.0**, exact existing format pin **`06bde939fb1e9c4c8d8641d810f0a916b5bce127`**. The official codeload archive was 38,087,675 bytes; SHA-256 `d3c5b32e7a1b649366d7d35eaaf877b06376864aacd8713ee8e670ff8ee4099c`. The archived package-lock, PNG parser and World Info sources matched the earlier recorded pins. Ten source-file hashes are enforced by the new [provenance record](../../tools/sillytavern-interop/provenance.json).

The archive was downloaded with a 90-second request bound and 100 MiB cap into the external evidence directory. `npm ci --omit=dev --ignore-scripts --no-audit --no-fund --prefer-offline --fetch-retries=1 --fetch-timeout=30000` installed 667 pinned production packages from the upstream lock in 8 seconds. These are isolated development tools, not Android dependencies. No upstream source was patched.

Each harness run starts a real server on a fresh loopback port with fresh synthetic data. CSRF remains enabled; browser launch, extensions, model/tokenizer downloads and server plugins are disabled. Only local character and World Info endpoints are exercised. The server stops in cleanup. No user files, provider calls or real keys are used.

## Executed checks

```sh
node --check tools/sillytavern-interop/check.mjs
node tools/sillytavern-interop/check.mjs /Users/trinadhthatakula/StudioProjects/TavernForge-worktrees/evidence/sillytavern-interop/SillyTavern-06bde939fb1e9c4c8d8641d810f0a916b5bce127 format/build/png-reference /Users/trinadhthatakula/StudioProjects/TavernForge-worktrees/evidence/sillytavern-interop
```

Result: **`PASS_WITH_DOCUMENTED_RECEIVER_LIMITS`**, six named engine exports, six blank-name engine probes and four existing fixture probes. The engine manifest's SHA-256 was `c01437c41d5b4e7cdae57c101193200d9df9640bbe7456f96ef9c7e2355a81c5`; every JSON/PNG input hash was checked before sending. The final report is retained outside Git at `TavernForge-worktrees/evidence/sillytavern-interop/receiver-TDLYSR/report.json`, SHA-256 `6a91485b7fe45950d437bd7de074e8027863c6bad7f92be7265de4f284f046d5`. Synthetic receiver exports and server logs are alongside it; the private synthetic server data must not be published.

| Input | Actual receiver result | Scope of evidence |
| --- | --- | --- |
| Engine V1, V2, V3 with nonempty edited names, each JSON and PNG | All six import/get/JSON-export/PNG-export paths succeed. Name, description, personality, scenario, first message and example dialogue match. Exported PNGs are readable by the pinned parser. V3 supplied tree/greetings remain present. | Named empty-template content is exercised; richer V3 coverage is a separate fixture below. |
| Engine blank-name V1 JSON | HTTP 400. | Empty text is schema-valid but not a reliable receiver import. |
| Engine blank-name V1 PNG | Imported after the preceding V1 JSON attempt created a colliding filename; core fields remained blank. | Acceptance depends on receiver file naming/state. This is not a dependable blank-name workflow. |
| Engine blank-name V2/V3 JSON and PNG | HTTP 200 with an error body and no returned filename. | HTTP status alone cannot prove successful import. |
| Existing rich V3 fixture | JSON import/get/export and PNG export succeed; core text, alternate/group greetings, embedded lore, assets metadata, multilingual notes, extensions and unknown supplied values compare equal at JavaScript number precision. | Fixture input, not newly emitted engine output. Asset references remain metadata; no embedded-media resolution or remote image retrieval is established. |
| Existing native World Info fixture | Import/get succeed; initial stored bytes match exactly; `entries` remains an object. | Actual native storage/read path; chat activation and browser export not exercised. |
| Existing bare V2 CharacterBook fixture | Import/get succeed and original bytes are stored, but `entries` remains an array with V2 names. | Mere storage acceptance does not demonstrate usable native World Info semantics. No conversion occurred. |
| Existing standalone V3 lorebook fixture | HTTP 400: endpoint requires top-level `entries`; V3 envelope has `data.entries`. | Direct import is unavailable through this receiver path. |

## Receiver transformations and limits

1. **Source version is not retained by SillyTavern.** Its own writer/reloader returns `chara_card_v3` for the named V1 and V2 inputs. Those returned files **lack mandatory V3 `group_only_greetings`**. This confirms the earlier upstream-writer observation in a running application. TavernForge must keep its strict V3 diagnostic when those files return; it must not quietly insert defaults or relabel its own exports to imitate the receiver.
2. **Numeric fidelity ends at the receiver's JavaScript parser.** The rich V3 and World Info fixture sentinel `123456789012345678901234567890` is absent from the JSON response; the receiver serializes a rounded JavaScript number. Negative-zero and exponent spellings also normalize. Deep comparison after JavaScript parsing cannot certify original numeric precision. Native World Info's initial stored bytes were exact, but `/get` already changed their numeric representation. TavernForge's raw-preserving export remains unchanged.
3. **Blank character names need receiver guidance.** Empty required strings remain specification-valid. The observed receiver failures justify authoring guidance or a compatibility warning recommending a nonempty name, not changing empty text into a schema error.
4. **Standalone lore formats are not interchangeable.** The pinned World Info UI source converts Novel, Agnai and Risu formats; it does not convert these V2/V3 forms before the tested endpoint. Bare-book storage acceptance is not an endorsement of its activation semantics. Same-version character embedding and separate source-format export remain distinct operations; no cross-version/native transformation was introduced by this work.

Browser navigation, actual World Info activation during chat, receiver editing after import, provider behavior, and TavernForge reimport of every normalized receiver output remain separate checks. This report closes the bounded receiving-server evidence gap for named character JSON/PNG and native World Info storage/readback; it does not certify every external application workflow or lossless cross-application round trips.
