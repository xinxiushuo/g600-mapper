plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.g600.mapper"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.g600.mapper"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // 固定签名: CI 每次构建自动生成的 debug keystore 都不同, 会导致
    // "签名不一致, 覆盖安装失败"。这里用仓库里固定的 p12, 保证以后每个版本
    // 都能直接覆盖升级, 不需要先卸载。
    signingConfigs {
        create("stable") {
            storeFile = file("../../keystore/g600.p12")
            storePassword = "g600mapper"
            keyAlias = "g600key"
            keyPassword = "g600mapper"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
