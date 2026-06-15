package com.chronyx.core.api

/** The single normalization axis for the session. BOOTTIME is the only correctness-preserving choice. */
enum class ClockBase {
    /** `SystemClock.elapsedRealtimeNanos()` — monotonic across deep sleep. The only supported value. */
    BOOTTIME,
}

/** Video codec for the storage encoder. */
enum class Codec { HEVC, AVC }

/** How the [com.chronyx.core.sync.SyncEngine] chooses the bundle reference timestamp `t_ref`. */
enum class Bundling {
    /** One bundle per delivered camera frame, `t_ref = frame.t_mid`. Default; right for VIO heads. */
    PerFrame,

    /** One bundle per IMU sample, for inertial-led heads with no camera. */
    PerImuSample,

    /** Fixed-rate bundling (see [com.chronyx.core.api.ChronyxConfig.bundleRateHz]). */
    FixedRate,
}

/** Thermal management aggressiveness. */
enum class ThermalPolicy {
    /** Never alter capture parameters; just report thermal status. Risk of hard throttle/shutdown. */
    NONE,

    /** Step down resolution → fps → bitrate as thermal status rises, logging every transition. */
    ADAPTIVE,
}

/**
 * Microphone source preset. `UNPROCESSED` avoids AGC/NS/echo-cancel so acoustic events survive
 * intact; falls back to `VOICE_RECOGNITION` when the device reports no unprocessed path.
 */
enum class AudioSource { UNPROCESSED, VOICE_RECOGNITION }

/**
 * Camera focus control. For VIO/mapping a fixed focus keeps intrinsics stable across the session;
 * `AUTO` is for general use. `FIXED_INFINITY`/`FIXED` disable continuous AF and lock the lens.
 */
enum class FocusMode { AUTO, FIXED_INFINITY, FIXED }
