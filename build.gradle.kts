// ---------------------------------------------------------------------------
// Top-level build file. It declares plugins and applies none of them; every
// version comes from gradle/libs.versions.toml so a bump happens in one place.
// ---------------------------------------------------------------------------
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// `gradle clean` from the root, without a stray `clean` task per module.
tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
