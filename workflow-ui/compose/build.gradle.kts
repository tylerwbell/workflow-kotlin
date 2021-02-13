import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("com.android.library")
  kotlin("android")
  id("org.jetbrains.dokka")
}

java {
  sourceCompatibility = JavaVersion.VERSION_1_8
  targetCompatibility = JavaVersion.VERSION_1_8
}

apply(from = rootProject.file(".buildscript/configure-android-defaults.gradle"))
apply(from = rootProject.file(".buildscript/android-ui-tests.gradle"))

android {
  // See https://github.com/Kotlin/kotlinx.coroutines/issues/1064#issuecomment-479412940
  packagingOptions.exclude("**/*.kotlin_*")

  buildFeatures.compose = true
  composeOptions {
    kotlinCompilerVersion = "1.4.30"
    kotlinCompilerExtensionVersion = "1.0.0-alpha12"
  }
}

tasks.withType<KotlinCompile> {
  kotlinOptions {
    useIR = true

    @Suppress("SuspiciousCollectionReassignment")
    freeCompilerArgs += listOf(
      "-Xopt-in=kotlin.RequiresOptIn"
    )
  }
}

dependencies {
  api(project(":workflow-core"))
  api(project(":workflow-ui:backstack-android"))
  api(project(":workflow-ui:core-android"))
  api(project(":workflow-ui:modal-android"))

  api(Dependencies.Kotlin.Stdlib.jdk8)

  androidTestImplementation(project(":workflow-runtime"))
  androidTestImplementation(Dependencies.AndroidX.activity)
  androidTestImplementation(Dependencies.AndroidX.Compose.foundation)
  androidTestImplementation(Dependencies.AndroidX.Compose.ui)
  androidTestImplementation(Dependencies.Test.AndroidX.core)
  androidTestImplementation(Dependencies.Test.AndroidX.truthExt)
  androidTestImplementation(Dependencies.Test.AndroidX.compose)
}
