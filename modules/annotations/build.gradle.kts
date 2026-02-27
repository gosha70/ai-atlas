// annotations module — ZERO external dependencies
// Only java.lang.annotation types allowed

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
