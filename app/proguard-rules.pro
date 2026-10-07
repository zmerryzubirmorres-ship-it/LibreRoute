# Project ProGuard / R8 Rules for LibreRoute

# Keep JNI Bridge and native methods
-keepclassmembers class io.github.libreroute.NativeBridge {
    public *;
    native <methods>;
}
-keep class io.github.libreroute.NativeBridge { *; }

# Keep AIDL interfaces
-keep class io.github.libreroute.IUnifiedService* { *; }

# Keep Custom Views instantiated in XML
-keep class io.github.libreroute.ui.widget.** {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    *;
}

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt

-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

-keepclassmembers class * {
    *** Companion;
    *** serializer();
}

-keep class io.github.libreroute.data.** { *; }
-keep class io.github.libreroute.util.AppUpdateChecker$** { *; }
-keep class io.github.libreroute.util.ConfigBackupManager$** { *; }

# Quickie, ZXing, and ML Kit
-dontwarn io.github.g00fy2.quickie.**
-dontwarn com.google.zxing.**
-dontwarn com.google.mlkit.**