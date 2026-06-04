-keepattributes *Annotation*
-keepclassmembers class ** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# Sherpa-ONNX
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keepclasseswithmembernames class * { native <methods>; }

# osmdroid (map view for the trip-mode pin picker)
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
