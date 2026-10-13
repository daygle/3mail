// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Raise vulnerable transitive dependencies on the build-tooling classpath.
// These are pulled in by the Android Gradle Plugin (sdk-common, builder,
// apkzlib, bundletool, jetifier, ...) - not by the app - but Dependabot
// reports them against the build because the dependency graph submission
// covers the plugin classpath. Constraints only set a floor: if AGP later
// requests a newer version, that one still wins.
buildscript {
    dependencies {
        constraints {
            // GHSA alerts on bcprov/bcpkix 1.80.2 (name constraints bypass,
            // ASN.1 nesting-depth guard, LDAP injection, bcpkix risky crypto).
            // Keep in step with the app's bcprov/bcpg in libs.versions.toml.
            classpath("org.bouncycastle:bcprov-jdk18on:1.86")
            classpath("org.bouncycastle:bcpkix-jdk18on:1.86")
            classpath("org.bouncycastle:bcutil-jdk18on:1.86")
            // XXE in JDOM < 2.0.6.1 (via jetifier-processor).
            classpath("org.jdom:jdom2:2.0.6.1")
            // XSS in Apache HttpClient < 4.5.13 (via httpmime 4.5.6).
            classpath("org.apache.httpcomponents:httpclient:4.5.14")
            // Uncontrolled recursion in commons-lang3 < 3.18.0 (via commons-compress).
            classpath("org.apache.commons:commons-lang3:3.21.0")
            // DoS via compressed JWE in jose4j (via bundletool).
            classpath("org.bitbucket.b_c:jose4j:0.9.7")
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
