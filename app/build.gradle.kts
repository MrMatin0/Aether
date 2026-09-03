import com.android.build.api.variant.FilterConfiguration.FilterType.ABI
import java.io.File
import java.net.URI
import java.util.Base64
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// ============================================================== VERSIONING ==
//
// The two values a release turns on, together, at the top of the file.
//
// The base code MUST move with versionName. If it does not, Android sees the
// new APK as the same build and refuses to install it as an update. 11 and 12
// belong to the 1.4.3 / 1.4.4 line and are skipped rather than reused.
//
// CI does not grep these any more: it reads AGP's own output-metadata.json
// next to the built APKs, so a comment that happens to mention versionName can
// no longer rename every published artifact.
val appVersionName = "1.4.5"
val appBaseVersionCode = 13

// Each split APK needs its own code, and the universal one must outrank both,
// otherwise a device that can take the arm64 split could still be offered the
// universal APK as an "update". Read by the variant callback at the bottom,
// which must not read the android DSL back (AGP 9 no longer guarantees that is
// safe) - hence plain script values instead.
val abiVersionCodeOffsets = mapOf(
    "armeabi-v7a" to 1,
    "arm64-v8a" to 2,
    "universal" to 3,
)

// ================================================================= SIGNING ==
//
// Priority: keystore.properties / KEYSTORE_* env (the real release key) ->
// .github/ci-keystore.jks.b64 (the key every published release so far was
// signed with). There is no third option: a build that invents a certificate
// produces an APK that existing users cannot install over their current app.
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
// Deliberately NOT under build/: `gradle clean assembleRelease` in one
// invocation would configure (writing the keystore), then delete build/,
// then try to sign against a file that is no longer there.
val ciKeystoreFile = File(rootProject.layout.projectDirectory.asFile, ".gradle/aether-ci-release.keystore")
if (useCiKeystore) {
    val decoded = Base64.getMimeDecoder().decode(ciKeystoreB64.readText().trim())
    if (!ciKeystoreFile.exists() || !ciKeystoreFile.readBytes().contentEquals(decoded)) {
        ciKeystoreFile.parentFile.mkdirs()
        ciKeystoreFile.writeBytes(decoded)
    }
}

// =============================================================== VAZIRMATN ==
//
// The UI face, for BOTH scripts (ui/theme/Type.kt). Font binaries follow the
// same rule as the native cores: never committed. The five weights the type
// scale actually uses are downloaded once into a gitignored res source set, so
// they are compiled INTO the APK.
//
// Why not Google's downloadable-font provider, which would need no files at
// all: it resolves over the network through Play Services at runtime. This app
// exists for people on filtered networks with no Play Services and no route to
// Google, i.e. it would fall back to the system font in exactly the situation
// where the app is being used. A bundled font always renders.
val vazirmatnVersion = "33.003"
val vazirmatnFontDir = layout.projectDirectory.dir("src/main/res-fonts/font").asFile
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

// Declares its inputs and outputs, so unlike the previous version it is
// actually up-to-date-checked instead of re-running on every single build, and
// each download gets three attempts before the build gives up on a flaky link.
val fetchVazirmatn = tasks.register("fetchVazirmatn") {
    group = "build setup"
    description = "Downloads the Vazirmatn weights used by the Compose type scale."
    val fontDir = vazirmatnFontDir
    val weights = vazirmatnWeights
    val version = vazirmatnVersion
    val relPath = vazirmatnFontPath
    inputs.property("vazirmatnVersion", version)
    inputs.property("vazirmatnWeights", weights.toSortedMap().toString())
    outputs.dir(fontDir)
    // Network downloads have no business in the remote build cache.
    outputs.cacheIf { false }
    doLast {
        // Host assembled from fragments, same convention as scripts/fetch-natives.sh.
        val base = "https://" + "raw.githubusercontent.com" +
            "/rastikerdar/vazirmatn/v" + version + "/fonts/ttf"
        val failed = mutableListOf<String>()
        weights.forEach { (resName, fileName) ->
            val target = File(fontDir, "$resName.ttf")
            // Already vendored (previous build, or copied in by hand): leave it.
            if (target.length() > 1024L) return@forEach
            var ok = false
            for (attempt in 1..3) {
                ok = runCatching {
                    URI("$base/$fileName").toURL().openStream().use { stream ->
                        target.outputStream().use { stream.copyTo(it) }
                    }
                }.isSuccess && target.length() > 1024L
                if (ok) break
                target.delete()
                logger.lifecycle("Vazirmatn: $fileName attempt $attempt failed, retrying")
            }
            if (!ok) failed += fileName
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
    // NOTE: namespace != applicationId on purpose.
    //
    // The namespace only decides which package R and BuildConfig are generated
    // into, i.e. it is a COMPILE-TIME concern, and every Kotlin file already
    // declares `package studio.cluvex.aether.*`. Renaming it would mean moving
    // the entire source tree and rewriting every import for zero runtime gain,
    // so it stays as it is. What Android keys the installed app on is the
    // applicationId below, and that is what this fork changes.
    namespace = "studio.cluvex.aether"
    compileSdk = 37

    defaultConfig {
        // FORK IDENTITY. Upstream ships studio.cluvex.aether; the package name
        // is the unique key the platform installs an app under, so as long as
        // this fork reused it the two builds were the same app to Android and
        // installing one replaced the other. With a distinct id both can live
        // on the same device side by side.
        applicationId = "io.github.mrmatin0.aether"
        minSdk = 26
        targetSdk = 37
        versionCode = appBaseVersionCode
        versionName = appVersionName

        // Launcher, Quick Settings tile and widget label. Defined next to the
        // applicationId so the whole "this is not the upstream build" identity
        // lives in one place, and so two icons with the exact same name can
        // never sit next to each other on the home screen.
        // Needs buildFeatures.resValues below: AGP 9 stopped enabling that
        // implicitly, and configuration fails outright if this call is present
        // while the feature is off.
        resValue("string", "app_label", "Aether (Fork)")

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
            // v1 (jar signing) is dead weight at minSdk 26 and only makes the
            // APK bigger; v2 + v3 is what the platform and Play Protect want.
            // The certificate itself is unchanged, so over-install still works.
            enableV1Signing = false
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
            // R8 is ON. This app is downloaded over throttled, filtered links,
            // so a smaller APK is a feature, and an unshrunk Compose +
            // material-icons-extended build carries a lot of dead surface.
            //
            // What keeps it safe (app/proguard-rules.pro):
            //  - the native bridge binds Java_..._TProxyService_* BY NAME, so
            //    that class and its native methods must keep their names;
            //  - enum names are persisted through DataStore, so valueOf() must
            //    keep working.
            // Manifest components are kept by AGP automatically.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseKeystore || useCiKeystore) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
        debug {
            // Debug exists to be fast and inspectable; never shrink it.
            isMinifyEnabled = false
            isShrinkResources = false
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
        // Required by the resValue("string", "app_label", ...) call above.
        // AGP 9 defaults this off, and a resValue with the feature disabled is
        // a hard configuration error, not a warning.
        resValues = true
    }

    packaging {
        // DO NOT SET THIS TO false.
        //
        // libaether.so is not a library: it is the Rust engine EXECUTABLE,
        // shipped under a .so name so the platform extracts it into the app's
        // native lib directory where it can actually be exec'd. Uncompressed
        // (legacy packaging off) native libs are mapped straight out of the
        // APK and never land on disk, so the engine would simply not be there
        // at runtime and every connection attempt would fail.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    dependenciesInfo {
        // Strip Google's signed dependency-metadata blob. It is only useful to
        // Play, this app ships from GitHub Releases, and it is one more
        // fingerprintable blob to bake into a circumvention binary.
        includeInApk = false
        includeInBundle = false
    }
}

// The font has to be on disk before resource merging reads the source set.
tasks.named("preBuild") { dependsOn(fetchVazirmatn) }

// android.kotlinOptions was removed in Kotlin 2.4; compiler options live here.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Refuse to produce a release APK signed with a throwaway debug certificate.
// Android will not install such a build over an existing one, and Play Protect
// flags it. Checked once against the task graph instead of decorating every
// task in the project with a doFirst.
if (!hasReleaseKeystore && !useCiKeystore) {
    gradle.taskGraph.whenReady {
        val offender = allTasks.firstOrNull { task ->
            task.name.contains("Release") &&
                listOf("assemble", "package", "bundle").any { task.name.startsWith(it) }
        }
        if (offender != null) {
            throw GradleException(
                "No stable release keystore configured - refusing to build a " +
                    "debug-signed release (${offender.path}). Run " +
                    "scripts/generate-keystore.sh, or provide KEYSTORE_PATH / " +
                    "KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD, or restore " +
                    ".github/ci-keystore.jks.b64.",
            )
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abiName = output.filters.find { it.filterType == ABI }?.identifier
            val base = appBaseVersionCode * 1000
            val offset = abiVersionCodeOffsets[abiName ?: "universal"] ?: 0
            output.versionCode.set(base + offset)
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.compose.ui.tooling)
    testImplementation(kotlin("test"))
}
