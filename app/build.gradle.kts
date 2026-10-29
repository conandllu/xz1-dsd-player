import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── release 签名 ─────────────────────────────────────────────────────────
// 仓库里不提交任何密钥：在项目根放一个 keystore.properties（已 .gitignore）即可：
//   storeFile=/绝对路径/xz1-dsd-player.jks
//   storePassword=…
//   keyAlias=xz1dsd
//   keyPassword=…
// 没有这个文件时 release 构建产出未签名 APK（CI/临时构建照常可用）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.dsd.player"
    compileSdk = 35
    ndkVersion = "27.3.13750724"

    defaultConfig {
        applicationId = "com.dsd.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_static"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // 两个分支并行产出：dsd = 现有本地播放器；companion = 与外部流媒体（官方 Apple Music 等）
    // 共存的版本（默认「让出 USB 独占」+ 本地 ALAC/m4a 支持）。
    flavorDimensions += "edition"
    productFlavors {
        create("dsd") {
            dimension = "edition"
            // applicationId 保持 com.dsd.player（与已安装版本一致，可直接覆盖升级）
        }
        create("companion") {
            dimension = "edition"
            applicationIdSuffix = ".companion"   // 可与原版同时安装
            versionNameSuffix = "-companion"
        }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // buildConfig：debug/release 分支判断（调试入口只在 debug 生效）与版本信息
    buildFeatures { viewBinding = true; buildConfig = true }
    packaging { jniLibs.useLegacyPackaging = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media:media:1.2.1")
    implementation("io.coil-kt:coil:2.6.0")            // 封面加载（带内存/磁盘缓存与降采样）
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
