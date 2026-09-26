# ProGuard / R8 rules for the release build of the on-device agent.
#
# Most modern AndroidX / Kotlin / Compose libraries ship consumer ProGuard
# rules in their AAR (R8 reads them automatically), so the rules below only
# cover what is *not* covered by a library's own `consumer-rules.pro`:
#  - reflection-driven code paths (Koog agents, kotlinx.serialization).
#  - native interop layers that R8 has no AST visibility into
#    (MediaPipe / LiteRT / SQLCipher).
#  - the `androidx.appfunctions` library surface (see its section).
#  - Stack-trace fidelity for Crashlytics.
#
# Every class, annotation and package named in a class specification below must
# exist on the release classpath: R8 matches a wrong name with nothing and says
# nothing, so `verify<Variant>KeepRuleTargets` checks the names before R8 runs.
# A rule that is not needed any more is deleted, not left behind as a comment.

# ─── Stack traces ────────────────────────────────────────────────────────────
# Preserve file + line info so Crashlytics-mapped stacks resolve to the right
# source positions. `-renamesourcefileattribute` makes obfuscated source-file
# attribute report a stable name instead of the original path.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ─── Kotlin metadata + annotations ───────────────────────────────────────────
# Required by every reflection-using library (Koog, kotlinx.serialization,
# Hilt's Kotlin codegen). The Kotlin Gradle plugin no longer adds these
# implicitly when consuming third-party AARs.
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.reflect.**

# ─── Coroutines ──────────────────────────────────────────────────────────────
# The `coroutines-core` AAR ships its own rules; this only keeps the
# debug-only ServiceLoader entry so R8 doesn't warn on missing classes.
-dontwarn kotlinx.coroutines.debug.**

# ─── kotlinx.serialization (used transitively by Koog) ───────────────────────
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# ─── Gson ────────────────────────────────────────────────────────────────────
# `app_functions_*.xml` and chat-export payloads round-trip through Gson.
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-dontwarn com.google.gson.**

# ─── Flogger (MediaPipe's logging backend) ───────────────────────────────────
# MediaPipe's `tasks-text` pulls in `com.google.flogger:flogger`, whose
# `FluentLogger.forEnclosingClass()` resolves the log site by **walking the call
# stack** for a frame belonging to flogger itself and taking the frame after it.
# R8 is free to inline those tiny methods away, and the frame then never appears
# — flogger throws `IllegalStateException: no caller found on the stack for: …`
# from `Graph.<clinit>`, which surfaces as `ExceptionInInitializerError` the
# first time `TextEmbedder.createFromOptions` runs. That is the on-device
# embedding path, so in a minified build every message that touches long-term
# memory killed the process. Debug builds never see it (no R8), which is exactly
# why it survived dogfooding.
#
# Pinning the flogger classes keeps their names and stops R8 from inlining them
# away, so the stack walk finds its anchor frame again. Upstream bug (still
# open): https://github.com/google-ai-edge/mediapipe/issues/6138
-keep class com.google.common.flogger.** { *; }
-dontwarn com.google.common.flogger.**

# ─── Protobuf lite (MediaPipe's graph configuration) ─────────────────────────
# MediaPipe describes its task graph as a protobuf and parses it on every
# `TextEmbedder.createFromOptions`. protobuf-javalite never calls a constructor:
# `getDefaultInstance()` materialises the message through
# `Unsafe.allocateInstance`. R8 in full mode sees no allocation site, concludes
# the class is never instantiated, and marks it **abstract** — after which the
# same call throws `InstantiationException: Can't instantiate abstract class
# com.google.protobuf.Any`, `TextEmbedder` never initialises, and every
# save-to-memory fails with "Couldn't save to memory".
#
# Release-only and silent: debug builds skip R8 entirely, and the failure is
# caught and shown as a snackbar rather than a crash, so no stack trace reaches
# logcat in a release build (which plants no Timber tree). It was found by
# building a minified APK that logs.
-keep class com.google.protobuf.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn com.google.protobuf.**

# ─── MediaPipe + LiteRT (native + reflection) ────────────────────────────────
# JNI bindings reach into Java classes by name; R8 cannot follow native frame.
# LiteRT-LM ships under `com.google.ai.edge`; no dependency has
# `org.tensorflow.lite` classes, so no keep rule names that package.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.ai.edge.**
-dontwarn org.tensorflow.lite.**

# ─── SQLCipher ───────────────────────────────────────────────────────────────
# `net.zetetic:sqlcipher-android` loads its native lib by reflection.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# ─── Koog (reflection-heavy agent framework) ─────────────────────────────────
# Koog uses kotlinx.serialization + reflection to materialise nodes, tools,
# and pipeline graphs at runtime. Keep the whole surface — shrinking gains
# from minifying Koog are small relative to the runtime breakage risk.
-keep class ai.koog.** { *; }
-keepclassmembers class ai.koog.** { *; }
-dontwarn ai.koog.**

# ─── Ktor (used by Koog HTTP clients) ────────────────────────────────────────
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ─── AppFunctions ────────────────────────────────────────────────────────────
# What the platform loads BY NAME is the service KSP generates from the entry point
# (`data.tools.local.appfunctions.KnotworkAppFunctionService`), bound through the
# manifest — which is what keeps it. The service reaches its inventory and the
# `@AppFunction` methods by ordinary calls, so R8 may rename those. The rule below
# keeps the library's whole package as well: it still ships the older auto-merged
# services and their name-loaded aggregated classes, unused here, and its consumer
# rules keep `@AppFunctionSerializable` types by annotation. `verify<Variant>Instantiable`
# checks the generated service is in the dex under its own name. (Four rules here
# once named the interfaces and the annotation in packages the library does not
# use, and matched nothing.)
-keep class androidx.appfunctions.** { *; }
-dontwarn androidx.appfunctions.**

# ─── Hilt ────────────────────────────────────────────────────────────────────
# AGP's Hilt plugin ships most rules, but the `_HiltModules` aggregated
# components occasionally get over-shrunk on R8 full-mode. Pin them.
-keep class * extends dagger.hilt.android.internal.managers.* { *; }
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-dontwarn dagger.hilt.**

# ─── Room ────────────────────────────────────────────────────────────────────
# Room's annotation processor generates `*_Impl` classes that subclass our
# DAOs and the database; consumer rules cover this, but we keep an explicit
# blanket rule for safety since the DB instantiation is reflective.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**
