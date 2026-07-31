# Add any ProGuard rules specific to 3mail here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Uncomment this to preserve the line number information for
# debugging stack traces.
-keepattributes SourceFile,LineNumberTable

# If you keep the line number table, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Keep rules for release builds (R8 / minification).
#
# Minification is ON (isMinifyEnabled = true) - see app/build.gradle.kts.
# These rules ensure that reflection-heavy libraries and dynamically-loaded
# classes survive the optimization pass.
# ---------------------------------------------------------------------------

# JavaMail (android-mail) resolves providers and MIME handlers reflectively via
# META-INF/javamail.* and Class.forName, so its classes must survive.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
-dontwarn com.sun.mail.**
-dontwarn javax.mail.**
-dontwarn javax.activation.**

# Google API client + Gmail/Calendar services use reflective JSON (de)serialization
# over @Key-annotated model fields.
-keep class com.google.api.client.** { *; }
-keep class com.google.api.services.** { *; }
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-dontwarn com.google.api.client.**
-dontwarn com.google.api.services.**

# Bouncy Castle - OpenPGP implementation. The PgpEngine uses reflection to
# locate providers and packet parsers. Ed25519/X25519 support requires BC's
# JCE provider to remain intact.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# WorkManager Workers. Since we use a manual ThreeMailWorkerFactory, R8 must
# not strip the Worker classes themselves or the factory won't be able to
# resolve their names at runtime.
-keep class com.threemail.android.sync.*Worker { *; }

# General reflection safety: retain annotations and signatures used by
# Dagger/Hilt, Room, and the Google HTTP client.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses

# Suppress warnings for missing classes that are part of the standard Java
# library but are not available (or needed) on Android. These are typically
# referenced by transitive dependencies like Apache HttpClient.
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
