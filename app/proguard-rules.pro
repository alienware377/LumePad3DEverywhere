# CNSDK relies on Java helper classes reached from native code; keep them.
-keep class com.leia.** { *; }
-keep interface com.leia.** { *; }

# TensorFlow Lite GPU delegate.
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
