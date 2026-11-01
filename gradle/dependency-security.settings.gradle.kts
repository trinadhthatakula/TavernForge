// Build-tool transitive fixes; keep AGP/Kotlin/Gradle and the Android runtime baseline unchanged.
// Rationale and advisory evidence: docs/planning/DEPENDENCY_SECURITY.md.
val patchedVersions = mapOf(
    "com.google.guava:guava" to "33.7.2-jre",
    "org.bouncycastle:bcprov-jdk18on" to "1.85",
    "org.bouncycastle:bcpkix-jdk18on" to "1.85",
    "org.bouncycastle:bcutil-jdk18on" to "1.85",
    "org.apache.commons:commons-lang3" to "3.18.0",
    "org.apache.httpcomponents:httpclient" to "4.5.14",
    "org.bitbucket.b_c:jose4j" to "0.9.6",
    "org.jdom:jdom2" to "2.0.6.1",
)

// Register before project evaluation so plugin classpaths and later lint/processor configurations
// receive the same reproducible patches in both the product and compatibility probe builds.
gradle.beforeProject {
    val enforce: Action<Configuration> = Action {
        resolutionStrategy.eachDependency {
            patchedVersions["${requested.group}:${requested.name}"]?.let { patched ->
                useVersion(patched)
                because("Pinned security fix for an inherited build-tool dependency; see DEPENDENCY_SECURITY.md")
            }
        }
    }
    buildscript.configurations.configureEach(enforce)
    configurations.configureEach(enforce)
}
