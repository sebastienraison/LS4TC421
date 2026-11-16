import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Pure Kotlin/JVM modules, no Android plugin (:app is configured in its own build file).
// Bytecode level 17 so that the Android app module can consume :protocol directly.
configure(listOf(project(":protocol"), project(":simulator"))) {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    extensions.configure<KotlinJvmProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            allWarningsAsErrors.set(true)
        }
    }
    tasks.withType<Test>().configureEach {
        useJUnit()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = false
        }
    }
}
