# Xposed Module
-keep class com.adblocker.xposed.module.** { *; }
-keep class com.adblocker.xposed.hook.** { *; }
-keep class de.robv.android.xposed.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
