// processor module — compile-time only, no runtime dependencies

apply(from = rootProject.file("gradle/publishing.gradle.kts"))

dependencies {
    // Annotations (implementation — processor needs them on its classpath at APT time)
    implementation(project(":modules:annotations"))

    // Code generation
    implementation(libs.javapoet)

    // OpenAPI spec generation
    implementation(libs.swagger.models)
    implementation(libs.jackson.databind)

    // AutoService for META-INF/services registration
    compileOnly(libs.auto.service.annotations)
    annotationProcessor(libs.auto.service)

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.compile.testing)
    testImplementation(project(":modules:annotations"))

    // Spring dependencies for compile-testing (generated code must compile)
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    testImplementation("org.springframework:spring-web")
    testImplementation(libs.spring.ai.mcp.server)
}

tasks.withType<Test> {
    // compile-testing's hasSourceEquivalentTo needs access to JDK compiler internals
    jvmArgs(
        "--add-opens", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )
}
