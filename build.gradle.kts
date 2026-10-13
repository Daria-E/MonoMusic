import org.gradle.api.tasks.Delete

plugins {
    id("com.android.application") version "8.2.0" apply false
    id("com.android.library") version "8.2.0" apply false
    // Kotlin 2.x (ahead of the Mono scaffold's 1.9.10) for the Compose
    // compiler's strong skipping, which lets list rows skip recomposition.
    kotlin("android") version "2.1.20" apply false
    kotlin("jvm") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
