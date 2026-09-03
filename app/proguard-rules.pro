# ---------------------------------------------------------------------------
# R8 is enabled for release builds. Everything below exists because something
# outside the Kotlin compiler's view reaches into this code BY NAME.
# ---------------------------------------------------------------------------

# JNI. scripts/aethertun-jni.c exports
# Java_studio_cluvex_aether_core_TProxyService_TProxy{Start,Stop}Service and
# ...TProxyGetStats. Those symbol names encode the class and method names, so
# renaming either end silently breaks the tunnel at runtime with an
# UnsatisfiedLinkError that no build check would catch.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep class studio.cluvex.aether.core.TProxyService { *; }

# Settings are persisted as enum NAMES through DataStore, so valueOf() has to
# keep resolving after obfuscation.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Parcelable CREATOR fields are looked up reflectively by the framework.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# Keep the crash handler's stack traces readable. The mapping file lands in
# app/build/outputs/mapping/release/ - keep it if you ever need to deobfuscate
# a user-reported trace.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn org.jetbrains.annotations.**
