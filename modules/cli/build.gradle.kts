// cli module — the standalone `atlas` command, packaged as a self-contained fat jar (atlas.jar).
//
// This module declares NO Spring dependency of its own (FR-005). The generated wrappers do
// reference @Tool / @Service / @RestController, so those types reach generation through the
// caller-supplied `--classpath`, never through this module's own classpath. AtlasCliTest asserts
// that invariant against the resolved runtime classpath.

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

// Every module ships through the one atomic Maven Central release, this one included. The Shadow
// plugin contributes shadowJar to the `java` component under the `all` classifier, so the shared
// publication carries the executable alongside the thin jar, and the tag-triggered
// `publishMavenJavaPublicationToOssrhRepository` picks it up with no release-workflow change.
// On disk the file keeps the plain `atlas.jar` name the spec's `java -jar atlas.jar` refers to;
// in the repository it is ai-atlas-cli-<version>-all.jar.
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

val mainClassName = "com.egoge.ai.atlas.cli.AtlasCli"

dependencies {
    implementation(project(":modules:processor"))
    implementation(libs.picocli)
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(project(":modules:annotations"))

    // Spring types the generated wrappers reference. Tests hand these to generation through
    // `--classpath`, exactly as a real caller does — they are deliberately test-only, so they do
    // not reach `runtimeClasspath` and are not bundled into atlas.jar.
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    testImplementation("org.springframework:spring-web")
    testImplementation(libs.spring.ai.mcp.server)
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to mainClassName,
            "Implementation-Version" to project.version
        )
    }
}

val shadowJar = tasks.named<ShadowJar>("shadowJar")

shadowJar.configure {
    // The spec advertises `java -jar atlas.jar`, so the fat jar carries the plain name rather
    // than the default <base>-<version>-all.jar.
    archiveFileName.set("atlas.jar")
    manifest {
        attributes(
            "Main-Class" to mainClassName,
            "Implementation-Version" to project.version
        )
    }
    // Keeps META-INF/services entries from the bundled dependencies intact.
    mergeServiceFiles()
}

// FR-011: the runnable jar is a build deliverable, not an opt-in extra.
tasks.named("build") {
    dependsOn(shadowJar)
}

val runtimeClasspath = configurations.named("runtimeClasspath")

tasks.withType<Test>().configureEach {
    // The functional test runs the packaged executable as its own process, so the jar must exist
    // before the tests do — that is the only way manifest, shading and service-file merging
    // problems show up in CI rather than in a user's `java -jar`.
    dependsOn(shadowJar)
    inputs.file(shadowJar.flatMap { it.archiveFile }).withPropertyName("atlasJar")
    // Lets the test assert FR-005 against what actually ships in atlas.jar, rather than against
    // the test classpath (which intentionally carries Spring so generated code compiles).
    inputs.files(runtimeClasspath).withPropertyName("cliRuntimeClasspath")
    doFirst {
        systemProperty("ai.atlas.cli.runtimeClasspath", runtimeClasspath.get().asPath)
        systemProperty("ai.atlas.cli.jar", shadowJar.get().archiveFile.get().asFile.absolutePath)
    }
}
