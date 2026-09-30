// gradle-plugin module — wraps annotation processor configuration

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "1.3.1"
}

gradlePlugin {
    website.set("https://github.com/gosha70/ai-atlas")
    vcsUrl.set("https://github.com/gosha70/ai-atlas")

    plugins {
        create("agenticPlugin") {
            id = "com.egoge.ai-atlas"
            implementationClass = "com.egoge.ai.atlas.plugin.AgenticPlugin"
            displayName = "AI-ATLAS Gradle Plugin"
            description = "Compile-time annotation processor that generates PII-safe MCP tools, REST controllers, DTOs, and OpenAPI specs from annotated Java services"
            tags.set(listOf("ai", "mcp", "annotation-processor", "code-generation", "pii", "openapi", "spring-boot"))
        }
    }
}

// The plugin's own version, as a resource the plugin reads at run time: the default ai-atlas
// dependency version, independent of the consumer's project.version
val pluginVersionDir = layout.buildDirectory.dir("generated/plugin-version")
val generatePluginVersion = tasks.register("generatePluginVersion") {
    val version = project.version.toString()
    val outputDir = pluginVersionDir
    inputs.property("version", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get().file("com/egoge/ai/atlas/plugin/ai-atlas-plugin.properties").asFile
        file.parentFile.mkdirs()
        file.writeText("version=$version\n")
    }
}
sourceSets.main {
    resources.srcDir(generatePluginVersion)
}

// Functional test source set for Gradle TestKit
val functionalTest by sourceSets.creating

configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

// Modules the plugin adds as dependencies, published to a build-local repository so the
// functional tests can compile a consumer project against this build's processor.
val functionalTestRepo = rootProject.layout.buildDirectory.dir("functional-test-repo")
val publishedModules = listOf(":modules:annotations", ":modules:processor", ":modules:runtime")

val functionalTestTask = tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()

    dependsOn(publishedModules.map { "$it:publishMavenJavaPublicationToFunctionalTestRepository" })
    inputs.dir(functionalTestRepo).withPropertyName("functionalTestRepo")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("ai.atlas.functionalTest.repo", functionalTestRepo.get().asFile.absolutePath)
    systemProperty("ai.atlas.functionalTest.version", project.version.toString())
}

tasks.check {
    dependsOn(functionalTestTask)
}

gradlePlugin.testSourceSets.add(functionalTest)

dependencies {
    // The contract tasks' work actions call the processor's EmptyContract, ContractGate and IrJson.
    // compileOnly: at run time they are loaded from the consumer's annotationProcessor classpath,
    // in an isolated class loader, so the check runs the processor version that compiled.
    compileOnly(project(":modules:processor"))

    // ClassOutputResourcesTest calls processor classes (ContractResources, ContractRelease) directly,
    // as a plain unit test, not through the isolated worker classpath: unlike compileOnly, this makes
    // them available at test run time too.
    testImplementation(project(":modules:processor"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}

tasks.test {
    systemProperty("ai.atlas.test.version", project.version.toString())
}
