plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    compileOnly(libs.aniyomi.lib)
}

tasks.register("printDependentExtensions") {
    doLast {
        println("[]")
    }
}
