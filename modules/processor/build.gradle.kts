// processor module — compile-time only, no runtime dependencies

dependencies {
    // Annotations (implementation — processor needs them on its classpath at APT time)
    implementation(project(":modules:annotations"))

    // Code generation
    implementation(libs.javapoet)

    // AutoService for META-INF/services registration
    compileOnly(libs.auto.service.annotations)
    annotationProcessor(libs.auto.service)

    // Testing
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.compile.testing)
    testImplementation(project(":modules:annotations"))
}

tasks.withType<Test> {
    // compile-testing's hasSourceEquivalentTo needs access to JDK compiler internals
    jvmArgs(
        "--add-opens", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-opens", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    )
}
