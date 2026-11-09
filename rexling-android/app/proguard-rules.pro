# JNI exports use the stable class/method names below.
-keep class dev.shulhi.rex.NativeBridge { *; }

# CameraX
-dontwarn androidx.camera.**
-keep class androidx.camera.** { *; }
-keep interface androidx.camera.** { *; }

# ZXing
-dontwarn com.google.zxing.**
-keep class com.google.zxing.** { *; }
