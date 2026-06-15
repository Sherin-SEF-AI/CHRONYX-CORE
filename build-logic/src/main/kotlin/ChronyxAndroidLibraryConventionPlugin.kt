import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.getByType

class ChronyxAndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        with(pluginManager) {
            apply("com.android.library")
            apply("org.jetbrains.kotlin.android")
        }

        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            defaultConfig {
                targetSdk = CHRONYX_TARGET_SDK
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                consumerProguardFiles("consumer-rules.pro")
            }
            buildTypes {
                release {
                    isMinifyEnabled = false
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
            }
            // Library AARs ship Java 17 bytecode; consumers must be on AGP 8+.
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }

        val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies {
            add("implementation", catalog.findLibrary("timber").get())
            add("implementation", catalog.findLibrary("coroutines-core").get())
            add("implementation", catalog.findLibrary("coroutines-android").get())
            add("testImplementation", catalog.findLibrary("junit").get())
            add("testImplementation", catalog.findLibrary("truth").get())
            add("testImplementation", catalog.findLibrary("robolectric").get())
            add("testImplementation", catalog.findLibrary("coroutines-test").get())
            add("testImplementation", catalog.findLibrary("mockk").get())
            add("androidTestImplementation", catalog.findLibrary("androidx-test-ext-junit").get())
            add("androidTestImplementation", catalog.findLibrary("androidx-test-runner").get())
            add("androidTestImplementation", catalog.findLibrary("androidx-test-core").get())
            add("androidTestImplementation", catalog.findLibrary("androidx-test-rules").get())
            add("androidTestImplementation", catalog.findLibrary("truth").get())
        }
    }
}
