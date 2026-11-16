# Native plugins are separate binaries. Calls used only by plugins are invisible
# to the host shrinker, including Kotlin top-level functions and default overloads.
-keep,includedescriptorclasses class dev.metrofuse.api.** { *; }
-keep,includedescriptorclasses class dev.metrofuse.sdk.** { *; }

# Public/protected members of host-provided compileOnly dependencies are SDK ABI.
# Keep their signatures and implementations without disabling app optimization.
-keep,includedescriptorclasses class kotlin.** { public *; protected *; }
-keep,includedescriptorclasses class kotlinx.coroutines.** { public *; protected *; }
-keep,includedescriptorclasses class kotlinx.serialization.** { public *; protected *; }
-keep,includedescriptorclasses class androidx.compose.** { public *; protected *; }
-keep,includedescriptorclasses class io.ktor.** { public *; protected *; }
-keep,includedescriptorclasses class kotlinx.io.** { public *; protected *; }
-keep,includedescriptorclasses class okhttp3.** { public *; protected *; }
-keep,includedescriptorclasses class okio.** { public *; protected *; }
