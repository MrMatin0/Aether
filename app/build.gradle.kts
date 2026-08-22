import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Human-friendly ABI -> versionCode offset so each split APK gets a unique code.
val abiCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "universal" to 3)

// The variant callbacks below must not read the android DSL back (AGP 9 no longer
// guarantees that is safe), so the base version code lives here instead.
// 1.4.3: 10 -> 11. This MUST move with versionName, otherwise Android treats the
// new APK as the same build and refuses to install it as an update.
val baseVersionCode = 11

val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propKey: String, envKey: String): String? =
    (keystoreProps.getProperty(propKey) ?: System.getenv(envKey))?.takeIf { it.isNotBlank() }

val releaseStorePath: String? = signingValue("storeFile", "KEYSTORE_PATH")
val hasReleaseKeystore: Boolean =
    releaseStorePath != null && rootProject.file(releaseStorePath).exists()

val ciKeystoreB64 = rootProject.file(".github/ci-keystore.jks.b64")
val useCiKeystore: Boolean = !hasReleaseKeystore && ciKeystoreB64.exists()
val ciKeystoreFile = rootProject.file("build/ci-release.keystore")
if (useCiKeystore) {
    ciKeystoreFile.parentFile.mkdirs()
    ciKeystoreFile.writeBytes(
        Base64.getMimeDecoder().decode(ciKeystoreB64.readText().trim()),
    )
}

// ------------------------------------------------------------- VAZIRMATN ----
//
// The UI face, for BOTH scripts (ui/theme/Type.kt). Font binaries follow the
// same rule as the native cores: never committed. The five weights the type
// scale actually uses are downloaded once into a gitignored res source set, so
// they are compiled INTO the APK.
//
// Why not the Google Fonts downloadable-font provider, which would need no
// files at all: it resolves over the network through Play Services at runtime.
// This app exists for people on filtered networks with no Play Services and no
// route to Google, i.e. it would fall back to the system font in exactly the
// situation where the app is being used. A bundled font always renders.
val vazirmatnVersion = "33.003"
val vazirmatnFontDir = file("src/main/res-fonts/font")
val vazirmatnFontPath = vazirmatnFontDir.relativeTo(rootDir).invariantSeparatorsPath
val vazirmatnWeights = mapOf(
    "vazirmatn_regular" to "Vazirmatn-Regular.ttf",
    "vazirmatn_medium" to "Vazirmatn-Medium.ttf",
    "vazirmatn_semibold" to "Vazirmatn-SemiBold.ttf",
    "vazirmatn_bold" to "Vazirmatn-Bold.ttf",
    "vazirmatn_extrabold" to "Vazirmatn-ExtraBold.ttf",
)

// Created at configuration time so resource merging never sees a missing dir.
vazirmatnFontDir.mkdirs()

val fetchVazirmatn = tasks.register("fetchVazirmatn") {
    group = "build setup"
    description = "Downloads the Vazirmatn weights used by the Compose type scale."
    val fontDir = vazirmatnFontDir
    val weights = vazirmatnWeights
    val version = vazirmatnVersion
    val relPath = vazirmatnFontPath
    doLast {
        // Host assembled from fragments, same convention as scripts/fetch-natives.sh.
        val base = "https://" + "raw.githubusercontent.com" +
            "/rastikerdar/vazirmatn/v" + version + "/fonts/ttf"
        val failed = mutableListOf<String>()
        weights.forEach { (resName, fileName) ->
            val target = File(fontDir, "$resName.ttf")
            // Already vendored (previous build, or copied in by hand): leave it.
            if (target.length() > 1024L) return@forEach
            val ok = runCatching {
                URI("$base/$fileName").toURL().openStream().use { stream ->
                    target.outputStream().use { stream.copyTo(it) }
                }
            }.isSuccess && target.length() > 1024L
            if (!ok) {
                target.delete()
                failed += fileName
            }
        }
        if (failed.isNotEmpty()) {
            throw GradleException(
                "Could not fetch Vazirmatn (${failed.joinToString()}). Run " +
                    "scripts/fetch-fonts.sh on a connected machine, or copy the TTFs " +
                    "into $relPath yourself (lowercase names, e.g. " +
                    "vazirmatn_regular.ttf), then build again.",
            )
        }
    }
}

android {
    namespace = "studio.cluvex.aether"
    compileSdk = 37

    defaultConfig {
        applicationId = "studio.cluvex.aether"
        minSdk = 26
        targetSdk = 37
        versionCode = baseVersionCode
        versionName = "1.4.3"

        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        val githubRepo = System.getenv("GITHUB_REPOSITORY")
            ?: (project.findProperty("githubRepo") as? String ?: "")
        val releasesUrl =
            if (githubRepo.isNotBlank()) "https://github.com/$githubRepo/releases/latest" else ""
        buildConfigField("String", "RELEASES_URL", "\"$releasesUrl\"")

        val coreVersion = rootProject.file("native/aether/CORE_VERSION")
            .takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { "unknown" }
        buildConfigField("String", "CORE_VERSION", "\"$coreVersion\"")
    }

    sourceSets {
        getByName("main") {
            // Fetched font binaries live outside res/ so the committed resource
            // tree stays free of blobs. See fetchVazirmatn above.
            res.srcDir("src/main/res-fonts")
        }
    }

    signingConfigs {
        create("release") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            if (hasReleaseKeystore) {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = signingValue("storePassword", "KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "KEY_PASSWORD")
            } else if (useCiKeystore) {
                storeFile = ciKeystoreFile
                storePassword = "aether-ci-keystore"
                keyAlias = "aether-ci"
                keyPassword = "aether-ci-keystore"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (hasReleaseKeystore || useCiKeystore) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
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

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// The font has to be on disk before resource merging reads the source set.
tasks.named("preBuild") { dependsOn(fetchVazirmatn) }

// android.kotlinOptions was removed in Kotlin 2.4; the compiler options live here now.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

if (!hasReleaseKeystore && !useCiKeystore) {
    tasks.configureEach {
        if (name.contains("Release") &&
            (name.startsWith("assemble") || name.startsWith("package") || name.startsWith("bundle"))
        ) {
            doFirst {
                throw GradleException(
                    "No stable release keystore configured — refusing to build a " +
                        "debug-signed release. Run scripts/generate-keystore.sh or " +
                        "provide KEYSTORE_* env vars / .github/ci-keystore.jks.b64."
                )
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abiName = output.filters.find { it.filterType == ABI }?.identifier
            val base = baseVersionCode * 1000
            val offset = abiCodes[abiName ?: "universal"] ?: 0
            output.versionCode.set(base + offset)
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation(kotlin("test"))
    debugImplementation("androidx.compose.ui:ui-tooling")
}
