plugins {
    id("chronyx.android.library")
    id("maven-publish")
}

android {
    namespace = "com.chronyx.core"

    defaultConfig {
        // Surface the achievable raw-GNSS envelope to consumers at build time.
        buildConfigField("boolean", "RAW_GNSS_DEFAULT_ON", "true")
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(libs.androidx.core.ktx)
    api(libs.androidx.lifecycle.runtime.ktx)

    // FusedLocationProviderClient for cooked fixes. Raw GNSS uses the framework LocationManager.
    implementation(libs.play.services.location)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.chronyx"
            artifactId = "chronyx-core"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
