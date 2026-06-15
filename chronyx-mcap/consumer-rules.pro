# Protobuf-lite generated messages use reflection-free codegen but must not be renamed away.
-keep class com.chronyx.mcap.proto.** { *; }
-keep class foxglove.** { *; }
-keep class com.google.protobuf.** { *; }
-keep public class com.chronyx.mcap.McapSink { public *; }
-keep public class com.chronyx.mcap.McapWriter { *; }
-keep public class com.chronyx.mcap.internal.McapCompression { *; }
