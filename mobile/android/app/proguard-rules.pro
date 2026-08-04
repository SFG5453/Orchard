# Keep enough source information for useful release crash reports.
-keepattributes SourceFile,LineNumberTable

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
