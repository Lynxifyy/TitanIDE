# TITAN IDE release rules.
#
# R8 in release mode has to be told about everything reached by reflection. The
# rules below are grouped by the library that needs them; each group names the
# concrete failure that appears when it is missing.

# ---------------------------------------------------------------------------
# Missing optional dependencies
#
# JGit pulls in a GSSAPI/negotiate auth path and an slf4j binder binding, and
# Tink (via androidx.security) references Error Prone annotations. None of these
# classes ship on Android. Without -dontwarn, R8 fails the build outright with
# "Missing class" errors rather than dropping the unreachable code.
# ---------------------------------------------------------------------------
-dontwarn org.ietf.jgss.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn java.lang.ProcessHandle
-dontwarn org.slf4j.impl.**
-dontwarn com.google.errorprone.annotations.**

# ---------------------------------------------------------------------------
# kotlinx.serialization
#
# Generated serializers are looked up by name through the companion object. R8
# strips them and every @Serializable payload then throws
# SerializationException: Serializer for class 'X' is not found.
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisibleAnnotations
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.titanide.**$$serializer { *; }
-keepclassmembers class dev.titanide.** {
    *** Companion;
}
-keepclasseswithmembers class dev.titanide.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------------------------------------------------------------------------
# JGit
#
# JGit resolves transports, merge strategies and the FS implementation by
# reflection on class name, so these must keep their names.
# ---------------------------------------------------------------------------
-keep class org.eclipse.jgit.** { *; }
-dontwarn org.eclipse.jgit.**
# slf4j ships no binder on Android. Keeping the whole tree retains MDC's
# StaticMDCBinder lookup, which references a class that does not exist here, so
# only the API surface is kept and the binders are silenced.
-keep interface org.slf4j.Logger { *; }
-keep class org.slf4j.LoggerFactory { *; }
-dontwarn org.slf4j.**

# ---------------------------------------------------------------------------
# OkHttp / Okio
#
# OkHttp's platform detection reflects over these; on Android it also warns
# about the optional Conscrypt and Bouncy Castle providers.
# ---------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# ---------------------------------------------------------------------------
# Room
#
# Room generates an implementation per @Database and resolves it by name.
# ---------------------------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------------------------
# Compose
#
# The tooling and preview annotations are debug-only, but the runtime keeps
# referencing the parameter-name metadata for recomposition tracing.
# ---------------------------------------------------------------------------
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}
-dontwarn androidx.compose.**

# ---------------------------------------------------------------------------
# Tink / security-crypto
#
# EncryptedSharedPreferences resolves its keyset managers by class name.
# ---------------------------------------------------------------------------
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# ---------------------------------------------------------------------------
# Coroutines
#
# The debug agent and the internal exception-rewriting machinery are optional.
# ---------------------------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ---------------------------------------------------------------------------
# Reflection used by this app
#
# The extension host and the template registry both look classes up by name.
# ---------------------------------------------------------------------------
-keep class dev.titanide.core.templates.** { *; }
-keepnames class dev.titanide.** { *; }

# Keep line numbers so a release crash report still points at a real line.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
