-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class org.etg.migration.model.** { *; }
-keepclassmembers class org.etg.migration.model.** {
    *** Companion;
}
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-dontwarn kotlinx.serialization.**
