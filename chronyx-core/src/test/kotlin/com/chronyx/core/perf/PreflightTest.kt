package com.chronyx.core.perf

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PreflightTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun goWhenAllHealthy() {
        val s = Preflight.decide(batteryPercent = 80, freeBytes = 10 * gb, thermalStatus = 0, permissionsGranted = true)
        assertThat(s.go).isTrue()
        assertThat(s.reasons).isEmpty()
    }

    @Test
    fun blocksOnLowBattery() {
        val s = Preflight.decide(batteryPercent = 5, freeBytes = 10 * gb, thermalStatus = 0, permissionsGranted = true)
        assertThat(s.go).isFalse()
        assertThat(s.reasons.any { it.contains("Battery") }).isTrue()
    }

    @Test
    fun blocksOnLowStorageAndThermalAndPermissions() {
        val s = Preflight.decide(batteryPercent = 90, freeBytes = 100L * 1024 * 1024, thermalStatus = 4, permissionsGranted = false)
        assertThat(s.go).isFalse()
        assertThat(s.storageOk).isFalse()
        assertThat(s.thermalOk).isFalse()
        assertThat(s.permissionsOk).isFalse()
        assertThat(s.reasons).hasSize(3)
    }

    @Test
    fun unknownBatteryDoesNotBlock() {
        val s = Preflight.decide(batteryPercent = -1, freeBytes = 10 * gb, thermalStatus = 0, permissionsGranted = true)
        assertThat(s.batteryOk).isTrue()
        assertThat(s.go).isTrue()
    }
}
