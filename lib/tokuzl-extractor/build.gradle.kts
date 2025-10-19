plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    compileOnly(libs.bundles.common)
}
