import java.util.Properties

val envFile = rootProject.file(".env")
val envProps = Properties().apply {
    if (envFile.exists()) envFile.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.neveroff"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.neveroff"
        minSdk = 26
        targetSdk = 34
        // 3.0 rather than a point release: this adds a consent gate before
        // account creation, a language choice, and changes what read receipts
        // mean. Also unambiguous under UpdateManager.compareVersions, which
        // compares each dotted part numerically — "2.2" would have read as
        // older than the shipped "2.12" and reached nobody.
        //
        // 3.01 fixes registration (it landed on an empty profile), adds OTP
        // resend, shows console warnings, and puts @support in search. The
        // leading zero is read as a fraction by compareVersions, so it sorts
        // above 3.0 and below a future 3.1.
        versionCode = 15
        versionName = "3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        buildConfigField("String", "SUPABASE_URL", "\"${envProps["SUPABASE_URL"] ?: ""}\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"${envProps["SUPABASE_PUBLISHABLE_KEY"] ?: ""}\"")
    }

    signingConfigs {
        // TEMPORARY — signs release builds with the SDK's debug keystore.
        //
        // Every NeverOff install in the wild is debug-signed, and Android refuses an update
        // signed by a different key. Switching to a real key forces an uninstall, which wipes
        // neveroff_crypto_prefs and the AndroidKeyStore entry that unwraps the identity
        // keypair — making every message already on the server permanently undecryptable.
        // So we keep the existing key until that migration is deliberately planned.
        //
        // This is NOT safe for a public release: the debug keystore's password is the
        // documented constant "android", so anyone can forge an APK Android treats as this
        // app. It also only exists on this one machine — if debug.keystore is lost, no future
        // update can ever be installed by existing users. Back that file up.
        create("debugKeyRelease") {
            storeFile = file("${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"

            // v3 carries a signing-block lineage, which is the only mechanism that lets this
            // app ever move to a real key without an uninstall. Rotation reads the lineage from
            // the currently installed APK, so the v3 block has to already be out in the field
            // before the switch — enabling it now is what keeps that door open later.
            // v1 stays off: it is only consulted below API 24 and this app is minSdk 26.
            enableV1Signing = false
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debugKeyRelease")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    applicationVariants.all {
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.ApkVariantOutputImpl
            output?.outputFileName = "NeverOff.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("io.coil-kt:coil-compose:2.4.0")

    // Supabase
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.realtime)
    implementation(libs.supabase.gotrue)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)

    // Room & SQLCipher
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher)

    // Crypto
    implementation(libs.tink.android)

    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.0.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // CameraX
    val cameraxVersion = "1.3.0-rc01"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Media3 (ExoPlayer)
    val media3Version = "1.1.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")

    // Biometrics
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
