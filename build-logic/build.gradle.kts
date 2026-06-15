plugins {
    `kotlin-dsl`
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("androidLibrary") {
            id = "chronyx.android.library"
            implementationClass = "ChronyxAndroidLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "chronyx.android.application"
            implementationClass = "ChronyxAndroidApplicationConventionPlugin"
        }
        register("kotlinJvm") {
            id = "chronyx.kotlin.jvm"
            implementationClass = "ChronyxKotlinJvmConventionPlugin"
        }
    }
}
