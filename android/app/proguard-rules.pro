# This ROM ships an ancient Guava on the boot classpath
# (/system/framework/libsetting.jar), and boot classes always shadow app classes
# with the same names, which crashes Media3's ImmutableMap usage at runtime.
# Keep every class as-is EXCEPT Guava, leaving R8 free to rename our Guava copy
# (and rewrite every reference to it) so the boot-classpath copy can never match.
-keep class !com.google.common.**, ** { *; }

-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**

# libVLC JNI bindings: the native side resolves Java methods by name.
-keep class org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-keep class org.videolan.** { *; }
