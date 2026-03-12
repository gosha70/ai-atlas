plugins {
    id("com.egoge.ai-atlas") version "0.1.0-SNAPSHOT"
    id("org.springframework.boot") version "3.4.3"
}

// The plugin reads agentic.version to construct Maven coordinates for
// the framework modules it adds (annotations, processor, runtime).
// The default is project.version, which in a standalone build is
// "unspecified" — so we must set it explicitly here.
version = "0.1.0-SNAPSHOT"

agentic {
    apiMajorVersion.set(2)
    mcpEnabled.set(false)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.3"))
    implementation("org.springframework.boot:spring-boot-starter-web")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
