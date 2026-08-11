// mcp-stdio module — the standalone STDIO MCP server, packaged as a self-contained fat jar
// (atlas-mcp.jar) so a copilot's .mcp.json can launch it with {"command":"java","args":["-jar",...]}.
//
// This module declares NO Spring dependency of its own (FR-005): it speaks MCP through the plain
// MCP Java SDK (io.modelcontextprotocol.sdk), not the Spring AI starter. The generated wrappers do
// reference @Tool / @Service / @RestController, so those types reach generation through the
// caller-supplied `classpath` tool argument, never through this module's own classpath.
// AtlasMcpServerTest asserts that invariant against the resolved runtime classpath.

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.shadow)
}

// Ships through the same atomic Maven Central release as every other module; the shadow jar rides
// along under the `all` classifier exactly as modules/cli's does.
apply(from = rootProject.file("gradle/publishing.gradle.kts"))

val mainClassName = "com.egoge.ai.atlas.mcp.AtlasMcpServer"

// JaCoCo can only see AtlasMcpServer's lines if the spawned server JVM itself carries the agent:
// the round-trip test runs atlas-mcp.jar as a child process, outside the test task's own
// instrumentation. The test is handed a ready-made -javaagent argument for the child's command
// line; the child appends its execution data to the test task's file, so report and verification
// see one merged picture.
val jacocoChildAgent: Configuration by configurations.creating
val jacocoToolVersion = the<JacocoPluginExtension>().toolVersion

dependencies {
    "jacocoChildAgent"("org.jacoco:org.jacoco.agent:$jacocoToolVersion:runtime")

    implementation(project(":modules:processor"))
    implementation(libs.mcp.sdk)
    implementation(libs.jackson.databind)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(project(":modules:annotations"))

    // Spring types the generated wrappers reference. Tests hand these to generation through the
    // `classpath` tool argument, exactly as a real caller does — they are deliberately test-only,
    // so they do not reach `runtimeClasspath` and are not bundled into atlas-mcp.jar.
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
    // The spec's .mcp.json snippet says `java -jar atlas-mcp.jar`, so the fat jar carries the
    // plain name rather than the default <base>-<version>-all.jar.
    archiveFileName.set("atlas-mcp.jar")
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
    // The round-trip test spawns the packaged jar as its own process — the way a copilot's
    // .mcp.json launch does — so the jar must exist before the tests run. That is the only way
    // manifest, shading and service-file merging problems show up in CI rather than in a user's
    // MCP client.
    dependsOn(shadowJar)
    inputs.file(shadowJar.flatMap { it.archiveFile }).withPropertyName("atlasMcpJar")
    // Lets the test assert FR-005 against what actually ships in atlas-mcp.jar, rather than
    // against the test classpath (which intentionally carries Spring so generated code compiles).
    inputs.files(runtimeClasspath).withPropertyName("mcpRuntimeClasspath")
    doFirst {
        systemProperty("ai.atlas.mcp.runtimeClasspath", runtimeClasspath.get().asPath)
        systemProperty("ai.atlas.mcp.jar", shadowJar.get().archiveFile.get().asFile.absolutePath)
        val jacocoExec = extensions.findByType(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java)
            ?.takeIf { it.isEnabled }?.destinationFile
        if (jacocoExec != null) {
            systemProperty("ai.atlas.mcp.childJvmArg",
                "-javaagent:${jacocoChildAgent.singleFile.absolutePath}"
                    + "=destfile=${jacocoExec.absolutePath},append=true,includes=com.egoge.ai.atlas.*")
        }
    }
}
