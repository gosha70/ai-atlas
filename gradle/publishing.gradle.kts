// Shared publishing configuration — applied by publishable modules
// Usage: apply(from = rootProject.file("gradle/publishing.gradle.kts"))

apply(plugin = "maven-publish")

configure<PublishingExtension> {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set(project.name)
                description.set("AI-ADAM: AI Auto-Discoverable API Management")
                url.set("https://github.com/ai-adam/ai-adam")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/ai-adam/ai-adam.git")
                    developerConnection.set("scm:git:ssh://github.com/ai-adam/ai-adam.git")
                    url.set("https://github.com/ai-adam/ai-adam")
                }
            }
        }
    }

    repositories {
        mavenLocal()
    }
}
