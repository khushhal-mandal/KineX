// Imported rather than written as `java.util.Properties` below: inside a Gradle Kotlin DSL
// script, `java` resolves to the JavaPluginExtension and shadows the package name.
import java.util.Properties

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

/**
 * Where a debug build looks for the backend *by default*.
 *
 * This is a default now rather than the setting itself: Settings › Backend holds the address
 * the API client actually uses, and falls back to this value until somebody types one there.
 *
 * `10.0.2.2` is the emulator's alias for the host loopback, so the default works against
 * `docker compose up` on this machine with no configuration at all. A physical phone cannot
 * use it — it is not on the host's loopback — and wants the laptop's LAN address instead.
 * That is still one line in `local.properties`, which is gitignored and already per-machine:
 *
 *     kinex.apiBaseUrl=http://192.168.1.x:8000
 *
 * but it only seeds the default, and moving it needs an edit here and a reinstall — which is
 * the thing the setting exists to avoid on a laptop whose LAN address moves with DHCP.
 *
 * No trailing slash; the client appends paths that start with one.
 */
val devApiBaseUrl: String = run {
    val properties = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }
        ?.inputStream()?.use { properties.load(it) }
    properties.getProperty("kinex.apiBaseUrl") ?: "http://10.0.2.2:8000"
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

        buildConfigField("String", "API_BASE_URL", "\"$devApiBaseUrl\"")

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

    // The same directory, handed to the instrumented tests as assets. MigrationTestHelper
    // builds a database *at* an old version by reading that version's JSON, which is the only
    // way to get a v1 database on a device that has only ever known v2 — and therefore the only
    // way to test a migration against rows rather than against an empty table.
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    androidResources {
        // Keep the .task model uncompressed — MediaPipe reads it via AAsset_getBuffer,
        // which would otherwise inflate all 5.5 MB into heap on startup.
        noCompress += "task"
    }

    // The auth contract's committed test vector, handed to the unit tests as an absolute path
    // rather than copied into test resources. One copy exists; if it moves, the test fails
    // saying so, instead of quietly checking the Kotlin implementation against a stale twin of
    // the thing it is supposed to be checked against.
    testOptions {
        unitTests.all {
            it.systemProperty(
                "kinex.authVector",
                rootProject.file("backend/tests/vectors/auth_v1.json").absolutePath,
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Deliberately unreachable. Phase 7 has not happened, so there is no production
            // backend to name, and `.invalid` is reserved by RFC 2606 and never resolves — a
            // release build that tries to sync fails loudly rather than quietly reaching for
            // whatever laptop happened to be in local.properties at build time. Settings can
            // still point it somewhere, which is a person typing an address rather than a
            // build inheriting one, and that is the distinction this comment was ever about.
            buildConfigField("String", "API_BASE_URL", "\"https://api.kinex.invalid\"")
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
    // Ed25519. See "The auth contract" in the root design doc for why the curve is Ed25519 and
    // why that costs a dependency: the platform provider only has it from API 33, minSdk is
    // 24, and a recovery phrase rules out the Keystore regardless of curve. Also supplies
    // PBKDF2-HMAC-SHA512 for BIP-39, which `SecretKeyFactory` only offers from API 26.
    implementation(libs.bouncycastle)
    // The wire format is JSON. The serialization compiler plugin is already applied for
    // navigation's routes; this is the runtime that plugin's generated serializers call into.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.security.crypto)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}