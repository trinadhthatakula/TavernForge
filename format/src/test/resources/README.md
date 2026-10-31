# JSON test fixture provenance

Baseline: [project kickoff charter](../../../../TavernForge_Project_Kickoff.html). All eight JSON resources are byte-identical copies of cases in the [fixture manifest](../../../fixtures/manifest.json), verified by its audit. The `st-v1-empty-fields`, `st-v2-empty-fields`, and `st-v3-envelope-only` resources are materialized upstream helper objects pinned to SillyTavern commit `06bde939fb1e9c4c8d8641d810f0a916b5bce127`.

They retain the upstream [AGPL-3.0 notice](../../../fixtures/upstream/sillytavern/LICENSE). They contain empty helper fields, not real character content. The V3 envelope-only helper is a negative strict-export case, never a positive V3 conformance fixture.

The Kotlin tests derive negative cases and preservation sentinels from these fixtures. Their required V2 field/type and embedded-book expectations were independently read from [`spec_v2.md`](https://github.com/malfoyslastname/character-card-spec-v2/blob/8083fb388615ccbce768e97cbbd49d2b3214632c/spec_v2.md). Test-only arbitrary extension names and values exercise preservation; they are not new native card fields.

`spec-v3-minimal`, `spec-v3-rich`, and `spec-lorebook-v3` are independently authored positive JSON fixtures from [CCv3 revision `f3a86af`](https://github.com/kwaroran/character-card-spec-v3/blob/f3a86af019fbd99f788f7a1155f399655b34ab35/SPEC_V3.md). `spec-character-book-v2` uses the bare CharacterBook shape from the pinned V2 contract. `st-world-info-synthetic` uses the [pinned native World Info field definition](https://github.com/SillyTavern/SillyTavern/blob/06bde939fb1e9c4c8d8641d810f0a916b5bce127/public/scripts/world-info.js). These five fixtures are synthetic test data, not actual upstream exports. URLs use the reserved `example.invalid` domain; no assets are fetched or executed.

The manifest records exact source and fixture hashes, authorship distinctions, and source license notices. See [JSON verification](../../../../docs/testing/JSON_FORMAT_VERIFICATION.md) for executed results and limitations.
