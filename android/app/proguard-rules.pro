# Add project specific ProGuard rules here.

# Strip all android.util.Log calls so no debug output can be read from logcat
# in release builds. Requires minifyEnabled true in build.gradle to take effect.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}

# Keep JNI bridge (native pinyin engine) — R8 must not rename these
-keepclasseswithmembernames class com.keyboard101.PinyinEngine {
    native <methods>;
}

# Keep IME service entry points
-keep class com.keyboard101.Keyboard101Service { *; }
-keep class com.keyboard101.SettingsActivity  { *; }
