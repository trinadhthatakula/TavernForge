# TavernForge

Create characters. Build worlds. Export for SillyTavern.

TavernForge is a planned FOSS Android companion for importing, manually authoring, validating, organizing, and exporting character cards and lorebooks. Optional Gemini assistance uses the user's own key. All workflows share a local project model and strict, version-aware format engine.

The [kickoff charter](TavernForge_Project_Kickoff.html) is the source of truth for scope and requirements. Start with [current project status](docs/STATUS.md) for assignments, discovery results, and pending decisions. Contributors and agents should read [AGENTS.md](AGENTS.md).

## Current phase

Kickoff discovery recovered after a power interruption. The requested toolchain passes an [isolated Android compatibility probe](tools/compatibility-smoke/README.md) and three JVM integration checks; product modules and workflows are not implemented yet. The user has [approved a hybrid UI direction](docs/DESIGN_DECISION.md): C’s library, A’s native controls, and B’s expanded inspector. [Explore the design references](docs/design-comparison.html).

## Product principles

- Local ownership, offline import/manual workflows, and no mandatory account or backend.
- Version-specific SillyTavern compatibility and preservation of unknown data.
- Separate opt-in permissions for Gemini text, images, and follow-up calls.
- Private credentials, request transparency, and explicit acceptance of generated drafts.

The open-source license and distribution destination remain undecided.
