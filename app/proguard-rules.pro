# Project ProGuard / R8 Rules for Fluxon

# Keep JNI Bridge and native methods
-keepclassmembers class io.github.p1neapplexpress.openflux.NativeBridge {
    public *;
    native <methods>;
}
-keep class io.github.p1neapplexpress.openflux.NativeBridge { *; }

# Keep AIDL interfaces
-keep class io.github.p1neapplexpress.openflux.IUnifiedService* { *; }

# Keep Custom Views instantiated in XML
-keep class io.github.p1neapplexpress.openflux.ui.widget.** {
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

-keep class io.github.p1neapplexpress.openflux.data.** { *; }
-keep class io.github.p1neapplexpress.openflux.util.AppUpdateChecker$** { *; }
-keep class io.github.p1neapplexpress.openflux.util.ConfigBackupManager$** { *; }

# Quickie, ZXing, and ML Kit
-dontwarn io.github.g00fy2.quickie.**
-dontwarn com.google.zxing.**
-dontwarn com.google.mlkit.**