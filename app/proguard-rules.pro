# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in /Users/denilarsanov/Library/Android/sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.kts.

# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# --- Strip android.util.Log from release builds ---
# R8 drops these calls entirely once told they have no side effects, so nothing reaches logcat
# on a shipped build — including from bundled libraries, since the rule matches every caller.
# Error paths lose nothing: LoggerManager.logError already persists the full stack trace to
# internal storage, which is where field diagnostics actually come from.
# assumenosideeffects is safe here because Log's return value (bytes written) is never used.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}

# Keep Room classes
-keep class androidx.room.RoomDatabase
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# --- kotlinx.serialization (official recommended R8 rules) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.neveroff.**$$serializer { *; }
-keepclassmembers class com.neveroff.** {
    *** Companion;
}
-keepclasseswithmembers class com.neveroff.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class com.neveroff.ui.screens.ProfileSerializer { *; }

# --- Supabase-kt / Ktor (reflection-heavy network layer) ---
-keep class io.github.jan.supabase.** { *; }
-dontwarn io.github.jan.supabase.**
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# --- Tink (crypto) ---
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# --- SQLCipher (JNI-backed) ---
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

# --- Firebase Messaging ---
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**

# --- CameraX ---
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# --- Media3 / ExoPlayer ---
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# --- Biometric ---
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# --- slf4j (transitive dep pulled in by Ktor/Supabase; no binder on Android, which
# slf4j handles gracefully at runtime by no-oping logging — safe to silence) ---
-dontwarn org.slf4j.**
