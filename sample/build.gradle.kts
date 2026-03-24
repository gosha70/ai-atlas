plugins {
    id("com.egoge.ai-atlas") version "1.2.0"
    id("org.springframework.boot") version "3.4.3"
}

agentic {
    version.set("1.2.0")
    apiMajorVersion.set(2)
    mcpEnabled.set(false)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
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
