# libtorrent4j uses JNI/SWIG – keep everything
-keep class org.libtorrent4j.** { *; }
-dontwarn org.libtorrent4j.**
# Jellyfin ffmpeg decoder (JNI)
-keep class androidx.media3.decoder.ffmpeg.** { *; }
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.tormeo.app.** { *** Companion; }
-keepclasseswithmembers class com.tormeo.app.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.tormeo.app.**$$serializer { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
