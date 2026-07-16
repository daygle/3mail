# Add any ProGuard rules specific to 3mail here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number table, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---------------------------------------------------------------------------
# Keep rules for release builds (R8 / minification).
#
# Minification is currently OFF (isMinifyEnabled = false) - see
# app/build.gradle.kts. These rules are staged so that enabling R8 for release
# doesn't strip the reflection-heavy mail/Google libraries and crash at
# runtime. Verify on a device once minification is turned on.
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

# GSON / model classes referenced reflectively by the Google HTTP client.
-keepattributes Signature, *Annotation*, EnclosingMethod, InnerClasses
