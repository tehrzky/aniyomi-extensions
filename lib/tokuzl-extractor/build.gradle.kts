plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    compileOnly(libs.bundles.common)
}

// Add this task to fix the build error
tasks.register("printDependentExtensions") {
    doLast {
        println("[]") // Return empty JSON array since no extensions depend on this yet
    }
}
