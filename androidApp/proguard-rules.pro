# Preserve retrace and source-context metadata. Libraries provide their own
# consumer rules; app code is referenced directly or rooted by the manifest.
-keepattributes SourceFile,LineNumberTable

# Keep real class and method names so user-submitted crash reports are readable
# without the private R8 mapping. Shrinking and optimization stay enabled.
-dontobfuscate

# Dynamically loaded APKs are compiled separately against this binary API.
-keep,includedescriptorclasses class dev.metrofuse.api.** { *; }

# Host-provided compileOnly SDK dependencies must retain plugin-only calls too.
-keep,includedescriptorclasses class kotlin.** { public *; protected *; }
-keep,includedescriptorclasses class kotlinx.coroutines.** { public *; protected *; }
-keep,includedescriptorclasses class kotlinx.serialization.** { public *; protected *; }
-keep,includedescriptorclasses class androidx.compose.** { public *; protected *; }

# JNI calls private QuickJS callbacks and creates these objects directly.
# These supplement its Android consumer rules with a stable, unoptimized JNI ABI.
-keep class com.dokar.quickjs.QuickJs { *; }
-keep class com.dokar.quickjs.QuickJsException { *; }
-keep class com.dokar.quickjs.MemoryUsage { *; }
-keep class com.dokar.quickjs.binding.JsProperty { *; }
-keep class com.dokar.quickjs.binding.JsFunction { *; }
-keep class com.dokar.quickjs.binding.JsObject { *; }
-keep class kotlin.UByteArray { *; }

# Ktor's optional IntelliJ debugger detector references desktop-only JMX APIs.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Ktor 3.6.0's unused named-module loader and receive(KClass) overload.
# Keep this list narrow so new reflection helper classes fail the release build.
-dontwarn kotlin.reflect.full.KCallables
-dontwarn kotlin.reflect.full.KClasses
-dontwarn kotlin.reflect.full.KClassifiers
-dontwarn kotlin.reflect.jvm.ReflectJvmMapping

# ScrobbleManager uses coroutine lambdas that R8 may strip as unreachable.
-keep class com.metrolist.shared.scrobble.ScrobbleManager$** { *; }
