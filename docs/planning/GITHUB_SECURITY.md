# GitHub security configuration

Baseline: [TavernForge kickoff charter](../../TavernForge_Project_Kickoff.html). The user authorized all applicable free public-repository security features on 1 November 2026; see the [repository decision](REPOSITORY_DECISION.md). No paid AI/security feature is authorized.

## Repository settings

Live REST/UI checks confirm public visibility, dependency graph, Dependabot vulnerability alerts and security-update PRs, grouped security updates, secret scanning, push protection, private vulnerability reporting, and standard Copilot Autofix enabled. The user enabled the separate Dependabot malware-alert toggle and confirmed it in the conversation. CodeQL must successfully analyze code before Autofix can suggest fixes.

The default Actions token is read-only and cannot approve PRs. External-contributor workflow runs require approval. Main is protected against force pushes and deletion, including administrators, and PR conversations must be resolved. Automatic merging of dependency upgrades is not enabled; the user separately authorized reviewing and merging the current Dependabot batch.

## Checked-in automation

- [Android verification](../../.github/workflows/android.yml): product tests, fixture audit, app/data lint, APK build, isolated compiler probe, and public test/APK artifacts with seven-day retention. Main-branch APKs receive provenance attestations.
- [CodeQL](../../.github/workflows/codeql.yml): Java/Kotlin, Actions and Python security-extended analyses on push, PR and weekly schedule. Kotlin analysis builds with the pinned Android toolchain. Standard Copilot Autofix is free and already enabled; no paid agentic fixer is configured.
- [Dependency review](../../.github/workflows/dependency-review.yml): PR vulnerability/license review, failing on low-or-higher vulnerability severity, without posting comments.
- [Dependency submission](../../.github/workflows/dependency-submission.yml): automatically submits resolved product and compatibility-probe Gradle graphs on main and weekly, using the explicit JDK/SDK setup. This custom workflow replaces the need for GitHub's separate managed automatic-submission toggle; that toggle is not enabled to avoid a duplicate unconfigured build.
- [Dependabot](../../.github/dependabot.yml): weekly Gradle and Actions PRs. All workflow actions use reviewed full commit SHAs. SHA-only enforcement is enabled after the pinned workflows reach main.
- [Security policy](../../SECURITY.md): private reporting route, development support expectations, and synthetic reproduction guidance.

Workflow configuration is not proof of a successful scan. Record the first successful analysis, dependency submission and artifact attestation runs in current status before claiming operational coverage.

## Free-only boundaries

GitHub's feature-specific documentation restricts [generic/non-provider secret scanning](https://docs.github.com/en/code-security/how-tos/secure-your-secrets/detect-secret-leaks/enabling-secret-scanning-for-generic-patterns) and [partner validity checks](https://docs.github.com/en/code-security/how-tos/secure-your-secrets/customize-leak-detection/enable-validity-checks) to eligible organization Secret Protection plans. Both remain disabled for this personal repository; attempts to set them through the general repository API did not enable them. Paid AI Scan and Agentic Autofix consume credits and remain off. Standard Copilot Autofix suggestions and public-repository artifact attestations are free; see [Autofix](https://docs.github.com/en/code-security/concepts/code-scanning/autofix-for-code-scanning) and [attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations).
