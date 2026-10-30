plugins {
    id("com.android.application")
}

import java.util.Base64

// Shared debug signing key (same keystore as Friday/Kitchlore/Kinalm): new APKs install over old ones.
val sharedDebugKeystore = layout.buildDirectory.file("shared-debug.keystore").get().asFile.also { f ->
    if (!f.exists()) {
        f.parentFile.mkdirs()
        f.writeBytes(Base64.getMimeDecoder().decode(rootProject.file("debug.keystore.b64").readText()))
    }
}

android {
    namespace = "com.trypod33.centipede"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trypod33.centipede"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = sharedDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("shared") {
            storeFile = sharedDebugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
