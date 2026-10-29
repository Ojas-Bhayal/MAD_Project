# LiteRT (formerly TensorFlow Lite) JNI & reflective rules
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }

# Room Entities & DAOs
-keep class com.cropdoc.app.data.local.entity.** { *; }

# Firebase Firestore serialization safety
-keepattributes Signature, InnerClasses, EnclosingMethod

# Crashlytics line numbers for deobfuscated stacktraces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
