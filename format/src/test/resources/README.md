# Foundation test fixture provenance

Baseline: [project kickoff charter](../../../../TavernForge_Project_Kickoff.html). The three `fixtures/st-*.json` files are exact copies of the materialized upstream helper objects in the [fixture manifest](../../../fixtures/manifest.json), pinned to SillyTavern commit `06bde939fb1e9c4c8d8641d810f0a916b5bce127`.

They retain the upstream [AGPL-3.0 notice](../../../fixtures/upstream/sillytavern/LICENSE). They contain empty helper fields, not real character content. The V3 envelope-only helper is a negative strict-export case, never a positive V3 conformance fixture.

The Kotlin tests derive negative cases and preservation sentinels from these fixtures. Their required V2 field/type and embedded-book expectations were independently read from [`spec_v2.md`](https://github.com/malfoyslastname/character-card-spec-v2/blob/8083fb388615ccbce768e97cbbd49d2b3214632c/spec_v2.md). Test-only arbitrary extension names and values exercise preservation; they are not new native card fields.
