plugins { java }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

repositories { mavenCentral() }

val runtime = rootDir.resolve("../../modules/runtime/build")
val annotations = rootDir.resolve("../../modules/annotations/build")

dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.14"))
    testImplementation(platform("org.springframework.ai:spring-ai-bom:1.1.7"))
    // The real Atlas runtime (AgenticAutoConfiguration + LazyToolCallbackProvider), unchanged.
    testImplementation(files(runtime.resolve("classes/java/main"), runtime.resolve("resources/main"),
        annotations.resolve("classes/java/main")))
    testImplementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    testImplementation("org.springframework.boot:spring-boot-starter-web")
    testImplementation("org.springframework.boot:spring-boot-starter-validation")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); showStandardStreams = false; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    systemProperty("spike.evidence", rootDir.resolve("evidence").absolutePath)
}

// MethodToolCallback binds JSON arguments by parameter name.
tasks.withType<JavaCompile> { options.compilerArgs.add("-parameters") }
