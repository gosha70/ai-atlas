// runtime module — Spring Boot auto-configuration + MCP server

apply(from = rootProject.file("gradle/publishing.gradle.kts"))

dependencies {
    // Annotations (runtime — needed for reflection)
    api(project(":modules:annotations"))

    // Spring Boot (managed via BOM)
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("org.springframework.boot:spring-boot-starter-web")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor:${libs.versions.spring.boot.get()}")

    // Spring Data web paging: optional, read by PageableBindingCheck only when present
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    compileOnly("org.springframework.data:spring-data-commons")

    // Spring AI MCP — provides @Tool/@ToolParam annotations + MCP server auto-config
    api(libs.spring.ai.mcp.server)

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Bean Validation for ProxiedToolBeanTest's @Validated tool service (test only)
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    // JSON Schema 2020-12 validator with a bundled metaschema, for McpToolSpecificationTest (test only)
    testImplementation(libs.json.schema.validator)
    // The real processor, for ProcessorOutputMergeTest's generated mcp-tools.json (test only)
    testImplementation(project(":modules:processor"))
    // Spring Data's paging resolver, for PageableBindingCheckTest (test only)
    testImplementation("org.springframework.data:spring-data-commons")
}
