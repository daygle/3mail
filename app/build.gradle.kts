import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(localPropertiesFile.inputStream())
}

configure<com.android.build.api.dsl.ApplicationExtension> {
    namespace = "com.threemail.android"
    // Bumped to 37: several androidx libraries (hilt 1.4.0, lifecycle 2.11.0,
    // core 1.19.0, activity/navigationevent 1.13/1.0) declare minCompileSdk=36
    // or 37 in their AAR metadata. compileSdk is decoupled from targetSdk
    // (still 35, no new runtime behaviour) and minSdk (bumped from 26 to 31 to
    // avoid lint errors from API 31+ calls used throughout the codebase).
    compileSdk = 37

    defaultConfig {
        applicationId = "com.threemail.android"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        val webClientId = localProperties.getProperty("google.web_client_id") ?: "YOUR_WEB_CLIENT_ID"
        resValue("string", "default_web_client_id", webClientId)
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        resValues = true
    }
    testOptions {
        unitTests {
            // Robolectric needs the app's merged resources/manifest so unit
            // tests that read app resources (e.g. context.getString(R.string.*))
            // resolve them instead of falling back to the framework-only
            // resource table and throwing "Bad identifier".
            isIncludeAndroidResources = true
        }
    }
    packaging {
        jniLibs {
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
            keepDebugSymbols += "**/libdatastore_shared_counter.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1,LICENSE.md,LICENSE.txt,NOTICE.md,NOTICE.txt}"
            // The Google API / JavaMail jars ship overlapping metadata files.
            excludes += "/META-INF/INDEX.LIST"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/LICENSE"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

// The Room Gradle plugin (alias(libs.plugins.androidx.room) in plugins {})
// MANDATES a schemaDirectory extension at configuration time even when no
// Room database declares exportSchema=true (Utils.kt:52). We disable
// exportSchema in ThreeMailDatabase.kt because Room 2.8.4's bundled
// serializer classes are ABI-incompatible with the serialization-core
// KSP 2.2.10-2.0.2 puts on its daemon classpath, causing an
// AbstractMethodError at kspDebugKotlin time. The directory is unused but
// the extension is required; keep it stable so a future Room upgrade can
// simply flip exportSchema back to true.
room {
    schemaDirectory("$projectDir/schemas")
}

// Surface full failure details (assertion message + stack) for unit tests in
// the CI console, since the HTML/XML report artifacts aren't always reachable
// when triaging a red build.
tasks.withType<Test>().configureEach {
    // Robolectric reflects into OpenJDK internals on Java 17+; SDK 36+ reads
    // FileDescriptor internals during app-state setup and fails with
    // "Failed to interact with raw FileDescriptor internals" without these.
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.security=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
    )
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Mail (android-mail already bundles android-activation, so no separate
    // com.sun.activation:javax.activation dependency - it duplicates the classes)
    implementation(libs.java.mail)

    // Google Sign-In / Gmail
    implementation(libs.play.services.auth)
    implementation(libs.google.api.client.android)
    implementation(libs.google.api.services.gmail)
    implementation(libs.google.api.services.calendar)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp3)

    // Encrypted credential storage uses the platform Android Keystore directly
    // (see CredentialStore.kt); no androidx.security:security-crypto needed.
    implementation(libs.androidx.credentials.core)
    implementation(libs.androidx.credentials.play.auth)
    // Provides com.google.android.libraries.identity.googleid.{GetGoogleIdOption, GoogleIdTokenCredential}
    // for the Google's Credential Manager sign-in flow. Not bundled with credentials-play-services-auth.
    implementation(libs.androidx.googleid)

    // In-app OpenPGP provider. See OpenPgpController.kt for the rationale:
    // the upstream openpgp-api artifact hasn't shipped a public release since
    // 2014 and the OpenKeychain-brokered path requires their app to be
    // installed - we run our own cryptographic operations against Bouncy Castle.
    // The Android Keystore-backed CredentialStore is used to wrap the private
    // key at rest so the keyring in the app's private files dir never holds
    // plaintext bytes.
    implementation(libs.org.bouncycastle.bcpg)
    implementation(libs.org.bouncycastle.bcprov)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.ui.test.junit4)
    // Compose's ui-test brings espresso-core 3.5.0 transitively, and that
    // version injects events through InputManager.getInstance(), which the
    // SDK 37 framework no longer has (NoSuchMethodException out of
    // Espresso.onIdle, i.e. every Compose UI test at SDK 37). Pin the same
    // 3.7.0 the instrumented tests use, which no longer takes that path.
    testImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    // ui-test-junit4 is needed in BOTH test source sets: the Robolectric
    // suite (src/test) and the instrumented smoke suite (src/androidTest).
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}

// Robolectric's android-all framework jar, resolved by Gradle instead of by
// Robolectric at test runtime.
//
// By default Robolectric downloads this ~150 MB jar itself, from inside the
// test JVM, straight from repo1.maven.org - outside Gradle's dependency cache
// (so CI's setup-gradle cache never keeps it) and with no retry. Maven Central
// rate-limits that with HTTP 429, which failed every Robolectric test class
// at once ("Failed to fetch maven artifact
// org.robolectric:android-all-instrumented:..."). Resolving it here goes
// through Gradle's repositories, retries and cache, and the test task then
// runs Robolectric in offline mode against the resolved copy.
//
// The coordinate must match the Robolectric version and the sdk= pin: every
// Robolectric release carries its own SDK -> android-all-instrumented table,
// and the offline test run resolves exactly the jar it names there. 4.18-beta-1
// maps SDK 37 -> 17-robolectric-16653203-i7; 4.17 maps 37 ->
// 17-robolectric-15733970-i7 and has no 16653203 entry at all. Changing this
// line alone makes Robolectric look for a build id the offline directory does
// not contain, which fails every Robolectric class at once with
// "Path is not a file: ...android-all-instrumented-...".
val robolectricAndroidAll: Configuration by configurations.creating {
    isCanBeConsumed = false
    isTransitive = false
}

dependencies {
    robolectricAndroidAll("org.robolectric:android-all-instrumented:17-robolectric-16653203-i7")
}

val robolectricJarsDir = layout.buildDirectory.dir("robolectric-jars")

val syncRobolectricJars by tasks.registering(Sync::class) {
    from(robolectricAndroidAll)
    into(robolectricJarsDir)
}

tasks.withType<Test>().configureEach {
    dependsOn(syncRobolectricJars)
    inputs.files(robolectricAndroidAll).withPropertyName("robolectricAndroidAll")
    systemProperty("robolectric.offline", "true")
    systemProperty(
        "robolectric.dependency.dir",
        robolectricJarsDir.get().asFile.absolutePath,
    )
}
