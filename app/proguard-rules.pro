# Keep Moshi/Kotlin metadata
-keepclassmembers class kotlin.Metadata { *; }
-keep class kotlin.reflect.jvm.internal.** { *; }

# Retrofit
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response

# Moshi
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class *
