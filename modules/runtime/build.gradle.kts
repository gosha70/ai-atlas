// runtime module — Spring Boot auto-configuration + MCP server

dependencies {
    // Annotations (runtime — needed for reflection)
    api(project(":modules:annotations"))

    // Spring Boot (managed via BOM)
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-web")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:${libs.versions.spring.boot.get()}")

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
