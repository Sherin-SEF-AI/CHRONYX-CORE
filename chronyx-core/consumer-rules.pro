# Keep the entire public API surface so consumers (perception heads) can link against it
# after R8 runs on their app.
-keep public class com.chronyx.core.api.** { public *; }
-keep public class com.chronyx.core.model.** { public *; }
-keep public class com.chronyx.core.clock.ClockDomain { *; }
-keep public class com.chronyx.core.clock.ClockReading { *; }
-keep public interface com.chronyx.core.api.Sink { *; }
-keepclassmembers enum com.chronyx.core.** { *; }
