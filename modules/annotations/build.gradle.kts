// annotations module — ZERO external dependencies
// Only java.lang.annotation types allowed

apply(from = rootProject.file("gradle/publishing.gradle.kts"))

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
