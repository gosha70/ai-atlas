// demo module — consumer of the framework

plugins {
    alias(libs.plugins.spring.boot)
}

// Disable bootJar until main class exists (Cycle 2)
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    enabled = false
}

tasks.named<Jar>("jar") {
    enabled = true
}

dependencies {
    // Spring Boot BOM for version management
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))

    // Framework modules
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
