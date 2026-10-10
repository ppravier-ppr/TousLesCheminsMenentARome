import java.util.Properties
import java.io.FileInputStream
import java.io.FileOutputStream

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.ksp)
}

val versionPropsFile = file("../version.properties")
val versionProps = Properties()
if (versionPropsFile.exists()) {
    versionProps.load(FileInputStream(versionPropsFile))
}
val buildNumber = (versionProps.getProperty("buildNumber") ?: "0").toInt() + 1
versionProps.setProperty("buildNumber", buildNumber.toString())
versionProps.store(FileOutputStream(versionPropsFile), null)

android {
    namespace = "com.example.everystreettracker"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.example.everystreettracker"
        minSdk = 24
        targetSdk = 36
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        manifestPlaceholders["MAPS_API_KEY"] = "AIzaSyBM5iGyOep5IWfIv-T-0aK5ve5i-cAgA6U"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      jniLibs {
        useLegacyPackaging = false
      }
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
        excludes += "META-INF/INDEX.LIST"
        excludes += "META-INF/DEPENDENCIES"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.documentfile)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)

  // Google Maps & Location
  implementation(libs.maps.compose)
  implementation(libs.play.services.maps)
  implementation(libs.play.services.location)
  implementation(libs.play.auth)

  // Google Drive API
  implementation(libs.google.client.android)
  implementation(libs.google.client.gson)
  implementation(libs.google.http.android)
  implementation(libs.google.drive)
  implementation(libs.androidx.credentials)
  implementation(libs.androidx.credentials.auth)

  // Room Database
  implementation(libs.room.runtime)
  implementation(libs.room.ktx)
  ksp(libs.room.compiler)

  // WorkManager
  implementation(libs.work.runtime.ktx)

  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}

base {
    archivesName.set("TousLesCheminsMenentARome")
}

