plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Room's annotation processor. KSP rather than KAPT: KAPT runs the whole Java stub
    // pipeline and is on its way out for Kotlin 2.x, and Room ships a first-party KSP
    // processor.
    alias(libs.plugins.ksp)
    // Navigation's type-safe routes are @Serializable, and this is what generates their
    // serializers. See the note in libs.versions.toml for why this one is safe to pin to the
    // project's Kotlin when KSP is not.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.kinex"
    compileSdk {
        version = release(36)
    }
    // Pinned so every machine and CI runner builds the engine with one toolchain.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.kinex"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // MediaPipe's native lib is 11-15 MB per ABI; arm64-v8a alone keeps the
            // APK around 21 MB instead of 58 MB.
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Room writes the schema of every version it compiles here, and the directory is
    // committed. That JSON is what a Phase 9 migration is written and tested against — Room
    // can only auto-verify a migration it can see both ends of, and without the file the
    // only record of version 1's shape is whatever shipped in an APK.
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    androidResources {
        // Keep the .task model uncompressed — MediaPipe reads it via AAsset_getBuffer,
        // which would otherwise inflate all 5.5 MB into heap on startup.
        noCompress += "task"
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG gates the replay mode out of release builds.
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mediapipe.tasks.vision)
    // room-ktx is not here on purpose: as of Room 2.7 its coroutine and Flow support was
    // folded into room-runtime and the artifact is empty.
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    // Six destinations. MainActivity used to say "a third is what would justify a navigation
    // library" over a two-value enum; this session is what cashed that in.
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}