import java.text.SimpleDateFormat
import java.util.Date
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.x has built-in Kotlin support: no separate Kotlin Android plugin. The Compose
    // compiler plugin stays separate and must match the Kotlin version.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Build date shown in Settings (compile date, ISO 8601 so it reads the same in every language).
val buildDate: String = SimpleDateFormat("yyyy-MM-dd").format(Date())

// Release signing, as Bivouac (D26: a key of its own, the same on GitHub, F-Droid and Play).
// Keystore path: "signing.storeFile" in local.properties (never committed), default below.
// Password: ONLY the environment variable LS4TC421_SIGNING_PASSWORD, never a file: set it from the
// password manager in the shell that builds the release. One password for the store and the key.
// Without both, the release is built UNSIGNED: never a fallback on the debug key, so that an APK
// signed with the debug key can never pass for a release.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}
fun expandUserHome(path: String): String =
    if (path.startsWith("~")) path.replaceFirst("~", System.getProperty("user.home")) else path
val releaseSigningStoreFile: File =
    File(expandUserHome(localProperties.getProperty("signing.storeFile", "~/cles-signature/ls4tc421-release.p12")))
val releaseSigningPassword: String? = System.getenv("LS4TC421_SIGNING_PASSWORD")
val releaseSigningAvailable: Boolean = releaseSigningStoreFile.isFile && !releaseSigningPassword.isNullOrEmpty()
if (!releaseSigningAvailable) {
    logger.warn(
        "Release NOT SIGNED: needs the keystore ($releaseSigningStoreFile) and LS4TC421_SIGNING_PASSWORD. " +
            "assembleRelease produces an unsigned APK; there is no fallback on the debug key.",
    )
}

android {
    // The Kotlin package; the published identifier is applicationId below.
    namespace = "net.rseb.ls4tc421"
    compileSdk = 37

    defaultConfig {
        // Published identifier (D26, Q89): it can never change once on a store.
        applicationId = "com.ls4tc421.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "BUILD_DATE", "\"$buildDate\"")
    }

    signingConfigs {
        // Created only when both the keystore and the password are there: never a partial config.
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = releaseSigningStoreFile
                storePassword = releaseSigningPassword
                keyAlias = "ls4tc421"
                keyPassword = releaseSigningPassword
            }
        }
    }

    buildTypes {
        release {
            // R8 (shrinking, optimisation, obfuscation), as Bivouac: Google Play asks it of apps with more
            // than 10 MB of DEX (Q187). Resource shrinking stays off, as Bivouac, for F-Droid's
            // reproducible builds. Keep rules: proguard-rules.pro.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseSigningAvailable) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        error += "MissingTranslation"
    }
    testOptions {
        // The merged manifest and resources, for the Compose UI tests under Robolectric.
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Robolectric (database migration test only) opens file descriptors by reflection, and the
            // android-all of API 37 goes through jdk.internal.access: both closed by default on JDK 17+.
            test.jvmArgs("--add-opens", "java.base/java.io=ALL-UNNAMED")
            test.jvmArgs("--add-exports", "java.base/jdk.internal.access=ALL-UNNAMED")
        }
    }
    // The dependency list is shown in the app's credits screen; do not embed it in the APK.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":protocol"))

    implementation(libs.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.navigation.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)
    debugImplementation(libs.ui.tooling)
    // Compose UI tests on the JVM under Robolectric (no device): the test activity comes from the manifest.
    debugImplementation(libs.ui.test.manifest)

    // The simulator is a test-only dependency: it must never reach the APK.
    testImplementation(project(":simulator"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real SQLite on the JVM for the migration test (no device, no instrumented test).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.ui.test.junit4)
    // ui-test-junit4 brings Espresso 3.5, which fails on the Android 37 framework of Robolectric.
    testImplementation(libs.espresso.core)
}
