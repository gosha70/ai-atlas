// demo module — consumer of the framework
//
// ─── External consumer configuration ────────────────────────────────────
// Real projects use the Gradle plugin, which provides the `agentic { }`
// extension and wires all dependencies + processor options automatically:
//
//   plugins {
//       id("com.egoge.ai-atlas") version "<latest>"
//   }
//   agentic {
//       apiMajorVersion.set(2)          // default: 1
//       apiBasePath.set("/api")         // default: "/api"
//       openApiInfoVersion.set("2.0.0") // default: "{major}.0.0"
//   }
//
// This monorepo demo cannot self-apply the plugin (its dependency resolver
// targets published Maven coordinates, not in-repo project paths), so it
// wires the same processor options directly. The configuration below is
// identical to what AgenticPlugin.configureProcessorOptions() produces.
// ─────────────────────────────────────────────────────────────────────────

import java.util.Properties

plugins {
    alias(libs.plugins.spring.boot)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    mainClass.set("com.egoge.ai.atlas.demo.DemoApplication")
}

// Mirror the agentic { } extension values — same options the plugin wires
val apiMajorVersion = 2
val apiBasePath = "/api"
val openApiInfoVersion = "$apiMajorVersion.0.0"

val processorOptions = linkedMapOf(
    "ai.atlas.api.major" to apiMajorVersion.toString(),
    "ai.atlas.api.basePath" to apiBasePath,
    "ai.atlas.openapi.infoVersion" to openApiInfoVersion
)

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(processorOptions.map { (key, value) -> "-A$key=$value" })
}

// Exports the exact inputs `:demo:compileJava` hands to the annotation processor — compile
// classpath and -A options — so the processor's AtlasGenerator golden test can reproduce this
// module's generated output byte-for-byte through the driver API.
val exportGenerationInputs by tasks.registering {
    val compileClasspath = configurations.named("compileClasspath")
    val inputsFile = layout.buildDirectory.file("atlas/demo-generation.properties")
    val options = processorOptions
    inputs.files(compileClasspath)
    inputs.property("processorOptions", options)
    outputs.file(inputsFile)
    doLast {
        val properties = Properties()
        properties.setProperty("classpath", compileClasspath.get().asPath)
        options.forEach { (key, value) -> properties.setProperty("option.$key", value) }
        inputsFile.get().asFile.outputStream().use {
            properties.store(it, "ai-atlas demo generation inputs")
        }
    }
}

dependencies {
    // Spring Boot BOM for version management
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    // Framework modules (plugin adds these automatically for external consumers)
    implementation(project(":modules:annotations"))
    implementation(project(":modules:runtime"))
    annotationProcessor(project(":modules:processor"))

    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
