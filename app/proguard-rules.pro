# BouncyCastle registers providers reflectively; keep the provider surface intact.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# apksig reflects over nothing but does reference optional desktop-only classes.
-dontwarn com.android.apksig.**
-keep class com.android.apksig.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.miron404.appcloner.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.miron404.appcloner.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Never let the optimizer keep debug logging of secrets around.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}

# ARSCLib walks its own block tree reflectively in places and references desktop-only APIs.
-keep class com.reandroid.** { *; }
-dontwarn com.reandroid.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
