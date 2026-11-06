import com.android.build.api.variant.FilterConfiguration
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The four ABIs the Rust core is built for; scripts/build-native.sh builds
// the same list.
val nativeAbis = listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")

// A build makes one APK per ABI (most of an APK is the core, and a device
// runs one ABI's) plus a universal APK. F-Droid builds each ABI's APK on its
// own, with -PsporaAbi=<abi>: only that ABI's core is built and packaged
// (that build's universal APK holds that ABI alone, and goes unused).
val sporaAbi: String? = providers.gradleProperty("sporaAbi").orNull
require(sporaAbi == null || sporaAbi in nativeAbis) { "sporaAbi must be one of $nativeAbis, not $sporaAbi" }
val apkAbis = sporaAbi?.let { listOf(it) } ?: nativeAbis

// An APK's versionCode is defaultConfig's × 10 + its ABI's code below (0
// for the universal APK): every APK of a release has its own code, as
// F-Droid's repo needs, and all of them are above the previous release's,
// so devices can move between the APKs of different releases and sources.
// A 64-bit ABI is above its 32-bit sibling, so a device that runs both
// moves to 64-bit. F-Droid's recipe computes the same codes
// (VercodeOperation). BuildConfig.VERSION_CODE is compiled into the dex that
// all of a build's APKs share, so it is the universal APK's (× 10 + 0) in
// every one of them; see isUniversalApk below.
val abiVersionCodes = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)

/**
 * Builds libspora_ffi.so for a release APK from the core/ submodule with
 * scripts/build-native.sh --release (pinned toolchain, reproducible flags,
 * bindings check), for the ABIs being packaged. It runs on every release
 * build, since cargo tracks its own inputs, and it is the only source of
 * native code in a release APK.
 */
abstract class BuildNativeLibs : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Internal abstract val script: RegularFileProperty
    @get:Internal abstract val ndkDirectory: DirectoryProperty
    @get:Internal abstract val abis: ListProperty<String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        execOperations.exec {
            commandLine(
                listOf(
                    script.get().asFile.path, "--release",
                    "--ndk", ndkDirectory.get().asFile.path,
                    "--out", outputDir.get().asFile.path,
                ) + abis.get().flatMap { listOf("--abi", it) }
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
        versionCode = 6
        versionName = "0.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // JNA's AAR also carries armeabi, mips and mips64 libraries (and
        // the other ABIs' in a -PsporaAbi build), which would only bloat the
        // universal APK: there is no core for them.
        ndk { abiFilters += apkAbis }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include(*apkAbis.toTypedArray())
            // For GitHub releases and the website: one file for every device.
            // A -PsporaAbi build makes one too: AGP takes BuildConfig's
            // VERSION_CODE from the universal APK when there is one, else
            // from the ABI's, and F-Droid's rebuild of an ABI's APK would then
            // carry × 10 + <ABI> in its dex where ours carries × 10 + 0.
            isUniversalApk = true
        }
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
    // spora-<version>.apk (universal), spora-<version>-<abi>.apk: the names
    // the F-Droid recipe downloads from the GitHub release
    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val abi = output.getFilter("ABI")
            output.outputFileName = "spora-${versionName}${abi?.let { "-$it" } ?: ""}.apk"
        }
    }
}

val buildNativeRelease = tasks.register<BuildNativeLibs>("buildNativeRelease") {
    script.set(rootProject.layout.projectDirectory.file("scripts/build-native.sh"))
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    abis.set(apkAbis)
    doNotTrackState("cargo decides what to rebuild")
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
            val base = output.versionCode.orNull ?: return@forEach
            output.versionCode.set(base * 10 + (abi?.let(abiVersionCodes::getValue) ?: 0))
        }
    }
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
