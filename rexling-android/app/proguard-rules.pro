# JNI exports use the stable class/method names below.
-keep class dev.lemon.rex.NativeBridge { *; }

# CameraX: rely on AAR consumer rules; suppress non-fatal warnings
-dontwarn androidx.camera.**

# ZXing: keep only QR code scanning classes used by QrScanner.kt
-dontwarn com.google.zxing.**
-keep class com.google.zxing.qrcode.** { *; }
-keep class com.google.zxing.BinaryBitmap { *; }
-keep class com.google.zxing.MultiFormatReader { *; }
-keep class com.google.zxing.Result { *; }
-keep class com.google.zxing.LuminanceSource { *; }
-keep class com.google.zxing.PlanarYUVLuminanceSource { *; }
-keep class com.google.zxing.RGBLuminanceSource { *; }
-keep class com.google.zxing.Reader { *; }
-keep class com.google.zxing.DecodeHintType { *; }
-keep class com.google.zxing.BarcodeFormat { *; }
-keep class com.google.zxing.common.HybridBinarizer { *; }
-keep class com.google.zxing.common.BitMatrix { *; }
-keep class com.google.zxing.common.BitArray { *; }
