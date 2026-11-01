# Gemini foundation implementation

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html), section 05. Design contract: [Gemini security and API plan](../planning/GEMINI_SECURITY.md). Integration status and verified build evidence belong in [project status](../STATUS.md).

This increment implements provider/policy/credential foundations in the isolated `/tmp/TavernForge-next` checkout. It uses the existing modules and pinned Ktor 3.6.1. The supervisor owns Gradle/catalog/app integration and execution. This document does not claim live Google API access, model availability, successful billing, hardware-backed key protection, or release readiness.

## Owned paths and contracts

| Path/package | Implemented responsibility |
| --- | --- |
| `core/model/.../generation` | Independent default-off grants, immutable request bytes/target, one-use approvals, safe error codes, provider usage observations |
| `data/.../security` | Credential availability flow, AndroidKeyStore AES-GCM encryption, bounded versioned envelope, atomic file replacement, local deletion tombstone, synchronous lease revocation and buffer clearing |
| `data/.../network/gemini` | Exact-ID evidence registry intersected with runtime model listing, current GenerateContent REST request building, token-count previews, bounded Ktor/OkHttp transport, untrusted text/image staging |
| Corresponding unique JVM/Android test classes | Policy/race/failure tests, synthetic fake-provider responses, Android Keystore and native image decoding checks |
| [Contract fixtures](../../data/src/test/resources/gemini/README.md) | Minimal public wire/model facts, source URLs/revision/date and immutable fixture hashes |

No repository/database/format-engine source or app UI is owned by this assignment. Generated values are never written to a project by the provider. The app must validate/review a generated draft and use the canonical creation or expected-revision edit operation after explicit acceptance. Image bytes remain staging content until the shared asset workflow accepts them.

## UI integration API

Construct `AndroidGeminiCredentials(applicationContext)`, call its suspend `refresh()`, and observe `state: StateFlow<CredentialSnapshot>`. `update(CharArray)` and `delete()` are local operations and never verify keys over the network. The update method clears its supplied buffer. ViewModels should retain only `CredentialSnapshot`, never a key or decrypted value. Do not place the key field in saved state, ordinary preferences or logs.

Construct `GeminiService(credentials)` for the owner lifecycle and close it on disposal. Tests inject a `GeminiTransport`, image verifier and clock. Public operations:

```kotlin
service.grants()
service.updateGrants(GenerationGrants(text, image, automaticFollowups))
service.prepareModelList(pageToken = null)
service.prepareText(model, prompt, target, maxOutputTokens = 1024, jsonSchema = null)
service.prepareImage(model, prompt, target, maxOutputTokens = 4096)
service.prepareTokenCount(generationRequest)
service.approve(preparedRequest)
service.execute(preparedRequest, approval, currentTarget)
```

`GenerationTarget(projectId, revision, label)` captures the current base. Both ID and revision are absent for a new draft. The caller passes its current target again at execution; existing-project revisions must be refreshed from the editor/repository before dispatch. Preparation throws `GenerationException(code)` for safe local failures; execution returns `GeminiResult.Failure(code, usage?)`. No raw provider/credential exception is a UI message.

Display `PreparedRequest.provider`, `model`, `purpose`, `target`, `limits`, `callCount` and the complete `bodyPreview` (expandable if needed). For metadata GET, there is no project body; show metadata-only purpose and one page/call. Approval comes from the user's explicit review action. Do not rebuild a prompt after review or log `bodyPreview`.

Results are `Models`, `TextDraft`, `ImageDraft`, `TokenCount`, or `Failure`. `Models.models` is a read-only list with exact resource names and documented adapter capabilities; `nextPageToken` can prepare another separately reviewed metadata page. A `GeminiModel` is selectable only in the service/credential listing that produced it. Do not construct models from display names. `GeneratedImage.bytes()` returns an owned copy, with validated MIME/dimensions. Candidate text/artwork is not saved project data.

## Authorization and lifecycle invariants

All three grants initially equal false. Metadata verification is a separately reviewed metadata-only action; it does not enable generation. Text/card/lore/field requests require text permission. Artwork requires image permission; incidental caption does not become a field rewrite. Counting input is another reviewed call with the corresponding text/image grant.

`GenerationPolicy` binds an approval to policy identity, request identity, exact body hash, key generation, grant generation, target revision and a five-minute monotonic expiry. Grant changes invalidate existing previews, even if toggled back. Both approval and prepared request are consumed at admission, so two approval tokens for the same prepared request cannot dispatch twice. Failed admitted attempts stay consumed. Process death discards all approvals and pending requests.

Before admission, `GeminiService` revalidates the exact model record under the same lock used to replace model-list results. Refreshing/replacing that record invalidates earlier approvals. Cached models expire after 24 hours and are not persisted across process death. The service rejects stale keys/targets, unknown capabilities and arbitrary model substitutions. Turning grants off cancels affected active execution jobs. After admission, cancellation/network failure conservatively reports `OUTCOME_UNKNOWN`; previously sent bytes cannot be recalled.

Credential leases share an admission monitor with local mutation. Update/delete/refresh starts a mutation barrier and synchronously cancels registered lease jobs. No new lease can enter while the barrier is active. Each lease holds the vault mutex through its cancellable transport action and clears its buffers before releasing the mutex, so storage mutation cannot overtake an active decrypted lease. This correctness does not depend on asynchronously observing a StateFlow. Service-level credential observation provides additional cancellation/cache cleanup.

The policy can represent a separately reviewed exact static follow-up, but this service currently exposes no automatic workflow executor. Response-derived repairs, continuations and image prompts always need a new prepared request/preview. The follow-up switch is not permission to send arbitrary next requests.

## Credential storage and limitations

Each successful update gets a fresh UUID identity and AES-256 AndroidKeyStore alias restricted to encryption/decryption with GCM/NoPadding and randomized encryption. Cipher-generated IVs are stored with the encrypted bytes and 128-bit GCM tag. AAD binds provider, envelope version and credential ID. Plaintext is never persisted. The binary envelope validates version, UUID, lengths and total size before decrypting; usable keys are bounded printable ASCII without spaces/newlines.

The production envelope is `noBackupFilesDir/gemini-credentials-production/credential.bin`. Writes use a synced temporary file, atomic replacement and directory sync. If a commit/fsync fails ambiguously, the vault marks itself unavailable and retains aliases needed for recovery; it does not falsely claim the old or new credential is active. A later refresh authenticates the committed record and cleans unused owned aliases. A pre-commit encryption failure preserves the previous active credential.

Deletion first writes a durable tombstone, then removes owned aliases. Interrupted alias cleanup cannot turn that tombstone back into an active key. A cleanup failure keeps local access unavailable and can be retried. Missing aliases, tampered ciphertext/AAD or invalid envelope versions are unavailable states, with no plaintext fallback or silent key recreation. The app must provide re-enter/delete recovery. Local deletion does not revoke the user's Google key remotely.

Mutable decrypted byte/character arrays are cleared on success, cancellation and action failure. The HTTP header needs an immutable JVM string; guaranteed zeroization of all runtime copies is not claimed. Native Keystore encryption protects storage; it cannot protect a credential already accessible to a compromised app process. StrongBox, biometric key authorization and hardware attestation are not claimed. Existing app backup/device-transfer exclusions and masked non-restored key UI remain app integration responsibilities.

## Provider and parsing limits

Production requests use fixed HTTPS origin `generativelanguage.googleapis.com`, validated API paths, `x-goog-api-key` header, default TLS verification and no URL credentials. Ktor redirects are disabled; OkHttp connection-failure retries, HTTP redirects and SSL redirects are disabled. A per-call guard also rejects a second physical HTTP send, including internal explicit `Retry-After` follow-ups that retry configuration alone may permit. No logging, authenticator, tool execution, hidden key verification, file upload, remote image fetch or WorkManager retry is installed.

The transport sends `Accept-Encoding: identity`, rejects unexpected encoded/non-JSON success responses, checks declared length and counts actual stream bytes. It discards error bodies. Defaults: 256 KiB request, 2 MiB text response, 512 KiB metadata/count response, 24 MiB image response including base64; 30-second connect and 120-second request/socket timeouts. Artwork is limited to one image, 16 MiB image-file bytes and 16 megapixels. Native decoding happens only after MIME signature/dimension checks; decoding and bounded JSON parsing run away from the UI thread.

The format engine's public bounded JSON parser rejects duplicate names, malformed UTF-8, deep/oversized trees and excessive collections before provider JSON becomes a map. Only one complete candidate is accepted. Blocked/truncated/continuation/unknown finish reasons are explicit failures. Non-thought text is joined in part order; candidates are never merged. Tool calls, code and remote file parts are rejected and cannot execute. Invalid/oversized image base64/MIME fails before staging. Structured-output requests use the reviewed `responseFormat.text` schema; output still requires the app's canonical semantic validation.

Usage preserves supplied input/output/thinking/cache/tool/total fields and modality details separately. Missing remains absent; output is never computed by subtracting input, and cached tokens are not double-added. Token-count results are labeled input counts. Unknown safe enums are displayed as unknown. No exact monetary charge is invented, and no request history is persisted.

The initial registry includes exact reviewed IDs `gemini-3.8-flash`, `gemini-2.5-flash`, and `gemini-3.1-flash-image`, intersected with returned `generateContent` support. Public `Model` has no output-modality field; model names and image-input support are not evidence of image output. The 2.5 public page limits access for new users; no availability or fallback is inferred. The image model's separate text operation is intentionally disabled even though captions are supported. Wire fixtures establish field shapes, not live per-model compatibility or credential access.

## Test inventory and execution boundary

Tests use only clearly invalid synthetic non-credentials. Network tests use Ktor MockEngine or fake transports and never contact Google. No real key, `.env` file, credential file, environment value or user document was inspected.

| Test class | Coverage |
| --- | --- |
| `GenerationPolicyTest` | All eight grant combinations; exact request/policy/key/target/grant binding; expiry; double/concurrent dispatch; owned body bytes; defaults/limits |
| `CredentialVaultTest` | Standard JVM AES-GCM contract; envelope restart; IV/key rotation; tampering/missing alias; cleanup/commit failures; input clearing; action failure; update/delete cancellation and wiping |
| `GeminiServiceTest` | Exact-ID capability intersection; immutable REST wire; refreshed-model invalidation; explicit token counting; HTTP/raw-error safety; malformed/blocked/truncated/tool responses; nullable usage; image staging; repeated pagination; cancellation and bounded outcomes |
| `KtorGeminiTransportTest` | Header-only authentication; redirect/error-body discard; actual/declared stream bounds; MIME/encoding rejection; destination validation |
| `GeminiReplayGuardTest` | Loopback-only synthetic 503/Retry-After server verifies the production OkHttp send guard prevents a second HTTP request |
| `GeminiContractFixtureTest` | Minimal public wire/model hashes, revision/field names and registry consistency |
| `GeminiCredentialDeviceTest` | Real AndroidKeyStore lifecycle/tampering/missing alias; noBackup path/plaintext absence; revoked lease wiping; native image decoder MIME/pixel checks |
| `GeminiCredentialRestartDeviceTest` | Two ordered storage/reopen phases; separate instrumentation invocations are required to claim process restart |

The supervisor runs Gradle and records results. Suitable existing-module checks are `:core:model:test`, `:data:testDebugUnitTest`, `:data:lintDebug`, and `:data:assembleDebugAndroidTest`, alongside app integration checks. No passing result for the new tests is asserted here until those executions complete.

For real restart evidence on the authorized existing AVD, run the two `GeminiCredentialRestartDeviceTest` methods in separate `am instrument` invocations: `phase1StoreSyntheticCredential`, then `phase2VerifyAndRemoveSyntheticCredential`. Both target only the test-owned `instrumentation-restart-only` namespace. Running the entire class once exercises fresh vault instances but does not by itself prove process death. All other credential instrumentation uses unique UUID namespaces and cleans them afterward.

Mock tests do not prove TLS behavior on a real network, provider configuration compatibility, account quota/billing, physical hardware security, biometric invalidation, actual backup transfer or end-to-end user consent UI. These remain separately tracked integration/device/provider checks. The first live smoke call must use a user-entered in-app credential and its exact reviewed request; it is not required for credential-free CI.
