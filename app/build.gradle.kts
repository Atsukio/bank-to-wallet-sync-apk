import java.util.Properties

plugins {
    // AGP 9 compiles Kotlin itself (built-in Kotlin), so there's no separate Kotlin plugin.
    id("com.android.application")
}

// Google Play upload key, described in keystore.properties next to settings.gradle.kts (git-ignored).
// Read through a provider so the configuration cache notices when it changes.
val keystore = providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
    .asText.orNull?.let { text -> Properties().apply { load(text.reader()) } }

android {
    namespace = "app.banksync"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.banksync"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "1.3"
    }

    signingConfigs {
        if (keystore != null) {
            create("upload") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // The upload key when keystore.properties exists (needed for Play); otherwise the debug
            // key, which is fine for sideloading but rejected by Play.
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    // Kotlin's JVM target follows targetCompatibility under built-in Kotlin.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Material 3 for the UI only. Everything on the background path (listener, parser, SQLite,
    // JobScheduler, HttpURLConnection) uses framework APIs, so the notification path stays cheap.
    implementation("com.google.android.material:material:1.12.0")
    // App lock prompt (fingerprint, face or screen lock). UI only, like Material.
    implementation("androidx.biometric:biometric:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
