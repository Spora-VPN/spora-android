import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The four ABIs the Rust core is built for; scripts/build-native.sh builds
// the same list.
val nativeAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

/**
 * Builds libspora_ffi.so for a release APK from the core/ submodule with
 * scripts/build-native.sh --release (pinned toolchain, reproducible flags,
 * bindings check). It runs on every release build, since cargo tracks its own
 * inputs, and it is the only source of native code in a release APK.
 */
abstract class BuildNativeLibs : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Internal abstract val script: RegularFileProperty
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        execOperations.exec {
            commandLine(
                script.get().asFile.path, "--release",
                "--ndk", ndkDirectory.get().asFile.path,
                "--out", outputDir.get().asFile.path,
            )
        }
    }
}

android {
    namespace = "to.spora.android"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "to.spora.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // JNA's AAR also carries armeabi, mips and mips64 libraries, which
        // would only bloat the APK: there is no core for them.
        ndk { abiFilters += nativeAbis }
    }

    // Native libraries: a release APK gets only what buildNativeRelease
    // builds from core/ (wired below). Dev builds of the core
    // (scripts/build-native.sh, ../spora/build-ffi.sh) land in the gitignored
    // src/main/jniLibs, which only debug builds package, so a stale dev
    // library can never reach a release.
    sourceSets {
        getByName("main") { jniLibs.setSrcDirs(emptyList<String>()) }
        getByName("debug") { jniLibs.srcDir("src/main/jniLibs") }
    }

    // AGP would otherwise put a dependency list, encrypted with a Google key,
    // into the APK signing block: opaque to everyone else, and F-Droid asks
    // for it to be off.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Release signing, configured entirely from ~/.gradle/gradle.properties
    // or the environment — never committed. When absent (CI, other machines)
    // assembleRelease produces an unsigned APK and debug builds are
    // unaffected. The keystore is the app's permanent identity: updates only
    // install over installs signed with the same certificate.
    val sporaKeystore = providers.gradleProperty("sporaKeystore").orNull
        ?: System.getenv("SPORA_KEYSTORE")
    val sporaKeystorePassword = providers.gradleProperty("sporaKeystorePassword").orNull
        ?: System.getenv("SPORA_KEYSTORE_PASSWORD")

    signingConfigs {
        if (sporaKeystore != null && sporaKeystorePassword != null) {
            create("release") {
                storeFile = file(sporaKeystore)
                storePassword = sporaKeystorePassword
                keyAlias = providers.gradleProperty("sporaKeyAlias").orNull
                    ?: System.getenv("SPORA_KEY_ALIAS") ?: "spora"
                keyPassword = providers.gradleProperty("sporaKeyPassword").orNull
                    ?: System.getenv("SPORA_KEY_PASSWORD") ?: sporaKeystorePassword
            }
        }
    }

    // The diagnostics service's API (Diagnostics.kt). Debug builds can be
    // pointed at a test server (-PsporaApi=<urls> or SPORA_API, comma-
    // separated as for the CLI) and report "<version>-debug", so a dev device
    // is never mistaken for an install. Absent (null) is the built-in
    // production API; set but blank is passed on as it is, for the service
    // to refuse — a dev setup gone wrong must not quietly mean production.
    // Not a build failure: this block is configured for release tasks too.
    // Release builds always use the built-in API.
    val sporaApi: String? = providers.gradleProperty("sporaApi").orNull ?: System.getenv("SPORA_API")

    buildTypes {
        debug {
            versionNameSuffix = "-debug"
            buildConfigField(
                "String",
                "SPORA_API",
                sporaApi?.let { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" } ?: "null",
            )
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release")
            buildConfigField("String", "SPORA_API", "null")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    ndkVersion = "29.0.14206865"
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "spora-${versionName}.apk"
        }
    }
}

val buildNativeRelease = tasks.register<BuildNativeLibs>("buildNativeRelease") {
    script.set(rootProject.layout.projectDirectory.file("scripts/build-native.sh"))
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    doNotTrackState("cargo decides what to rebuild")
}

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildNativeRelease, BuildNativeLibs::outputDir)
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
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.security.crypto)
    implementation(libs.jna) {
        artifact {
            type = "aar"
        }
    }
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}