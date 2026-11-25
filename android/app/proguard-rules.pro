# Keep generated kotlinx.serialization serializers reachable through reflection.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer {
    *** serializer(...);
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
