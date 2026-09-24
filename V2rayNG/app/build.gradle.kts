import java.util.zip.ZipFile
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.zongyangbigpolo.aigateway"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        val invitationService = providers.gradleProperty("INVITATION_SERVICE_URL").orElse("https://47.103.58.159").get()
        require(invitationService.none { it == '\n' || it == '\r' }) { "Invalid invitation service URL" }
        buildConfigField("String", "INVITATION_SERVICE_URL",
            "\"" + invitationService.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        multiDexEnabled = true

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (!abiFilterList.isNullOrEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
            assets.srcDir(rootProject.file("../.native-build/geodata"))
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        variant.outputs
            .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
            .forEach { output ->
                val abi = output.getFilter("ABI") ?: "universal"
                output.outputFileName =
                    "AiGateway_${variant.versionName}_${variant.flavorName}_${variant.buildType.name}_${abi}.apk"
            }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

val verifyBundledGeodata by tasks.registering {
    group = "verification"
    description = "Verify pinned geodata and license notices before packaging assets."
    doLast {
        val pins = Properties().apply {
            rootProject.file("../scripts/geodata-dependencies.env").inputStream().use { load(it) }
        }
        val directory = rootProject.file("../.native-build/geodata")
        val hashes = mapOf(
            "geosite.dat" to "GEOSITE_SHA256",
            "geoip.dat" to "GEOIP_SHA256",
            "geoip-only-cn-private.dat" to "GEOIP_CN_PRIVATE_SHA256",
            "GEODATA-LICENSE-GPL-3.0" to "GPL_LICENSE_SHA256",
            "GEODATA-LICENSE-CC-BY-SA-4.0" to "CC_LICENSE_SHA256"
        )
        hashes.forEach { (name, key) ->
            val asset = directory.resolve(name)
            check(asset.isFile) {
                "Missing bundled $name. Run bash scripts/prepare-geodata.sh from the repository root."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            asset.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == pins.getProperty(key)) {
                "Bundled $name checksum mismatch. Run bash scripts/prepare-geodata.sh."
            }
        }
        val notice = directory.resolve("GEODATA-NOTICE")
        check(notice.isFile && notice.readText() == rootProject.file("../GEODATA-NOTICE").readText()) {
            "Missing or stale GEODATA-NOTICE. Run bash scripts/prepare-geodata.sh."
        }
    }
}

val verifyNativeLibraries by tasks.registering {
    group = "verification"
    description = "Reject APKs missing the real Xray and hev native dependencies."
    doLast {
        val abis = (project.findProperty("ABI_FILTERS") as? String)?.split(';')
            ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        val aar = file("libs/libv2ray.aar")
        check(aar.isFile) { "Run bash scripts/prepare-native.sh from the repository root first." }
        ZipFile(aar).use { zip ->
            abis.forEach { abi ->
                check(zip.getEntry("jni/$abi/libgojni.so") != null) {
                    "Missing Xray JNI library for $abi. Run bash scripts/prepare-native.sh."
                }
                listOf("libhev-socks5-tunnel.so", "libhevsockstun.so").forEach { library ->
                    check(file("libs/$abi/$library").isFile) {
                        "Missing $abi/$library. Run bash scripts/prepare-native.sh."
                    }
                }
            }
        }
    }
}

tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(verifyBundledGeodata)
    }
    if (name.startsWith("merge") && name.endsWith("NativeLibs")) {
        dependsOn(verifyNativeLibraries)
    }
}

dependencies {
    // Core Libraries
    implementation(files("libs/libv2ray.aar"))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment)

    // UI Libraries
    implementation(libs.material)
    implementation(libs.toasty)
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
