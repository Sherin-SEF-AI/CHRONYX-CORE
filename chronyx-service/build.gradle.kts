plugins {
    id("chronyx.android.library")
    id("kotlin-parcelize")
    id("maven-publish")
}

android {
    namespace = "com.chronyx.service"
    buildFeatures {
        aidl = true
    }
}

dependencies {
    api(project(":chronyx-core"))
    // The service constructs the default file sink, so it depends on the MCAP module too.
    api(project(":chronyx-mcap"))
    implementation(libs.androidx.lifecycle.service)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.chronyx"
            artifactId = "chronyx-service"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
