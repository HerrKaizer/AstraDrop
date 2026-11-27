plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.tapdrop"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.tapdrop"
        minSdk = 33
        targetSdk = 34
        // Conscrypt's native TLS library (SolEngine pairing): phone CPUs only.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        versionCode = 30
        versionName = "2.5.3"
    }

    buildTypes {
        // The APK we hand out is this build. Not debuggable: a debuggable app runs its own code
        // much slower (the wallpaper blur took ~1 s) and lets anyone with adb read its private files.
        getByName("debug") {
            isDebuggable = false
            // libadb pulls in all of BouncyCastle (~7 MB) for a few algorithms; R8 shrinks only
            // BouncyCastle (see shrink-rules.pro).
            isMinifyEnabled = true
            proguardFiles("shrink-rules.pro")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    packaging { resources.excludes += listOf("org/bouncycastle/**", "META-INF/versions/**/OSGI-INF/**") }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    testImplementation("junit:junit:4.13.2")
}
