import com.google.protobuf.gradle.id

plugins {
    id("chronyx.android.library")
    id("maven-publish")
    alias(libs.plugins.protobuf)
}

android {
    namespace = "com.chronyx.mcap"

    // The protobuf plugin emits a FileDescriptorSet (with imports) that the writer embeds into MCAP
    // Schema records. We route it into a generated resources dir so it is packaged into the AAR and
    // loadable at runtime via the classloader.
    sourceSets {
        getByName("main") {
            resources.srcDir(layout.buildDirectory.dir("generated/descriptors"))
        }
    }
}

dependencies {
    api(project(":chronyx-core"))

    implementation(libs.protobuf.javalite)
    implementation(libs.protobuf.kotlin.lite)

    // Compression backends for chunked MCAP. aircompressor is pure-Java zstd (works on Android, the
    // default); zstd-jni/lz4-java are kept for JVM/desktop consumers of the writer.
    implementation(libs.aircompressor)
    implementation(libs.zstd.jni)
    implementation(libs.lz4)

    // OPTIONAL OFFICIAL BACKEND (documented swap-in):
    // The official Foxglove MCAP Java writer can implement McapWriter instead of InternalMcapWriter.
    // It is declared here behind the air-gap flag; the active backend is InternalMcapWriter, which is
    // fully MCAP-spec-conformant and Foxglove-readable. To adopt the official writer, uncomment and
    // implement a FoxgloveMcapWriter against its API.
    // implementation(libs.foxglove.mcap)
}

protobuf {
    protoc { artifact = libs.protobuf.protoc.get().toString() }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                id("java") { option("lite") }
            }
            task.generateDescriptorSet = true
            task.descriptorSetOptions.includeImports = true
            task.descriptorSetOptions.path =
                layout.buildDirectory.file("generated/descriptors/chronyx_schemas.desc").get().asFile.path
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.chronyx"
            artifactId = "chronyx-mcap"
            version = "0.1.0"
            afterEvaluate { from(components["release"]) }
        }
    }
}
