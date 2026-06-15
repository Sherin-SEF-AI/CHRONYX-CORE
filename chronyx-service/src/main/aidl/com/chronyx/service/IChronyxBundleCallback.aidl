package com.chronyx.service;

import com.chronyx.service.BundleMetadata;
import android.hardware.HardwareBuffer;

/**
 * Delivered to bound clients. Frames cross the process boundary as a HardwareBuffer HANDLE plus
 * lightweight metadata — never as serialized YUV over Binder. `oneway` so a slow client can never
 * block the capture process.
 */
oneway interface IChronyxBundleCallback {
    void onBundleMetadata(in BundleMetadata meta, in HardwareBuffer buffer);
    void onDiagnostics(String diagnosticsLine);
}
