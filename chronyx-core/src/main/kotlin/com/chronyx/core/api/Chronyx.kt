package com.chronyx.core.api

import android.content.Context
import com.chronyx.core.internal.CaptureSessionImpl
import timber.log.Timber

/**
 * Entry point to CHRONYX. A perception head builds a [ChronyxConfig] and calls [start]; it receives
 * time-aligned [com.chronyx.core.sync.SyncedBundle]s and never touches Camera2 or `SensorManager`.
 *
 * Two consumption models share the same underlying session object:
 *  - Embed the AAR and call [start] directly (caller owns the lifecycle), or
 *  - let `chronyx-service`'s foreground service own the lifecycle (it calls [start] internally).
 */
object Chronyx {
    /**
     * Validates [config] and starts capture. Throws [ChronyxConfigException] on an invalid config.
     * Unsupported-but-degradable requests (e.g. raw GNSS on a device without it) are warned and
     * degraded at runtime, not thrown.
     */
    @JvmStatic
    fun start(context: Context, config: ChronyxConfig): CaptureSession {
        if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
        return CaptureSessionImpl(context.applicationContext, config).also { it.start() }
    }
}
