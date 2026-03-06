# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Preserve line numbers for debugging
-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ===== Kotlinx Serialization =====
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclasseswithmembers class **$$serializer {
    *** childSerializers();
}

-keep,includedescriptorclasses class com.example.sensorstreamerwearos.**$$serializer { *; }
-keepclassmembers class com.example.sensorstreamerwearos.** {
    *** Companion;
}

# ===== Model classes =====
-keep class com.example.sensorstreamerwearos.model.** { *; }
-keep class com.example.sensorstreamerwearos.workout.model.** { *; }

# ===== Room Database =====
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface *
-keep class com.example.sensorstreamerwearos.data.** { *; }

# ===== Google Play Services Wearable =====
-keep class com.google.android.gms.wearable.** { *; }
-keep interface com.google.android.gms.wearable.** { *; }

# ===== Health Services =====
-keep class androidx.health.services.client.** { *; }
-keep interface androidx.health.services.client.** { *; }

# ===== Gson =====
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ===== Keep services =====
-keep class com.example.sensorstreamerwearos.service.** { *; }
-keep class com.example.sensorstreamerwearos.workout.service.** { *; }
-keep class com.example.sensorstreamerwearos.habit.** { *; }

# ===== Keep WearableListenerService implementations =====
-keep class * extends com.google.android.gms.wearable.WearableListenerService { *; }

# ===== Keep BroadcastReceivers =====
-keep class * extends android.content.BroadcastReceiver { *; }