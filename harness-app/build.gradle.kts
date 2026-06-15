plugins {
    id("chronyx.android.application")
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.chronyx.harness"

    defaultConfig {
        applicationId = "com.chronyx.harness"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(project(":chronyx-core"))
    implementation(project(":chronyx-mcap"))
    implementation(project(":chronyx-service"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
