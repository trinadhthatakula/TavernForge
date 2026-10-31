# Format fixtures and provenance

Baseline: [TavernForge kickoff charter](../../TavernForge_Project_Kickoff.html). Updated 31 October 2026. This corpus combines pinned upstream reference/helper material with independent synthetic contract cases. Execution evidence is recorded in [JSON verification](../../docs/testing/JSON_FORMAT_VERIFICATION.md); provenance alone is not an interoperability claim.

## Current inventory

[manifest.json](manifest.json) pins source revisions, URLs, derivation, license notices and SHA-256 for every reference/case. SillyTavern source is pinned to [`06bde939fb1e9c4c8d8641d810f0a916b5bce127`](https://github.com/SillyTavern/SillyTavern/tree/06bde939fb1e9c4c8d8641d810f0a916b5bce127), observed on its `release` branch.

| Local artifact | Origin | Intended use |
|---|---|---|
| `upstream/sillytavern/tests/tavern-card-validator.test.js` | Exact upstream test bytes | Reference for three helper objects and detector behavior; not wired into a local runner |
| `upstream/sillytavern/src/character-card-parser.js` | Exact upstream source bytes | PNG keyword, precedence and write behavior evidence; not imported into app code |
| `upstream/sillytavern/LICENSE` | Exact upstream license bytes | Preserve AGPL-3.0 notice for reference sources and derived fixture material |
| `cases/st-v1-empty-fields.json` | JSON materialization of upstream `makeV1Card()` | Six empty-string fields; V1 strict validation/export case |
| `cases/st-v2-empty-fields.json` | JSON materialization of upstream `makeV2Card()` | V2 strict case with empty authoring content and correct arrays/object |
| `cases/st-v3-envelope-only.json` | JSON materialization of upstream `makeV3Card()` | Upstream detector-positive but **strict V3 negative** because required data is absent |
| `cases/spec-v3-minimal.json`, `cases/spec-v3-rich.json` | Independent synthetic data from pinned V3 contract | Required fields; optional metadata/assets; embedded lorebook; exact no-op and unrelated-edit preservation |
| `cases/spec-lorebook-v3.json` | Independent synthetic data from pinned V3 contract | Official standalone `lorebook_v3` envelope, no invented `spec_version` |
| `cases/spec-character-book-v2.json` | Independent synthetic data from pinned V2 contract | Bare array-entry CharacterBook, without implicit conversion to native World Info |
| `cases/st-world-info-synthetic.json` | Independent synthetic data from pinned native field definitions | Object-keyed entries, nullable settings and enum coverage |

The JSON cases are derived helper outputs, not captured user cards or independent interoperability proof. No user content, credentials, artwork or unreviewed binary assets are included. TavernForge's original work is GPL-3.0-or-later; these third-party references and derivatives retain their own notices. Keep them separate from application source and assess distribution obligations before a release; see [notices](../../NOTICE.md).

## Pinned definitions and important discrepancies

- V1/V2 definitions: [`malfoyslastname/character-card-spec-v2@8083fb388615ccbce768e97cbbd49d2b3214632c`](https://github.com/malfoyslastname/character-card-spec-v2/tree/8083fb388615ccbce768e97cbbd49d2b3214632c), especially `spec_v1.md` and `spec_v2.md`. The repository metadata did not identify a license during discovery; link to it rather than copying its text into this corpus.
- V3 definition: [`kwaroran/character-card-spec-v3@f3a86af019fbd99f788f7a1155f399655b34ab35/SPEC_V3.md`](https://github.com/kwaroran/character-card-spec-v3/blob/f3a86af019fbd99f788f7a1155f399655b34ab35/SPEC_V3.md), with MIT license in that repository. Positive fixtures are independent synthetic data; actual supporting-upstream export comparison remains pending.
- V3 prose describes `secondary_keys` ambiguously as comma-separated strings while its explicit TypeScript interface uses `Array<string>`; tests follow that interface and inherited V2 shape. `constant` and `selective` remain optional properties in that interface. Raw decorators and asset metadata are preserved without executing lorebook matching or fetching media.
- Upstream validator tests establish what the upstream detector accepts; they are not an exhaustive schema oracle. Its V3 helper has only a version envelope and empty `data`. Do not use this object as the positive V3 export golden.
- At the pinned SillyTavern revision, PNG reading inspects `tEXt`, matches keywords case-insensitively and prefers the first `ccv3` over the first `chara`. Duplicate/conflicting/malformed payload behavior needs explicit TavernForge diagnostics and preservation tests.
- The PNG writer's comment says it writes only `chara`, but its executed code also creates a `ccv3` chunk by changing the supplied JSON's `spec` and `spec_version`. Code and comment disagree. This does not prove that the synthesized payload satisfies the complete V3 specification; do not copy the behavior blindly.

## Acquisition backlog before support claims

| Needed evidence | Acquisition and expected review | Blocking claim |
|---|---|---|
| Complete V2 with embedded book/unknown extensions | Capture an export from the pinned upstream using locally authored synthetic content; record build/steps/license/checksum | Live upstream interoperability beyond synthetic contract tests |
| Complete strict V3 with V3-only fields | Capture a supporting upstream export; compare with the reviewed synthetic positives | Live upstream interoperability beyond synthetic contract tests |
| Real PNG `chara`, `ccv3`, both and metadata-free | Export an original tiny generated artwork from pinned upstream; retain original image and exact chunk inventory | PNG interoperability |
| Standalone SillyTavern World Info | Export empty and populated worlds with locally authored entries from pinned upstream; include native indexed entries and settings | Live upstream interoperability beyond synthetic native-field tests |
| Legacy V1 representative | Obtain attributable export/fixture and record exact provenance, conversion expectations | V1 import support beyond helper shape |
| Adversarial derivatives | Derive from approved positive fixtures with one documented mutation and expected validation phase/path; preserve source and derivation | Safe malformed/oversized input behavior |

Record exact exporter revision, commands/steps, source authorship/license, target version, original hash, derivation and expected result. Review cases into `manifest.json`; never refresh references from an unpinned branch automatically. Do not treat user-authored cards scraped from public sites as freely redistributable fixtures.

## Reproducible discovery audit

From repository root:

```sh
python3 format/fixtures/verify_manifest.py
```

This uses the Python standard library, checks every listed file's SHA-256 and path/revision metadata, parses the JSON cases, and confirms the materialized objects match the inspected upstream helper declarations. It makes no network calls and executes no vendored JavaScript. A successful audit is **not** an adapter, PNG, Android or SillyTavern interoperability test.

The [recovery verification report](../../docs/testing/RECOVERY_VERIFICATION.md) records the historical post-power-failure audit. The current audit also verifies all eight JVM resources remain byte-identical to their manifested cases. Positive JSON coverage is tracked in the current verification report; PNG and live upstream interoperability remain pending.
