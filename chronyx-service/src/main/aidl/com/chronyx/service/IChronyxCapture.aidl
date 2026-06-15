package com.chronyx.service;

import com.chronyx.service.ChronyxServiceConfig;
import com.chronyx.service.IChronyxBundleCallback;

/**
 * The bound-service promotion path: one capture process, hot-swappable thin clients ("one rig, many
 * consumers"). Configuration and control cross Binder; frame pixels never do (see
 * IChronyxBundleCallback).
 */
interface IChronyxCapture {
    void startCapture(in ChronyxServiceConfig config);
    void stopCapture();
    boolean isRecording();
    void registerCallback(IChronyxBundleCallback cb);
    void unregisterCallback(IChronyxBundleCallback cb);
}
