# ============================================================
# FilmLightMeter ProGuard / R8 Rules
# ============================================================

# ---- Kotlin ----
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# ---- Kotlin Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ---- Compose ----
-keep class androidx.compose.** { *; }
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <methods>;
}
# Keep functions referenced by remember {}
-keepclassmembers class * {
    @androidx.compose.runtime.Composable <init>(...);
}

# ---- Lifecycle / ViewModel ----
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.AndroidViewModel { *; }

# ---- CameraX ----
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**

# ---- App models (keep for reflection / serialization) ----
-keep class com.lightmeter.app.settings.** { *; }
-keep class com.lightmeter.app.metering.** { *; }
-keep class com.lightmeter.app.exposure.** { *; }
-keep class com.lightmeter.app.camera.** { *; }

# ---- Keep entry points ----
-keep class com.lightmeter.app.MainActivity { *; }

# ---- General ----
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator CREATOR;
}
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
