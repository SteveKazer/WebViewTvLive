plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vasthread.webviewtv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stevekazer.yangshiplive"
        minSdk = 21
        targetSdk = 34
        versionCode = 32
        versionName = "2.4.5-yangshipin"
    }

    signingConfigs {
        // 固定发布签名: 保证 CI 产物签名一致, 支持"覆盖安装"升级
        create("release") {
            storeFile = file("keystore/release.keystore")
            storePassword = "YspLive2026kq7X"
            keyAlias = "yangshipin"
            keyPassword = "YspLive2026kq7X"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 固定签名(覆盖安装), 兼容此前已安装的 debug 签名版本不存在——首次安装新签名需卸载旧版
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        // 自动更新需要读取 BuildConfig.VERSION_NAME
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("org.apache.commons:commons-lang3:3.0")
}
