plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.junjo.eztranscribe"
    compileSdk = 37
    // r30 builds 16 KB-aligned native libraries by default, which Android 15+ devices may require.
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "dev.junjo.eztranscribe"
        minSdk = 33
        // Held one behind compileSdk: the app opts in to 37's runtime behaviour
        // only once there is something on a device to test it against.
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"

        ndk {
            // whisper.cpp is built for 64-bit ARM only: the target phone, and every phone fast
            // enough to run large-v3-turbo usefully.
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with this machine's debug key on purpose: the copy already on the phone was
            // signed with it, so GitHub releases (installed through Obtainium) update it in place.
            // A build from another machine gets that machine's key and installs as a separate signer.
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
