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

// Inputs for AtlasGeneratorGoldenTest: the demo's annotation-processed output is the golden
// reference the driver must reproduce byte-for-byte, and its compile classpath supplies the
// Spring AI / Spring Web types the generated wrappers reference.
val demoProject = project(":demo")
val demoSources = demoProject.projectDir.resolve("src/main/java")
val demoGeneratedSources = demoProject.layout.buildDirectory
    .dir("generated/sources/annotationProcessor/java/main")
val demoGenerationInputs = demoProject.layout.buildDirectory
    .file("atlas/demo-generation.properties")
val demoClasses = demoProject.layout.buildDirectory.dir("classes/java/main")
val demoOpenApiDir = demoProject.layout.buildDirectory
    .dir("classes/java/main/META-INF/openapi")

tasks.withType<Test> {
    // compile-testing's hasSourceEquivalentTo needs access to JDK compiler internals
    jvmArgs(
        "--add-opens", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )

    dependsOn(":demo:compileJava", ":demo:exportGenerationInputs")

    // The golden data lives outside this module, so it must be declared as task input — otherwise
    // Gradle keeps the golden test UP-TO-DATE after the demo sources, its generated tree, the
    // exported processor options/classpath, or the reference OpenAPI spec change.
    inputs.files(demoSources).withPropertyName("demoSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(demoGeneratedSources).withPropertyName("demoGeneratedSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(demoGenerationInputs).withPropertyName("demoGenerationInputs")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(demoOpenApiDir).withPropertyName("demoOpenApiSpecs")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    systemProperty("ai.atlas.demo.sources", demoSources.absolutePath)
    systemProperty("ai.atlas.demo.generatedSources", demoGeneratedSources.get().asFile.absolutePath)
    systemProperty("ai.atlas.demo.generationInputs", demoGenerationInputs.get().asFile.absolutePath)
    systemProperty("ai.atlas.demo.classesDir", demoClasses.get().asFile.absolutePath)
}
