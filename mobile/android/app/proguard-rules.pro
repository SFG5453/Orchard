# Keep enough source information for useful release crash reports.
-keepattributes SourceFile,LineNumberTable

# ONNX Runtime's JNI layer resolves its Java classes, fields and constructors by
# hardcoded name from native code (FindClass/GetMethodID/GetFieldID). R8 renaming
# or stripping any of them turns into `java_class == null` and a SIGABRT inside
# convertToTensorInfo the first time a model runs, so the whole package must
# survive shrinking untouched.
-keep class ai.onnxruntime.** { *; }
-keep class ai.onnxruntime.providers.** { *; }
-dontwarn ai.onnxruntime.**

# WebRTC's native layer calls back into Java by hardcoded name, the same way ONNX
# Runtime's does: observers, the enums it reads signalling state from, and the
# constructors JNI instantiates are all resolved reflectively. Renaming any of them
# fails at the first setRemoteDescription rather than at build time.
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# WebRTC reaches Java through Chromium's jni_zero layer, whose C++ half looks up
# org.jni_zero.JniInit by name from JNI_OnLoad, before any Java code has run. Nothing
# on the Java side names that class, so shrinking drops it, and the native loader then
# aborts the process outright: a bare SIGTRAP with no Java frames, raised by
# System.loadLibrary at the first PeerConnectionFactory.initialize rather than anywhere
# near the party code that asked for it.
-keep class org.jni_zero.** { *; }
-dontwarn org.jni_zero.**

# NewPipeExtractor embeds Mozilla Rhino for YouTube cipher evaluation.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

# Rhino also contains optional JDK scripting/bean integrations. Android does
# not provide these desktop-only APIs, and Orchard uses Rhino's core engine
# for cipher evaluation rather than its javax.script or dynalink adapters.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
