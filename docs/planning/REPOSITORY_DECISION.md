# Repository visibility and automation

Baseline: [TavernForge kickoff charter](../../TavernForge_Project_Kickoff.html), repository/distribution decisions. Decision recorded 1 November 2026.

The user explicitly authorized making [trinadhthatakula/TavernForge](https://github.com/trinadhthatakula/TavernForge) public and adding Dependabot. This supersedes the earlier private-until-community-build instruction. Repository visibility is separate from release readiness; the user subsequently selected GPL-3.0-or-later from sibling Thor; community release remains undecided.

The existing [GitHub Actions workflow](../../.github/workflows/android.yml) runs on pushes to main, pull requests and manual dispatch. It verifies fixtures, tests, Android lint and APK builds with the pinned toolchain, read-only repository permissions, no application credentials and no release-publishing step. Standard GitHub-hosted runners are used. Workflow logs and retained artifacts are accessible according to the repository's public visibility; they must contain synthetic test data, never user documents or credentials.

[Dependabot](../../.github/dependabot.yml) checks Gradle dependencies in the product and isolated compatibility probe weekly, and checks GitHub Actions weekly. Related toolchain dependencies and action updates are grouped into reviewable pull requests. Dependabot vulnerability alerts and automatic security-update pull requests are enabled. No automatic merge is configured. Dependency PRs must preserve or explicitly obtain approval to change the requested baseline and must pass the applicable compatibility checks; Dependabot does not establish cross-plugin compatibility by itself.

Before the visibility change, tracked credential/local-machine filenames and common API-token/private-key patterns were checked across existing Git history with no findings. This bounded check is evidence, not a universal secret-detection guarantee. The fixture corpus is synthetic or attributable upstream reference material with its notices retained. The later explicit licensing instruction is recorded in [LICENSE](../../LICENSE), [README](../../README.md#license), and [notices](../../NOTICE.md).
