# Gemini provider, credential and request contracts

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), section 05. Related: [architecture](ARCHITECTURE.md), [execution plan](AUTONOMOUS_EXECUTION.md), [toolchain evidence](TECH_STACK.md), [current status](../STATUS.md).

**Status: researched proposal, 1 November 2026.** This document defines a future implementation slice. It does not claim an implemented provider, successful API call, working credential screen, model access, or verified Android Keystore behavior. Public documentation and the unauthenticated discovery schema were inspected; no real key, credential file, environment value, or user project was used. Ktor 3.6.1 and DataStore 1.3.0 are already catalogued; this plan does not change versions or add modules.

## Scope and ownership

The planning owner is `edit_tests`, with this document as the sole output. Inputs are the charter, canonical/editing repository contracts, approved UI direction, official Google/Android references below, and the requirement for CI to run without real credentials. The supervisor owns decisions, integration, dependency/manifest changes and status. Future assignments must give one owner each for provider/policy, Android credential storage, generation UI, and independent tests.

Acceptance for this planning task: exact public API evidence, no name-based capability guesses, enforceable per-call authorization, encrypted key lifecycle, untrusted draft acceptance through existing storage, and an executable test strategy with clear unverified boundaries. Production implementation and live provider/device testing are outside this assignment.

## Official evidence and version pin

All references below were retrieved on **2026-11-01**. Public pages are mutable; capture a fresh reviewed schema/capability snapshot when implementing, and record changes instead of silently following newer examples.

| Source | Observed fact and implication |
| --- | --- |
| [Model API](https://ai.google.dev/api/models) | `models.list` paginates `models` with `nextPageToken`. `Model` exposes resource name, generation methods and token limits, but no image-output capability field. Page reports 2026-10-29 update. |
| [GenerateContent reference](https://ai.google.dev/api/generate-content) | REST v1beta has `contents`, optional `systemInstruction` and `generationConfig`; responses contain candidates, feedback and usage. Check candidate finish reasons before considering output complete. `responseSchema` and `_responseJsonSchema` are deprecated in the current reference. |
| [Public discovery schema](https://generativelanguage.googleapis.com/$discovery/rest?version=v1beta) | Retrieved revision **20261030** independently confirms REST field names and `responseFormat`. The `responseJsonSchema` description is internally inconsistent; do not use that ambiguous field. |
| [Structured output guide](https://ai.google.dev/gemini-api/docs/structured-output) | Current examples use Interactions. Supported schemas are a subset; several string/object/array constraints are ignored, and complex schemas can be rejected. Schema-shaped output still needs local semantic validation. Page reports 2026-10-21 update. |
| [Image generation guide](https://ai.google.dev/gemini-api/docs/image-generation) | Current examples use Interactions, which is a different wire protocol. Generated images include SynthID; retain provenance and make no watermark-free promise. |
| [Gemini 3.1 Flash Image model page](https://ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-image) | Explicit code `gemini-3.1-flash-image`; image and text outputs; image generation supported; structured output unsupported. This is capability evidence, not proof that a user's key can use the model. Page reports 2026-10-29 update. |
| [Token API](https://ai.google.dev/api/tokens) | `countTokens` accepts `contents` or a complete `generateContentRequest`, not both. Result includes `totalTokens` and optional cache/modality counts. |
| [Token guide](https://ai.google.dev/gemini-api/docs/tokens) and [pricing](https://ai.google.dev/gemini-api/docs/pricing) | Input counting and generation usage are different observations. Rates vary by model, modality, service tier and date. The pricing page distinguishes free/paid data-use terms. Do not infer account tier from a successful metadata request. |
| [API key guide](https://ai.google.dev/gemini-api/docs/api-key) | Current REST examples support the `x-goog-api-key` header. Google's guidance against exposing app-owned keys in mobile clients does not make a bundled/shared key acceptable for this BYOK app. |
| [Android Keystore](https://developer.android.com/privacy-and-security/keystore) | Cryptographic key material can remain non-exportable; hardware backing depends on the device. A compromised app process can still use its keys. |
| [KeyGenParameterSpec AES/GCM example](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec) | AndroidKeyStore supports an AES key restricted to encryption/decryption with GCM and no padding. |
| [Android backup rules](https://developer.android.com/identity/data/autobackup) | `noBackupFilesDir` is excluded from Auto Backup. Explicitly examine both backup and device-transfer configuration when integrating credentials. |

The locally inspected discovery response was 387,074 bytes on the first fetch. A second fetch had the same revision but a different raw hash; the full first body was not retained for a semantic comparison. The retained research snapshot's canonical SHA-256 is `d150bc5dd4bc06d5fc034caad0d87fe2f4287864e5caef93b658a8d1a1cb1d24`, using Python `json.dumps(document, sort_keys=True, separators=(',', ':'), ensure_ascii=False).encode('utf-8')` to make object ordering irrelevant. The temporary research file is not a checked-in fixture. Before implementation, pin a reviewed minimal contract fixture and its provenance in the repository; this document's hash alone cannot reproduce a future mutable response.

## API boundary and minimal wire contract

Recommend one explicit `GenerateContentV1Beta` adapter first. Do not mix Interactions request/response fields or SDK snake_case names into it. Interactions requires its own reviewed adapter if later selected. Production origin is fixed to `https://generativelanguage.googleapis.com`; an arbitrary provider URL is not an initial feature.

| Operation | Method/path relative to origin | Response projection |
| --- | --- | --- |
| Discover | `GET /v1beta/models?pageSize=100` with bounded pagination | `models[].name`, `supportedGenerationMethods`, `inputTokenLimit`, `outputTokenLimit`; optional `nextPageToken` |
| Inspect one model | `GET /v1beta/{name}` where validated name came from discovery | Same `Model` projection |
| Generate | `POST /v1beta/{name}:generateContent` | `candidates[].content.parts`, `finishReason`, `safetyRatings`; `promptFeedback`, `usageMetadata`, `modelVersion`, `responseId` |
| Count input | `POST /v1beta/{name}:countTokens` | `totalTokens`, optional cache and modality details |

The following is an original, synthetic contract example, **not a runnable request or proof of model support**. Its exact model path and credential are deliberately absent:

```json
{
  "contents": [{"role": "user", "parts": [{"text": "Write one greeting for a fictional archivist."}]}],
  "generationConfig": {
    "candidateCount": 1,
    "maxOutputTokens": 512,
    "responseModalities": ["TEXT"],
    "responseFormat": {
      "text": {
        "mimeType": "APPLICATION_JSON",
        "schema": {
          "type": "object",
          "properties": {"greeting": {"type": "string"}},
          "required": ["greeting"],
          "additionalProperties": false
        }
      }
    }
  },
  "store": false
}
```

The current discovery schema defines `responseFormat.text.mimeType` as `APPLICATION_JSON` or `TEXT_PLAIN`, with optional `schema`. Its image equivalent exposes `mimeType` (`IMAGE_JPEG`), `delivery` (`INLINE` or `URI`), and enum-valued `aspectRatio`/`imageSize`. These are API schema facts; individual model support must be established separately. Do not substitute enum values with strings from older `imageConfig` examples. Prefer inline results for the initial adapter. Unexpected remote image URLs must not trigger downloads.

For text drafts, consume ordered non-thought text parts from the selected complete candidate, never concatenate candidates together. For images, `inlineData` contains `mimeType` and base64 `data`. Preserve candidate attribution/provenance relevant to accepted output. Treat unexpected tools, executable code, URLs and unsupported part types as data/errors; none can execute or fetch anything. `STOP` is the ordinary complete case; truncation, block reasons, missing content, unknown finish reasons and continuation are explicit non-success outcomes. A continuation token is not permission for another call.

Choose a small purpose-specific schema for generated values. For a field rewrite, request the replacement value, not a replacement of the entire imported document. Full-card generation uses an explicitly selected format and the canonical engine; do not send the whole format engine's complex schema merely because the provider accepts a schema field. The app remains responsible for lengths, duplicate/unknown JSON properties, required fields, allowed patch paths, preservation and strict export checks.

Do not request tools, grounding, file uploads, caches, external URLs, automatic provider repair, or safety-setting overrides in the initial slice. Set `store=false` where this adapter supports it, but do not represent that field as a guarantee about Google's retention, abuse monitoring or training terms. Link the current provider terms from the disclosure.

## Model discovery and capability decisions

Represent three independent facts: **listed for this credential**, **documented capability**, and **observed successful operation**. A public model page establishes only the second. Metadata success does not establish billing, quota, regional access or generation success.

Proposed `ModelCapabilityEvidence` contains exact model resource ID, API surface/version, supported input types, exact allowed output-modality combinations, structured-output support, supported configuration fields, source URL, source retrieval date, reviewed snapshot hash and registry version. Each optional capability is `Supported`, `Unsupported`, or `Unknown`, never default-true. Configuration is constrained by the intersection of the provider model record, reviewed evidence and implemented adapter support.

At runtime, intersect the credential's bounded `models.list` results with the versioned evidence registry. Revalidate a chosen resource name as `models/<single-safe-segment>` before path construction; never accept a returned name as an arbitrary URL. Retain the exact selected resource ID; do not silently replace it with `baseModelId`, a family alias, another model or another endpoint. Cache non-secret model metadata with fetch time and credential generation, label stale/offline results, and refresh only on an explicit metadata action. Cap page count/body size and stop repeated page tokens.

Image support **cannot** be inferred from `generateContent`, the substring `image`, a marketing family name, or image-input support. The researched `gemini-3.1-flash-image` page can seed an evidence record, but exact endpoint/configuration compatibility remains to be reviewed before enabling a production choice. The newer `gemini-nano-banana-2.1` model page timed out on two bounded attempts, so it supplies no verified model-page evidence here. Unknown image capability remains unavailable with an explanation. A hidden generation probe is forbidden. A user-approved generation can add an observed result; it cannot retroactively authorize the request.

## Proposed domain and provider contracts

Keep these types free of Android, Compose and Ktor. Names below are proposed contracts, not existing classes.

| Contract | Required content/invariant |
| --- | --- |
| `GenerationGrants` | Independent `text`, `image`, `automaticFollowups` booleans, all false when absent; monotonically changing grant generation. Preferences contain no credential or prompt. |
| `CredentialState` | `Absent`, `Available(credentialGeneration)`, `Unavailable(reason)`, `Deleting`; no key text, suffix or plaintext fingerprint. |
| `GenerationIntent` | Purpose, target format/project and expected revision, exact allowed target paths, selected source fields/concept/assets, selected model, requested limits. |
| `PreparedRequest` | Owned immutable body bytes, fixed origin/method/path, content type, purpose, source snapshot hashes/revision, template/schema/adapter/capability versions, credential/grant generation and byte/response/call limits. No credential. Redacted `toString`; never a loggable data class containing prompts. |
| `RequestPreview` | Human-readable projection of that same prepared object: provider, exact ID, purpose, complete outgoing text, selected image preview/hash/size, all settings, image use, call count and supplied count/labeled estimate. A summary can expand to full data; do not build the body later from mutable UI state. |
| `RequestApproval` | In-memory, one-use token bound to prepared request identity/body digest and all generations/limits, expiry, parent workflow and permitted call index. Constructed only by the user's review action or a still-valid preapproved exact workflow. |
| `GenerationProvider` | Executes only a prepared-and-authorized call; returns bounded typed response, provider usage or a safe failure. No repository, file-picker, tools or grant-setting authority. |
| `CredentialStore` | Observe availability, update, delete, and restricted `withCredential(expectedGeneration)` for the transport boundary; never expose plaintext to ViewModels, model records, Room or exports. |
| `GeneratedDraft` | Candidate values/staged artwork, base revision, requested target paths, model/request provenance, diagnostics and review diff. No saved mutation and no executable provider actions. |
| `UsageObservation` | Nullable provider-supplied counts by name/modality, model version, request outcome and optional labeled estimate with provenance. Missing is not zero; invalid negative/overflow counts are rejected as invalid metadata. |

`prepare → review → authorize → dispatchOnce → parse → validate → reviewDraft → accept` is a state machine. Build/version prompts **before** rendering the exact preview. The charter's conceptual prompt-building step after preview must not permit the bytes to change after approval; this ordering refines its invariant without changing product scope.

Before opening the network call, a single serialized policy gate checks approval ownership/expiry/one-use status, all grants, credential generation, selected model capability, unchanged body/target revision, and remaining limits. Consume the approval atomically when admitting the request so double-taps/racing coroutines cannot duplicate it. A failed admitted attempt requires a fresh explicit retry review; a response does not restore its token. No HTTP client retry plugin, replay on authentication error, automatic redirect, WorkManager retry, or process-restored send queue.

Grant changes invalidate approvals and queued work. Turning a grant off or deleting/updating the key cancels affected in-flight jobs and prevents new dispatches; cancellation cannot recall bytes already sent. Define dispatch as the authorization linearization point, and disclose an ambiguous outcome if cancellation/connection loss happens after it. Never promise that a timed-out request was free or unprocessed. Serialize credential mutation against dispatch and check grants immediately before transport handoff.

## Independent grants and follow-up scope

| Operation | Required authorization |
| --- | --- |
| Store/update/delete local key | Explicit local settings action; no network and no generation grant |
| Verify key / refresh models | Explicit metadata action previewing provider, key use and bounded metadata calls; no project content. This does not enable text/image/follow-up grants. |
| Text/card/lore/field generation | Text grant plus exact call approval |
| Artwork generation | Image grant plus exact artwork call approval and supported image capability. Incidental caption text stays in artwork preview; using it to rewrite project fields requires a separately reviewed text operation. |
| Count tokens for a text/artwork request | Explicit count-call approval and the underlying data-disclosure/grant checks; counts are an additional network call, not a free hidden preview step |
| Automatic next call | Follow-up grant **and** the next call's own text/image grant **and** a still-valid exact preapproved payload/call slot |

The initial automatic workflow can execute only requests whose outgoing bytes were all prepared and reviewed in advance. A response-dependent repair, continuation or generated-image prompt changes the outgoing data: show its new exact preview. The follow-up switch does not grant unrestricted automation. Later dynamic workflows need a separately reviewed contract for bounded derivation and visible outgoing data; this is an open integration decision, not permission to weaken the exact-preview rule.

Initial proposed limits, subject to owner verification: one candidate; one request per approval; no automatic retries; 256 KiB UTF-8 text input, 2 MiB text response, 30-second connection and 120-second overall timeout. Artwork has separate file-byte, base64, decoded-pixel and count budgets; propose 16 MiB image-file bytes after base64 decoding, 16 megapixels and one accepted image per call. Cap the encoded HTTP response too, accounting for base64 overhead. Budget enforcement counts streamed/decompressed bytes, not just Content-Length. Align with existing format/asset limits before exposing options. Output token limits are model-specific configuration, not a guaranteed cost ceiling.

## Android credential lifecycle

Use AndroidKeyStore to generate an app-owned AES-256 key authorized for `ENCRYPT | DECRYPT`, `GCM`, and `NoPadding`. Use fresh provider-generated IVs for every encryption, a 128-bit authentication tag, and authenticated additional data binding envelope version, provider, credential ID and alias version. Persist only a small versioned envelope `{version, credentialId, aliasVersion, iv, ciphertextAndTag}` atomically under `noBackupFilesDir`. Validate bounded lengths/versions before decryption. Do not invent custom cryptography, reuse IVs or fall back to plaintext if Keystore fails.

The initial choice need not require biometric authentication on every call; any later authenticated-key mode needs its own lock/invalidation UX and tests. Do not claim StrongBox/hardware backing without checking the generated key's `KeyInfo`; ordinary AndroidKeyStore is the baseline. Run storage/crypto off the main thread. AVD success is not evidence of hardware-backed isolation.

1. **Add/update:** the user enters a masked value into transient UI state. Disable saved-instance-state/restoration, autofill and keyboard learning for that field where supported; clear it on leaving the screen. Do not automatically verify over the network. Validate non-empty/bounded input locally, encrypt under a new credential generation, verify the staged envelope, then atomically replace the active record. Failure leaves the previously active credential usable and reports a safe error. Successful replacement invalidates approvals/model access observations and cancels old calls; delete the superseded alias/envelope after commit.
2. **Use:** decrypt only inside the narrow transport operation, attach via `x-goog-api-key` header, and release references promptly. Do not put it in a query string. Wipe owned mutable byte/character buffers where possible; JVM/HTTP header strings may have copies, so never claim guaranteed memory erasure. Do not expose plaintext from `StateFlow`, `SavedStateHandle`, `Bundle`, `rememberSaveable`, exception messages, analytics or screenshots.
3. **Verify:** explicit metadata-only request with a bound call budget. Report “metadata access succeeded” rather than “all models/billing verified.” Authentication, permissions and quota failures have separate safe messages. Do not display raw provider error bodies that may echo input or credentials.
4. **Delete:** invalidate approvals and mark credentials unavailable before cleanup; cancel relevant jobs. Delete the ciphertext and its Keystore alias, including abandoned staged versions. If cleanup fails, remain unavailable and expose retryable local cleanup status. This deletes local access, not the cloud key; link [AI Studio](https://aistudio.google.com/api-keys) for remote revocation.
5. **Invalidation/corruption/restore:** missing alias, failed authentication tag, malformed envelope, unsupported version or unusable Keystore key produces `Unavailable`, with a re-enter/delete action. Never silently recreate a key and claim the old credential still works. Remove orphaned staged aliases/envelopes only using the credential store's owned namespace and committed manifest; do not scan/delete unrelated app files.

Exclude envelopes, staged credentials and consent/grant restoration from backup/device transfer; after reinstall/restore, grants default off and keys require re-entry. Use existing app backup policy where stronger. Credential screens can use secure-window protection while visible, restoring the prior flag on exit. Do not copy keys into clipboard or tests automatically. Since plaintext must briefly exist for authenticated HTTP, on-device encryption protects storage, not a compromised/rooted process; the charter's runtime BYOK choice remains, with no bundled key or hidden proxy.

## Transport, usage and failure handling

Use the pinned Ktor version behind an injectable transport. Pick an Android engine with auditable no-replay behavior; disable both Ktor-level retries and engine-level retry-on-connection-failure where applicable. Redirects are off, TLS certificate/hostname validation remains standard, cleartext is disabled, and test endpoint injection exists only in test construction. A production key must never be attached to a different origin. The app's INTERNET permission is a manifest prerequisite, not consent.

Do not install body/header logging. Use safe local error codes such as `AUTH_REJECTED`, `QUOTA_OR_RATE_LIMIT`, `MODEL_UNAVAILABLE`, `REQUEST_REJECTED`, `PROVIDER_BLOCKED`, `INVALID_RESPONSE`, `LIMIT_EXCEEDED`, `CANCELLED`, `OUTCOME_UNKNOWN`, and `TRANSPORT_FAILURE`. Provider status codes may inform them, but arbitrary error descriptions, request objects, URLs containing tokens and exception causes must not reach UI/logging/crash capture. No crash/analytics content collection is introduced by this slice.

Project content is untrusted input, including instructions embedded in imported JSON. Send only the chosen projection with explicit boundaries, not all unknown extensions or the whole library. Never add the API key to a prompt. Model output cannot alter grants, endpoints, templates or budgets. Reference images must be owned/validated bytes prepared before review; no automatic URL loading, document-picker access or upload on provider instruction.

The usage parser retains provider-supplied `promptTokenCount`, `candidatesTokenCount`, `thoughtsTokenCount`, `cachedContentTokenCount`, `toolUsePromptTokenCount`, `totalTokenCount`, optional modality detail arrays and `serviceTier`. Do not compute output as total minus input or add cached tokens twice. Missing usage remains “not supplied,” including on failed/cancelled calls. Count-token results are labeled input counts, not actual generation usage. Before countTokens, use the exact reviewed request snapshot; another content revision invalidates that count.

Without a reviewed tokenizer/rate table, show “token/cost estimate unavailable,” not guessed precision. Any later estimate records source date, exact model/modality/tier/currency and assumptions; provider counts plus a public rate still yield an estimate, not an invoice. Account billing, free-tier eligibility and taxes remain unknown. Show current pricing/quota links and the possibility of billed processing after ambiguous network failure. Optional local history defaults off until the product decision is recorded; an in-memory usage panel needs no prompt persistence.

## Draft review and persistence

The provider returns candidates to staging, never to `ProjectRepository` directly. Parse under the format engine's resource budget, validate the requested kind and proposed values, and produce a field-level diff against the immutable base revision. A schema-conforming response is not an accepted draft. Strip no unknown existing data; preserve untouched original/current snapshots and number lexemes. Present invalid/partial output as an unsaved draft with diagnostics; it cannot silently repair or retry itself.

Acceptance of edits uses the existing `ProjectRepository.applyEdits(id, expectedRevision, operations)`, restricted to the reviewed target paths. A conflicting revision, deleted project or changed draft invalidates acceptance and requires a fresh diff. Rejection/cancellation leaves the saved project untouched. Full new documents require an explicitly selected supported format and explicit save through the same repository/format flow, with generated provenance kept distinct from an imported source; agree that creation method with the manual-creation owner before implementation. Never pretend generated content came from an external import.

Generated artwork stays in bounded app-private staging, is decoded/validated before display, and is attached only after explicit acceptance through the shared asset repository. Preserve supplied attribution/SynthID-related provenance and original output bytes where supported. If JPEG is converted into another container for card export, disclose and test that transformation; do not rename its extension and call it PNG. Staged output is cleaned after rejection/cancellation or orphan recovery; accepted assets follow normal managed-file lifecycle. Unaccepted drafts are memory-only initially and do not auto-resume a request after process death.

## Verification plan and implementation order

Tests use synthetic non-credential sentinels and mock transport responses. No build, CI test or AVD check needs a real key. Test-only sentinels must be visibly invalid as Google credentials, and no live Google request is permitted in those tests.

| Order / owner | Owned paths to agree before assignment | Acceptance evidence |
| --- | --- | --- |
| 1. Supervisor + provider owner | Minimal checked-in official wire/capability fixture; catalog/engine integration owned by supervisor | Exact source revision/hash, chosen endpoint fields, one documented text/image combination; no unreviewed dependency changes or real access claims |
| 2. Policy owner | Proposed pure `core/model/.../generation` contracts/policy | All eight grant combinations, double-submit, stale body/revision/model/template/grant/key, expired/reused token, exact body binding, bounded batch admission and response-dependent follow-up rejection |
| 3. Credential owner | Proposed `data/.../security` and independent Android tests | Encrypt/decrypt/update/delete, IV uniqueness, altered IV/ciphertext/AAD, missing alias, storage-failure atomicity, invalidation, restart and backup exclusion on an existing AVD; use only synthetic input |
| 4. Provider owner | Proposed `data/.../network/gemini` with fake transport | Golden REST request/response cases for current schema, pagination cap/repeated token, absent usage, unknown enums/parts, invalid base64/MIME, truncated/blocked/no-image output, 400/401/403/404/429/5xx, redirects, partial connection failure, timeout/cancel and zero automatic replay |
| 5. UI owner | Proposed generation/settings screens and ViewModels under `app` | Independent default-off controls, explicit metadata verification, full immutable preview, stale-preview reset, safe failure/usage display, process death clears approvals, no secret in state restoration or UI dumps |
| 6. Draft/asset owner | Existing format/repository integration plus agreed staging API | Acceptance only at expected revision; unknown values/originals preserved; invalid/rejected output changes no project; artwork requires explicit attach; cleanup is scoped and interruption-safe |
| 7. Independent testing owner + supervisor | JVM/AVD regression and evidence docs | Offline manual/import/edit/export still work with absent key/all grants off; network spy sees zero calls; mock leak sentinels absent from app logs, exported JSON, Room rows, ordinary preferences and saved state |

Transport tests must observe request attempt counts, not only final exceptions. Use a fake/mocked engine for deterministic cases and an isolated test HTTP server only with synthetic credentials to exercise redirects, slow/decompressed oversized bodies and connection ambiguity. Production-origin validation still runs in production construction. Test no-replay below the provider wrapper because an HTTP engine can otherwise retry independently. Verify cancellation after admission differs from cancellation before admission.

Credential instrumentation should inspect only test-owned records/aliases. Exercise process restart through two test phases; simulate invalidation using a deleted test alias and tampered envelope, label this as simulation. Hardware-bound key behavior, biometric/device-lock cases and actual device-transfer behavior need separate physical-device evidence. Do not expand those simulations into claims about real biometric invalidation or secure hardware.

Security verification uses known synthetic markers inserted through test UI/transport, then checks only the app's test-owned outputs/log capture for leaks; never scan user credential files or print environment variables. A fake API success verifies adapter behavior, not provider access. A live smoke call remains separately awaiting an explicitly user-configured in-app key and reviewed request; lack of that call does not block completing mock/AVD implementation.

## Unresolved integration decisions and limits

- **API/model evidence:** `responseFormat` is documented and discovery-confirmed, but this research has no authenticated model listing or live model/configuration response. Pin exact model records and endpoint support; expose only the adapter combinations with fixture coverage. Do not silently swap to Interactions because its guide is newer.
- **Dynamic capability gap:** models.list alone cannot fulfill automatic image-capability discovery. The exact-ID evidence registry plus live availability intersection is the proposed charter-compatible interpretation; the supervisor must record it before implementation.
- **Follow-up exactness:** preapproved immutable multi-call requests are straightforward; response-derived automatic follow-ups require a separately agreed disclosure contract. Keep explicit new previews until then.
- **Creation/assets:** accepted new generated documents and images need the same creation/asset contracts as the manual/binary work. Coordinate ownership rather than adding a second store or importing synthetic provenance.
- **Limits and privacy defaults:** finalize artwork budgets, capability refresh lifetime, request expiry, engine choice, and optional history semantics during the slice. Proposed conservative values above are not a verified provider compatibility matrix.
- **Android/provider verification:** no Keystore, HTTP generation, billing, cloud key revocation, physical hardware protection, or live image generation was tested by this planning task. Record each future mock/JVM/AVD/live outcome separately.
