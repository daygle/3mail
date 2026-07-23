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
            isMinifyEnabled = false
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
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
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
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    // ui-test-junit4 is needed in BOTH test source sets: the Robolectric
    // suite (src/test) and the instrumented smoke suite (src/androidTest).
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
