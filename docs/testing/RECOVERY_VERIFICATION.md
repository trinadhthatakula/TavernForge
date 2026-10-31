# Recovery verification

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). Verification performed after the reported power failure, 31 October 2026, approximately 13:29 UTC. This report covers saved discovery artifacts only.

## Results actually observed

| Check | Result | Meaning / limit |
| --- | --- | --- |
| `python3 format/fixtures/verify_manifest.py` | **PASS**, exit 0: 6 pinned files, 3 helper objects | Local hashes, metadata, JSON readability, and transcribed helper values match. No application adapter is exercised. |
| Download each `upstream-reference` entry from its immutable `raw_url` and compare bytes | **PASS**, 3 of 3 exact matches | The saved validator tests, PNG parser source, and license match the pinned upstream commit. |
| Read pinned validator implementation and V3 specification | **Verified by source inspection** | The upstream V3 detector is weaker than the strict specification; no upstream test runner was executed. |
| Inspect PNG read/write source | **Verified by source inspection** | Reader chooses first case-insensitive `ccv3`, then `chara`; writer tries to add both. No PNG was parsed or exported locally. |
| TavernForge adapter, PNG, Android, security, and interoperability suites | **NOT RUN** | Product implementation/tests do not exist in this recovered fixture scope. The separate compatibility smoke project is owned by Agent 2. |

Environment: Python 3.12.9, macOS 27.2 arm64. There was no committed application revision to identify at audit time. Fixture manifest SHA-256: `b43cd9dd19d2d1542f6170f21480f50ec973b1af3f3f2158e08c7d3bad6a436e`.

## Pinned evidence

All three vendored references match SillyTavern revision [`06bde939fb1e9c4c8d8641d810f0a916b5bce127`](https://github.com/SillyTavern/SillyTavern/tree/06bde939fb1e9c4c8d8641d810f0a916b5bce127); their per-file SHA-256 values are in [the manifest](../../format/fixtures/manifest.json).

- [`TavernCardValidator.js`](https://github.com/SillyTavern/SillyTavern/blob/06bde939fb1e9c4c8d8641d810f0a916b5bce127/src/validator/TavernCardValidator.js): inspected remote bytes, SHA-256 `a0eba17805b004f5ab6927393e1f16fc1b4b6cd128d905046ece72f66673e1d4`. Its `validateV3()` delegates to envelope/version/data-object checks. The saved upstream tests explicitly expect the `{spec, spec_version, data: {}}` helper to pass. This is not complete field validation. Source also reveals that JavaScript arrays satisfy its data-object check and nonnumeric versions can evade its numeric range comparisons; FMT-05 includes those negative strict-schema cases. These observations come from source inspection, not a locally executed upstream suite.
- [`SPEC_V3.md`](https://github.com/kwaroran/character-card-spec-v3/blob/f3a86af019fbd99f788f7a1155f399655b34ab35/SPEC_V3.md): inspected remote bytes, SHA-256 `3c472a16eeda5d018837e90d30fce2816b0982f07f4dba14c8fcc89aa11fe76c`. The CharacterCardV3 interface requires the inherited character data fields and `group_only_greetings`; empty `data` cannot be a strict positive fixture. Its PNG convention is UTF-8 JSON → base64 in `tEXt` named `ccv3`, with `ccv3` preferred when both recognized chunks exist.
- The same specification distinguishes future-version import handling from writing the exact `3.0` target. Preserve future/unknown data and surface unsupported semantics; do not automatically reject or rewrite all newer imports merely to enforce a target export version.
- The saved [`character-card-parser.js`](../../format/fixtures/upstream/sillytavern/src/character-card-parser.js) has a stale comment saying only `chara` is written. Executed code always adds `chara` and attempts `ccv3` after replacing the envelope identifiers. That replacement alone does not establish strict V3 validity.

## Reproduce the reference-byte comparison

From the repository root, after the offline audit:

```sh
python3 - <<'PY'
import hashlib
import json
import urllib.request
from pathlib import Path

root = Path('format/fixtures')
manifest = json.loads((root / 'manifest.json').read_text())
for entry in manifest['entries']:
    if entry['kind'] != 'upstream-reference':
        continue
    with urllib.request.urlopen(entry['raw_url'], timeout=30) as response:
        remote = response.read()
    local = (root / entry['path']).read_bytes()
    if remote != local or hashlib.sha256(remote).hexdigest() != entry['sha256']:
        raise SystemExit(f"FAIL: {entry['path']}")
    print(f"PASS: {entry['path']} ({len(remote)} bytes)")
PY
```

This downloads official pinned reference bytes, compares them in memory, writes nothing, and executes no upstream JavaScript.

## Remaining gaps and licensing

No saved file was found corrupt or incomplete. The corpus still lacks a complete positive V3 card, real PNG exports, and standalone World Info exports; the [acquisition backlog](../../format/fixtures/README.md#acquisition-backlog-before-support-claims) remains blocking for those support claims. All [application test cases](TEST_STRATEGY.md) remain planned.

The upstream AGPL-3.0 license is present and byte-verified. References and derived helper fixtures remain isolated from application code. The V3 repository's MIT license was independently retrieved at its pinned revision (SHA-256 `9805dc6bf59dcf8d9eaedc8987f2798dc434bc3c8e6dafbbf23eb2147d74db95`); its specification is linked, not vendored. This inspection does not select TavernForge's project license or conclude that a future release has satisfied its distribution obligations. The V1/V2 specification license assessment and release notices remain for the planning/release review.
