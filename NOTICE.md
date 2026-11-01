# Copyright and third-party notices

Baseline: [TavernForge kickoff charter](TavernForge_Project_Kickoff.html). The user selected GPL-3.0-or-later on 1 November 2026.

TavernForge original source and project documentation: Copyright © 2026 Trinadh Thatakula, licensed under GPL-3.0-or-later. The complete GPL version 3 text in [LICENSE](LICENSE) is copied without modification from `../Thor/LICENSE`; the project notice in [README](README.md#license) explicitly permits version 3 or any later version. Thor-specific branding, component attributions and store-distribution claims are not TavernForge licensing terms.

The license of original TavernForge work does not replace the licenses of third-party material:

- Pinned SillyTavern reference source and derived helper fixtures in `format/fixtures` retain their AGPL-3.0 notice and upstream attribution. The original [upstream license](format/fixtures/upstream/sillytavern/LICENSE), [manifest](format/fixtures/manifest.json) and [fixture provenance](format/fixtures/README.md) remain authoritative for those files. The same fixture notice applies to byte-identical test-resource copies.
- V1/V2 and V3 format definitions are linked at exact revisions. Their specification text is not copied into production sources; independently authored synthetic cases and their provenance are identified in the fixture manifest.
- Gradle wrapper components and external Android/Kotlin dependencies retain their upstream licenses. The [runtime dependency inventory](docs/THIRD_PARTY_DEPENDENCIES.md) records 184 locked release-runtime coordinates and their published licenses. Full project and dependency texts are [bundled in the app](app/src/main/assets/open_source_notices.txt) and available through Settings. Build tooling and test-only material retain separate upstream terms. Production signing and distribution review remain release tasks.
