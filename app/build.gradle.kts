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
// 1.4.6 is the first build whose APK actually CONTAINS the Psiphon and Tor
// cores. 1.4.5 shipped the chain feature's source and none of its binaries: the
// Psiphon cross-compile could not start (the pipeline installed Go 1.23 while
// psiphon-tunnel-core's go.mod asks for 1.26.0) and that build step is
// continue-on-error, so the release went out with libpsiphon.so missing and the
// app correctly reported the core as absent. See docs/CHAIN_CORES.md.
//
// 1.4.7 shipped the Tor BRIDGES feature the same way: the source, the settings
// page and the parser, and none of the pluggable-transport binaries, because
// nothing in the build ever ran scripts/build-pt-transports.sh. See
// docs/TOR_BRIDGES.md and the PLUGGABLE TRANSPORTS block below.
//
// CI does not grep these any more: it reads AGP's own output-metadata.json
// next to the built APKs, so a comment that happens to mention versionName can
// no longer rename every published artifact.
val appVersionName = "1.4.6"
val appBaseVersionCode = 14

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

/** The ABIs this app ships. Single source of truth for the payload checks. */
val shippedAbis = listOf("arm64-v8a", "armeabi-v7a")

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

// ============================================================= TOR BRIDGES ==
//
// The BUILT-IN bridge list (assets/tor/bridges.json), refreshed at build time by
// scripts/fetch-tor-bridges.sh.
//
// WHY THIS IS A BUILD STEP AND NOT A COMMITTED FILE: bridge addresses get
// blocked, replaced and re-hosted, so a list in git history is a list of
// addresses a censor has had months to find. assets/tor/ is gitignored for the
// same reason the native cores are.
//
// WHY IT RUNS HERE AND NOT ONLY IN CI: it is the app's own build that needs the
// asset, and a step that only exists in one workflow file is a step that silently
// stops running the moment anything else assembles the APK. That is exactly how
// this feature shipped in 1.4.7 with no asset at all.
//
// WHY IT IS NON-FATAL: core/BridgeCatalog.kt carries a compiled-in list as the
// floor, and a build that could not reach bridges.torproject.org is a build made
// on a filtered network - which is where this app is developed. Same treatment as
// the geoip database. The script itself also refuses to overwrite a good list
// with a failed refresh.
val torBridgesDir = layout.projectDirectory.dir("src/main/assets/tor").asFile
val fetchTorBridgesScript = rootProject.file("scripts/fetch-tor-bridges.sh")
val torBridgesFile = File(torBridgesDir, "bridges.json")

// Same reason as the font directory: resource merging must never see a missing
// source dir on a fresh checkout.
torBridgesDir.mkdirs()

val fetchTorBridges = tasks.register("fetchTorBridges") {
    group = "build setup"
    description = "Refreshes the built-in Tor bridge list into assets/tor/bridges.json."
    val script = fetchTorBridgesScript
    val target = torBridgesFile
    val workingDir = rootProject.layout.projectDirectory.asFile
    inputs.file(script)
    outputs.file(target)
    // A list that is already there and plausible is left alone, so a local
    // rebuild costs no request. CI checks out fresh, so CI always fetches.
    outputs.upToDateWhen { target.length() > 200L }
    // Network downloads have no business in the remote build cache.
    outputs.cacheIf { false }
    doLast {
        target.parentFile.mkdirs()
        // Plain ProcessBuilder rather than project.exec: that API is deprecated
        // in Gradle 9 and reaching for the Project object at execution time is
        // what breaks the configuration cache.
        val ran = runCatching {
            val process = ProcessBuilder("bash", script.absolutePath)
                .directory(workingDir)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().forEachLine { logger.lifecycle("bridges: $it") }
            process.waitFor()
        }.getOrNull()
        if (ran != 0 || target.length() <= 200L) {
            logger.warn(
                "Could not refresh the built-in Tor bridge list (exit=${ran ?: "no bash"}). " +
                    "The app will use the list compiled into core/BridgeCatalog.kt, which works " +
                    "but is only as fresh as the source. Run scripts/fetch-tor-bridges.sh on a " +
                    "connected machine, or set TOR_BRIDGES_URL / TOR_BRIDGES_FILE / " +
                    "TOR_BRIDGES_B64.",
            )
        }
    }
}

// ====================================================== PLUGGABLE TRANSPORTS ==
//
// liblyrebird.so (obfs4 + meek_lite), libsnowflake.so and libwebtunnel.so: the
// binaries tor SPAWNS to reach a bridge, built by scripts/build-pt-transports.sh.
//
// THE BUG THIS EXISTS TO CLOSE. Nothing ever called that script. So no APK has
// ever contained a pluggable transport, core/PluggableTransports.kt found none,
// and core/BridgeLine.usable() filtered every obfuscated bridge line out of the
// torrc before tor could see it. That filtering is correct and load-bearing - tor
// treats a `Bridge obfs4 ...` line with no matching `ClientTransportPlugin` as a
// FATAL config error and exits during startup - which is exactly why the failure
// was invisible: 1.4.7 shipped a complete, working Bridges page whose every
// selection was silently dropped, and the Tor hop went to the public relays on
// networks that block them.
//
// WHY IN GRADLE and not only in .github/workflows/build.yml: the same reason as
// the bridge list above. The APK is what needs these files, so the thing that
// builds the APK is where the dependency belongs; a step in one workflow file is
// a step that does not exist for any other way of building.
//
// WHAT IT DOES WHEN IT CANNOT BUILD THEM. Cross-compiling Go for Android needs
// ANDROID_NDK_HOME and a Go toolchain, and neither is present in every
// environment that assembles this app (in CI the transports are cross-compiled
// in the natives job and travel to the app job as artifacts, so by the time
// Gradle runs they are already in jniLibs and this task is up to date). So:
//
//   * all six files present            -> up to date, no work
//   * missing, toolchain available     -> build them, and FAIL if that fails
//   * missing, no toolchain, strict    -> FAIL with the exact command to run
//   * missing, no toolchain, otherwise -> a loud warning naming what is missing
//
// Strict is -PrequirePluggableTransports=true or AETHER_REQUIRE_PT=1, and a
// release pipeline should set it: bridges are ON by default now (see
// model/Profile.kt), so an APK without these cannot honour its own default
// configuration on the networks this app exists for.
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs").asFile
val ptBinaries = listOf("liblyrebird.so", "libsnowflake.so", "libwebtunnel.so")
val buildPtScript = rootProject.file("scripts/build-pt-transports.sh")
val requirePt: Boolean =
    providers.gradleProperty("requirePluggableTransports").orNull?.toBoolean() == true ||
        providers.environmentVariable("AETHER_REQUIRE_PT").orNull == "1"

val buildPtTransports = tasks.register("buildPtTransports") {
    group = "build setup"
    description = "Builds the Tor pluggable transports into jniLibs (lyrebird, snowflake, webtunnel)."
    val jniDir = jniLibsDir
    val abis = shippedAbis
    val binaries = ptBinaries
    val script = buildPtScript
    val workingDir = rootProject.layout.projectDirectory.asFile
    val strict = requirePt
    val ndkHome = providers.environmentVariable("ANDROID_NDK_HOME").orNull
    val pathDirs = providers.environmentVariable("PATH").orNull

    // A missing .so is what this task exists to fix, so "missing" must mean
    // "run", not "up to date". Evaluated before execution, which is exactly
    // when Gradle asks.
    val missing: () -> List<String> = {
        abis.flatMap { abi ->
            binaries.filterNot { File(jniDir, "$abi/$it").exists() }.map { "$abi/$it" }
        }
    }
    inputs.file(script)
    outputs.upToDateWhen { missing().isEmpty() }
    outputs.cacheIf { false }

    doLast {
        if (missing().isEmpty()) return@doLast
        val hasGo = pathDirs.orEmpty().split(File.pathSeparatorChar).any { dir ->
            dir.isNotBlank() && File(dir, "go").canExecute()
        }
        val hasToolchain = !ndkHome.isNullOrBlank() && File(ndkHome).isDirectory && hasGo
        val advice =
            "Build them with:  export ANDROID_NDK_HOME=<ndk>  &&  bash " +
                "scripts/build-pt-transports.sh all"

        if (hasToolchain) {
            val exit = runCatching {
                val process = ProcessBuilder("bash", script.absolutePath, "all")
                    .directory(workingDir)
                    .redirectErrorStream(true)
                    .start()
                process.inputStream.bufferedReader().forEachLine { logger.lifecycle("pt: $it") }
                process.waitFor()
            }.getOrNull()
            val stillMissing = missing()
            if (exit != 0 || stillMissing.isNotEmpty()) {
                // Hard failure, deliberately: the toolchain was there, so this
                // is a real build error and not an environment without Go.
                throw GradleException(
                    "scripts/build-pt-transports.sh failed (exit=${exit ?: "could not start"}). " +
                        "Missing: ${stillMissing.joinToString().ifEmpty { "none" }}. Tor cannot " +
                        "use an obfuscated bridge without these, and bridges are on by default.",
                )
            }
            return@doLast
        }

        val message =
            "No pluggable transports in jniLibs (${missing().joinToString()}), and no Go + " +
                "Android NDK toolchain to build them with. tor cannot use obfs4, snowflake, " +
                "webtunnel or meek in this build - every such bridge line is dropped before " +
                "the torrc (see core/PluggableTransports.kt), so the Tor hop will connect to " +
                "the public relays. $advice"
        if (strict) throw GradleException(message)
        logger.warn("WARNING: $message")
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

    // AGP 9 creates unit-test tasks only for the tested build type. CI invokes
    // testReleaseUnitTest, so make release the tested build type explicitly.
    // Without this, AGP registers testDebugUnitTest only and configuration
    // fails before Gradle can run any task.
    testBuildType = "release"

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
        // Needs buildFeatures.resValues below: AGP 9 stopped enabling it
        // implicitly, and configuration fails outright if this call is present
        // while the feature is off.
        resValue("string", "app_label", "Aether (Fork)")

        // BOTH ABIs, and that is load-bearing for more than the engine: the
        // pluggable transports are cross-compiled per ABI too, and an APK that
        // carries libtor.so for an ABI without liblyrebird.so is an APK whose
        // Tor hop drops every obfuscated bridge on that ABI alone.
        ndk { abiFilters += shippedAbis }

        // Same value, same precedence as before (env first, then the Gradle
        // property, then empty), but read through the provider API.
        //
        // Gradle 9.6 deprecated resolving findProperty()/property() through the
        // PROJECT HIERARCHY and removes it in Gradle 10. `githubRepo` is set in
        // the root gradle.properties or with -PgithubRepo=..., and this is the
        // :app project, so `project.findProperty("githubRepo")` was exactly that
        // deprecated lookup and warned on every configuration under Gradle 9.7.
        // providers.gradleProperty reads Gradle properties directly instead.
        val githubRepo = project.providers.environmentVariable("GITHUB_REPOSITORY")
            .orElse(project.providers.gradleProperty("githubRepo"))
            .getOrElse("")
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
        //
        // The same is true of libpsiphon.so and libtor.so: all three chain
        // cores are executables under a .so name, not libraries. See
        // core/NativeChild.kt and docs/CHAIN_CORES.md.
        //
        // AND of the three pluggable transports - liblyrebird.so,
        // libsnowflake.so, libwebtunnel.so. Those are not even launched by this
        // app: tor spawns them itself through `ClientTransportPlugin ... exec
        // <path>`, which needs a real path on disk with the exec bit set. With
        // legacy packaging off there is no such path, and every obfuscated
        // bridge type would fail at startup rather than at configuration time.
        // See core/PluggableTransports.kt and docs/TOR_BRIDGES.md.
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

// All three have to be on disk before resource/asset merging and JNI packaging
// read the source sets, which is what makes preBuild the right hook rather than
// a dependency of the merge tasks themselves.
tasks.named("preBuild") { dependsOn(fetchVazirmatn, fetchTorBridges, buildPtTransports) }

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

    // Unit tests. This used to read `testImplementation(kotlin("test"))`, which
    // only works when the Kotlin Gradle Plugin is applied to the module: KGP is
    // what inspects the test task and rewrites that bare notation into the
    // framework-specific artifact. AGP 9 compiles Kotlin with built-in Kotlin
    // support and KGP is never applied here (see gradle/libs.versions.toml), so
    // the test classpath got plain kotlin-test - assertEquals resolved, but
    // kotlin.test.Test, a typealias that lives only in the JUnit variant, did
    // not, and :app:compileReleaseUnitTestKotlin failed with
    // "Unresolved reference 'Test'". Name the JUnit variant explicitly so
    // nothing has to be inferred.
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
