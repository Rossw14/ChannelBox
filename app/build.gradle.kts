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
        // On GitHub every build gets a higher number automatically, which is
        // how the app knows an update is newer than what's installed.
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = if (runNumber != null) 100 + runNumber else 5
        versionName = if (runNumber != null) "1.5 (build $runNumber)" else "1.5"

        // When built on GitHub, point the app at this project's TV guide (published
        // by the "Update TV guide" job) and at its latest release (for updates).
        val repo = System.getenv("GITHUB_REPOSITORY").orEmpty()
        val guideUrl = if (repo.isNotEmpty()) "https://raw.githubusercontent.com/$repo/guide/guide.xml.gz" else ""
        val updateUrl = if (repo.isNotEmpty()) "https://github.com/$repo/releases/latest/download" else ""
        buildConfigField("String", "GUIDE_URL", "\"$guideUrl\"")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")

        // Optional free TMDB key for the home page's film rows, from the TMDB_API_KEY GitHub secret.
        val tmdbKey = System.getenv("TMDB_API_KEY").orEmpty().filter { it.isLetterOrDigit() || it in "._-" }
        buildConfigField("String", "TMDB_KEY", "\"$tmdbKey\"")
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
    implementation("androidx.core:core:1.13.1")
}
