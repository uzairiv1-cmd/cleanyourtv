plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.iptvplayer.tv"
    compileSdk = 36

    defaultConfig {
        // Set in gradle.properties; see the note there before the first Google Play upload.
        applicationId = providers.gradleProperty("iptvAppId").get()
        minSdk = 26
        targetSdk = 36
        versionCode = providers.gradleProperty("iptvVersionCode").get().toInt()
        versionName = providers.gradleProperty("iptvVersionName").get()
    }

    // Release signing comes from the environment so the keystore never sits in the repo.
    // For Google Play this is the upload key (Play App Signing holds the key users' devices see).
    // The launcher pins the owner's card decision to the signing key: sign every release with the same one.
    val ks = System.getenv("IPTV_KEYSTORE")
    signingConfigs {
        if (ks != null) {
            create("release") {
                storeFile = file(ks)
                storePassword = System.getenv("IPTV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("IPTV_KEY_ALIAS")
                keyPassword = System.getenv("IPTV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ks != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    lint {
        abortOnError = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // QR code on the "Add from phone" screen.
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // XmlPullParser outside Android, for the XMLTV guide tests.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
