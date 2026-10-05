plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cc.eu.jeanwenzel.Noctreed"
    compileSdk = 34

    defaultConfig {
        applicationId = "cc.eu.jeanwenzel.Noctreed"
        minSdk = 31
        targetSdk = 34
        versionCode = 12
        versionName = "1.7.2"
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("REEDKIT_KEYSTORE")?.let { file(it) }
            if (ksFile != null && ksFile.exists() && ksFile.length() > 0) {
                storeFile = ksFile
                storePassword = System.getenv("REEDKIT_STORE_PASSWORD")
                keyAlias = System.getenv("REEDKIT_KEY_ALIAS")
                keyPassword = System.getenv("REEDKIT_KEY_PASSWORD")
            } else {
                logger.warn("Noctreed: release keystore not found at ${'$'}ksFile - release APK will be unsigned")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning?.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }
    // 按 CPU 架构拆分 APK，减小单个包体积
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }
    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.2")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // Shizuku：用于以 shell 身份授予 WRITE_SECURE_SETTINGS，实现永久开启无障碍
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    debugImplementation("androidx.compose.ui:ui-tooling")
}