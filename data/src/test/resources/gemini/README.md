# Gemini contract evidence

Baseline: [project kickoff charter](../../../../../TavernForge_Project_Kickoff.html). See the [Gemini plan](../../../../../docs/planning/GEMINI_SECURITY.md).

These are minimal public API/model facts reviewed on 2026-11-01, not authenticated responses, real credentials, user content, or evidence of model availability. Runtime model listing must intersect these exact IDs and method support. Unknown capabilities remain unavailable. Model-page facts were transcribed into an original factual record; no marketing copy or SDK sample is included.

- `wire-contract.json`: extracted names, types, references and enums from Google's public v1beta discovery revision `20261030`. Full canonical source SHA-256: `d150bc5dd4bc06d5fc034caad0d87fe2f4287864e5caef93b658a8d1a1cb1d24`. This minimal fixture SHA-256: `4f4b4873d0baf9835f38bcc13425055e3fce202c78e3fb351a884c88cd1197ba`.
- `model-capabilities.json`: exact official page URLs and update dates for Gemini 3.8 Flash, 2.5 Flash and 3.1 Flash Image. Fixture SHA-256: `89ddd4a8159b843db4b044a7711ec33c4f53906fb719491d3a0fc3a2a9379c89`.

The model fixtures document facts, while the adapter intentionally restricts supported operations further: 3.1 Flash Image is available only for artwork plus incidental caption, not separate field/card text generation. Google's 2.5 Flash page limits access to previously active users; it is never a fallback for unavailable newer models.

`GeminiContractFixtureTest` checks these pins and matching registry facts. Public sources are mutable: review changes and update tests/pins deliberately. Mock-generated JSON in the test classes is original synthetic test data; it is not a recorded Google success response. No test makes a real Google request.
