/*
 * 码上取 PickupManager —— 完全离线的安卓快递取件助手
 * Copyright (C) 2026 shanqiugzs
 * SPDX-License-Identifier: GPL-3.0-or-later
 * 许可全文见仓库根目录 LICENSE。
 */

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.compose.compiler)
}

// 发布签名凭据一律来自环境变量，不落盘、不进仓库。
// 缺少这些变量时 release 变体产出未签名 APK，构建本身不失败。
val keystorePath = System.getenv("PICKUP_KEYSTORE_PATH")
val keystorePassword = System.getenv("PICKUP_KEYSTORE_PASSWORD")
val keystoreAlias = System.getenv("PICKUP_KEY_ALIAS")
val keystoreAliasPassword = System.getenv("PICKUP_KEY_PASSWORD")
val hasReleaseKeystore = !keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank()
        && !keystoreAlias.isNullOrBlank() && !keystoreAliasPassword.isNullOrBlank()

android {
    namespace = "com.shanqiu.pickupmanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.shanqiu.pickupmanager"
        minSdk = 24
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreAliasPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
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
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            isDebuggable = false
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
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
    }
}

ksp {
    // 提交 schema 快照，使 Room migration 可被自动化校验
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // 仅为 res/values/themes.xml 的 Theme.MaterialComponents 父主题所需
    implementation(libs.material)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.process)

    implementation(libs.activity.ktx)
    implementation(libs.workmanager.runtime.ktx)

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.compose.animation:animation")

    testImplementation(libs.junit)
}
