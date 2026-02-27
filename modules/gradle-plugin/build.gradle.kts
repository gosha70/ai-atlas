// gradle-plugin module — wraps annotation processor configuration

plugins {
    `java-gradle-plugin`
}

gradlePlugin {
    plugins {
        create("agenticPlugin") {
            id = "ai.adam.gradle-plugin"
            implementationClass = "ai.adam.plugin.AgenticPlugin"
        }
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
}
