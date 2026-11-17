import com.android.build.api.variant.BuildConfigField
import com.android.build.api.variant.impl.VariantOutputImpl
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9.x has built-in Kotlin support: no separate Kotlin Android plugin. The Compose
    // compiler plugin stays separate and must match the Kotlin version.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Build date shown in Settings (ISO 8601, so it reads the same in every language). It differs per build type,
// as in Bivouac:
// - debug: the compile date, to tell two test builds apart;
// - release: the date of the last commit, set lazily at the end of this file. F-Droid rebuilds the release
//   from the tag and compares it byte for byte with the APK of the GitHub release
//   (https://f-droid.org/docs/Reproducible_Builds/): a compile date would make two builds of the same
//   sources differ.
val debugBuildDate: String = SimpleDateFormat("yyyy-MM-dd").format(Date())

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
        debug {
            buildConfigField("String", "BUILD_DATE", "\"$debugBuildDate\"")
        }
        release {
            // R8 (shrinking, optimisation, obfuscation), as Bivouac: Google Play asks it of apps with more
            // than 10 MB of DEX (Q187). Resource shrinking stays off, as Bivouac, for F-Droid's
            // reproducible builds. Keep rules: proguard-rules.pro.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseSigningAvailable) signingConfigs.getByName("release") else null
            // BUILD_DATE of the release: set at the end of this file (date of the last commit).
            // AGP otherwise puts META-INF/version-control-info.textproto in the APK and the bundle: the commit hash
            // from a clone, "NO_VALID_GIT_FOUND" from a worktree. Its content would depend on where the build runs,
            // and F-Droid could not reproduce an APK built in a worktree. As in Bivouac.
            vcsInfo {
                include = false
            }
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

// The F-Droid recipe takes the binary from .../releases/download/v%v/LS4TC421-%v.apk, so the APK attached to
// the GitHub release must have exactly that name: Gradle produces it directly, as in Bivouac, so that a
// forgotten manual rename cannot silently break a release. The Variant API is the stable way to rename an
// output with AGP 9. The -unsigned suffix is set again explicitly, because AGP replaces the name instead of
// completing it: an unsigned release (F-Droid rebuilds without the key) must not look like a signed one.
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        // BUILD_DATE of the release, read lazily: `git log` runs only when the release BuildConfig is generated,
        // never at configuration time (which runs for every task, debug and lint included).
        variant.buildConfigFields?.put(
            "BUILD_DATE",
            providers.provider {
                // %cs: committer date, short ISO 8601, in the time zone recorded in the commit, so every machine
                // reads the same date for the same commit. providers.exec is a tracked input, unlike a raw process.
                val gitCommitDate: String? = try {
                    val gitLog = providers.exec {
                        commandLine("git", "log", "-1", "--format=%cs")
                        isIgnoreExitValue = true
                    }
                    if (gitLog.result.get().exitValue == 0) gitLog.standardOutput.asText.get().trim() else null
                } catch (e: Exception) {
                    null
                }
                // Fallback outside a git clone: SOURCE_DATE_EPOCH (https://reproducible-builds.org/docs/source-date-epoch/,
                // Unix seconds, UTC). Neither: the build fails, rather than put a plausible but wrong date in an APK
                // whose reproducibility is checked.
                val date = gitCommitDate?.takeIf { it.isNotEmpty() }
                    ?: providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.let { epoch ->
                        DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
                            .withZone(ZoneOffset.UTC)
                            .format(Instant.ofEpochSecond(epoch.trim().toLong()))
                    }
                    ?: throw GradleException(
                        "Cannot determine BUILD_DATE for the release: `git log -1 --format=%cs` failed and " +
                            "SOURCE_DATE_EPOCH is not set. Build the release from a git clone, or set " +
                            "SOURCE_DATE_EPOCH (Unix seconds, UTC).",
                    )
                BuildConfigField("String", "\"$date\"", "Date of the last commit (F-Droid reproducibility)")
            },
        )

        variant.outputs.forEach { output ->
            if (output is VariantOutputImpl) {
                output.outputFileName.set(
                    output.versionName.map { versionName ->
                        val unsignedSuffix = if (releaseSigningAvailable) "" else "-unsigned"
                        "LS4TC421-$versionName$unsignedSuffix.apk"
                    },
                )
            }
        }
    }
}
