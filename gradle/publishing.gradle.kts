// Shared publishing configuration — applied by publishable modules
// Usage: apply(from = rootProject.file("gradle/publishing.gradle.kts"))
//
// Required environment variables for Maven Central publishing:
//   OSSRH_USERNAME      — Sonatype OSSRH username
//   OSSRH_PASSWORD      — Sonatype OSSRH password/token
//   GPG_SIGNING_KEY     — ASCII-armored GPG private key (or use gradle.properties)
//   GPG_SIGNING_PASSWORD — GPG key passphrase

apply(plugin = "maven-publish")
apply(plugin = "signing")

configure<PublishingExtension> {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("AI-ATLAS :: ${project.name}")
                description.set("AI-ATLAS: AI Annotation-Driven Tooling & Layered API Synthesis — ${project.name} module")
                url.set("https://github.com/ai-atlas/ai-atlas")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("ai-atlas")
                        name.set("AI-ATLAS Team")
                        email.set("dev@egoge.com")
                    }
                }

                scm {
                    connection.set("scm:git:git://github.com/ai-atlas/ai-atlas.git")
                    developerConnection.set("scm:git:ssh://github.com/ai-atlas/ai-atlas.git")
                    url.set("https://github.com/ai-atlas/ai-atlas")
                }
            }
        }
    }

    repositories {
        mavenLocal()

        // Maven Central (Sonatype OSSRH)
        val ossrhUsername: String? = providers.environmentVariable("OSSRH_USERNAME").orNull
        val ossrhPassword: String? = providers.environmentVariable("OSSRH_PASSWORD").orNull
        if (ossrhUsername != null && ossrhPassword != null) {
            maven {
                name = "ossrh"
                val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
                val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
                url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
                credentials {
                    username = ossrhUsername
                    password = ossrhPassword
                }
            }
        }
    }
}

// Sign all publications when signing key is available
configure<SigningExtension> {
    val signingKey: String? = providers.environmentVariable("GPG_SIGNING_KEY").orNull
    val signingPassword: String? = providers.environmentVariable("GPG_SIGNING_PASSWORD").orNull
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    // Only sign when publishing to a remote repository (not mavenLocal)
    isRequired = providers.environmentVariable("OSSRH_USERNAME").isPresent
    sign(the<PublishingExtension>().publications["mavenJava"])
}
