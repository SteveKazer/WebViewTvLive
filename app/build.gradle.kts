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
        versionCode = 23
        versionName = "2.3.0-yangshipin"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 复用自动生成的 debug 签名, 保证 CI 产出的 APK 可直接安装
            signingConfig = signingConfigs.getByName("debug")
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
    implementation("com.tencent.tbs:tbssdk:44286")
    implementation("org.apache.commons:commons-lang3:3.0")
}
