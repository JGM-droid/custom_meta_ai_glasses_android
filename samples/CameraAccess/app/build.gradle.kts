/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

val localProperties =
    Properties().apply {
      val localPropertiesFile = rootProject.file("local.properties")
      if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use(::load)
      }
    }

android {
  namespace = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
  compileSdk = 36

  buildFeatures { buildConfig = true }

  defaultConfig {
    applicationId = "com.meta.wearable.dat.externalsampleapps.cameraaccess"
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // Meta Wearables Device Access Toolkit Setup
    // Without Developer Mode, these values need to be set with credentials from the app registered
    // in Wearables Developer Center
    manifestPlaceholders["mwdat_application_id"] = ""
    manifestPlaceholders["mwdat_client_token"] = ""
    val investigationBackendBaseUrl =
      providers.gradleProperty("investigation_backend_base_url").orNull
        ?: localProperties.getProperty(
          "investigation_backend_base_url",
          "http://10.0.2.2:8001",
        )
    buildConfigField(
      "String",
      "INVESTIGATION_BACKEND_BASE_URL",
      "\"$investigationBackendBaseUrl\"",
    )
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  testOptions {
    unitTests {
      // Standard Android Gradle flag: unmocked android.* calls (e.g. Log.e in
      // ProjectContinuityHudController's failure paths, now exercised for real by the Stage 2
      // acceptance harness's FakeDisplay) return a harmless default instead of throwing. No
      // Robolectric/Mockito-inline added - this is a plain testOptions flag, not a new dependency.
      isReturnDefaultValues = true
    }
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
  signingConfigs {
    getByName("debug") {
      storeFile = file("sample.keystore")
      storePassword = "sample"
      keyAlias = "sample"
      keyPassword = "sample"
    }
  }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.exifinterface)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material.icons.extended)
  implementation(libs.androidx.material3)
  implementation(libs.kotlinx.collections.immutable)
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
  implementation(libs.mwdat.display)
  implementation(libs.mwdat.mockdevice)
  androidTestImplementation(libs.androidx.ui.test.junit4)
  androidTestImplementation(libs.androidx.test.uiautomator)
  androidTestImplementation(libs.androidx.test.rules)
  // Required by androidx.compose.ui.test.junit4.createComposeRule() (used in
  // ProjectWorkspaceScreenTest) to host content without a real app Activity - it merges in a
  // plain ComponentActivity for the test APK. Debug-only: never shipped in a release build.
  debugImplementation(libs.androidx.ui.test.manifest)
  testImplementation(libs.junit)
  testImplementation(libs.org.json)
}
