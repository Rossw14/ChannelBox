plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "uk.co.channelbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "uk.co.channelbox"
        minSdk = 21
        targetSdk = 35
        versionCode = 5
        versionName = "1.4"

        // When built on GitHub, point the app at the TV guide that this
        // project's "Update TV guide" job publishes on its "guide" branch.
        val repo = System.getenv("GITHUB_REPOSITORY").orEmpty()
        val guideUrl = if (repo.isNotEmpty()) {
            "https://raw.githubusercontent.com/$repo/guide/guide.xml.gz"
        } else {
            ""
        }
        buildConfigField("String", "GUIDE_URL", "\"$guideUrl\"")
    }

    buildFeatures {
        buildConfig = true
    }

    // A fixed signing key so each new build installs over the previous one
    // on the TV (keeping your favourites and settings).
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "channelbox"
            keyAlias = "channelbox"
            keyPassword = "channelbox"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    val media3 = "1.5.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
