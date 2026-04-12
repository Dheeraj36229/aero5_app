# AERO5 ProGuard Rules

# Keep Firebase classes
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Keep our app classes
-keep class com.aero5.mask.** { *; }

# Keep JavaScript interface methods
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep WebView bridge
-keepattributes JavascriptInterface
-keepattributes *Annotation*

# WorkManager
-keep class androidx.work.** { *; }

# Suppress warnings
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**
