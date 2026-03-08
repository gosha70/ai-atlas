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

// Functional test source set for Gradle TestKit
val functionalTest by sourceSets.creating

configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[functionalTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

val functionalTestTask = tasks.register<Test>("functionalTest") {
    testClassesDirs = functionalTest.output.classesDirs
    classpath = functionalTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.check {
    dependsOn(functionalTestTask)
}

gradlePlugin.testSourceSets.add(functionalTest)

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
