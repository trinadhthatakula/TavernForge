# Build dependency security patches

Baseline: [project kickoff charter](../../TavernForge_Project_Kickoff.html). The user authorized free GitHub security features and autonomous implementation; [status](../STATUS.md) records verification. This policy preserves the approved AGP 9.5.0, Gradle 9.8.2, Kotlin 2.4.21 and Android dependency baseline while patching inherited build-tool libraries.

On 1 November, GitHub reported 18 open Dependabot alerts: nine findings in each product/probe submitted dependency graph. Zero code-scanning or secret-scanning alerts were open. The dependency findings include critical Bouncy Castle certificate-constraint handling and high-severity parser/resource-allocation issues. They are not dismissed merely because they originate in build tools.

## Exact pins and origins

| Artifact | Previous resolved version | Security pin | Origin |
| --- | --- | --- | --- |
| `com.google.guava:guava` | 33.4.0-jre; Room processor 33.2.1-jre | 33.7.2-jre | AGP/common/lint; Room compiler-processing |
| `org.bouncycastle:bcprov-jdk18on` | 1.80.2 | 1.85 | builder/apkzlib/sdk-common |
| `org.bouncycastle:bcpkix-jdk18on` | 1.80.2 | 1.85 | builder/apkzlib/sdk-common |
| `org.bouncycastle:bcutil-jdk18on` | 1.80.2 | 1.85 | bcpkix; aligned with the provider |
| `org.apache.commons:commons-lang3` | 3.16.0 | 3.18.0 | sdk-common/sdklib/repository → commons-compress 1.27.1 |
| `org.apache.httpcomponents:httpclient` | 4.5.6 | 4.5.14 | sdklib → httpmime; AGP crash already requests 4.5.14 |
| `org.bitbucket.b_c:jose4j` | 0.9.5 | 0.9.6 | AGP → bundletool 1.18.3 |
| `org.jdom:jdom2` | 2.0.6 | 2.0.6.1 | AGP → jetifier-processor 1.0.0-beta10 |

All eight exact POM versions were confirmed available at Maven Central before resolution. Dependabot alert endpoints identify the affected ranges and patched minima; Bouncy Castle findings include GHSA-9pwp-9qqc-pr26, and Guava includes GHSA-xxph-c9ww-hj94. The 18 findings cover the same inherited artifacts in both independent Gradle builds.

## Enforcement and maintenance

The shared [settings policy](../../gradle/dependency-security.settings.gradle.kts) registers before project evaluation. It applies exact version selection to project buildscript classpaths and all project dependency configurations, covering plugin resolution, lint tools and Room/KSP processors. Both root settings files apply the same policy; application-only constraints would leave these separate tool classpaths vulnerable. Product and probe catalog/toolchain versions are unchanged.

The current affected artifacts are build/lint/processor dependencies, not dependencies in the application's locked runtime configurations. This reduces shipped runtime exposure but does not eliminate the need to patch build tooling. Regenerated lockfiles and resolved graph checks, successful product/probe builds, lint, signing and tests are required before declaring this mitigation verified.

Exact `useVersion` selections also override future newer requests. Revisit this table whenever AGP, Room, Kotlin, lint or Dependabot dependencies change; update or remove overrides when upstream selections are patched and compatible. Do not retain an override that holds a future dependency below its required secure version. Avoid upgrading the AGP/Kotlin baseline solely to hide an inherited-library alert.

After PR #9 merged as `efb53bc`, both corrected default-branch dependency snapshots passed in [run 41320585757](https://github.com/trinadhthatakula/TavernForge/actions/runs/41320585757). GitHub then reported all 18 alerts fixed and zero open dependency, code-scanning or secret-scanning alerts. No alerts were dismissed. Future findings must also be resolved rather than dismissed to make the count green. Final evidence is recorded in [autonomous verification](../testing/AUTONOMOUS_VERIFICATION.md).

## Local verification

The product rebuild passed 348 JVM tests, APK/instrumentation assembly and clean lint in 78s. The isolated probe passed 3 tests, assembly and clean lint in 21s. Both buildEnvironment graphs resolve every affected occurrence to the versions above; product lint/Room processor locks match. An offline actual debugRuntimeClasspath report contains none of these eight coordinates. See [verification](../testing/AUTONOMOUS_VERIFICATION.md) for hosted snapshots and final alert state.
