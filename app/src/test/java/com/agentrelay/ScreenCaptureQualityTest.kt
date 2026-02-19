package com.agentrelay

import org.junit.Assert.*
import org.junit.Test

class ScreenCaptureQualityTest {

    // ── First-time default ───────────────────────────────────────────────────

    @Test
    fun `zero speed returns 30 (first-time default)`() {
        assertEquals(30, ScreenCaptureService.computeAutoQuality(0f))
    }

    // ── Speed tiers from fastest to slowest ──────────────────────────────────

    @Test
    fun `very fast speed above 1000 returns 50`() {
        assertEquals(50, ScreenCaptureService.computeAutoQuality(1500f))
        assertEquals(50, ScreenCaptureService.computeAutoQuality(1001f))
    }

    @Test
    fun `fast speed 601-1000 returns 40`() {
        assertEquals(40, ScreenCaptureService.computeAutoQuality(1000f))
        assertEquals(40, ScreenCaptureService.computeAutoQuality(601f))
    }

    @Test
    fun `average speed 301-600 returns 30`() {
        assertEquals(30, ScreenCaptureService.computeAutoQuality(600f))
        assertEquals(30, ScreenCaptureService.computeAutoQuality(301f))
    }

    @Test
    fun `slow speed 151-300 returns 25`() {
        assertEquals(25, ScreenCaptureService.computeAutoQuality(300f))
        assertEquals(25, ScreenCaptureService.computeAutoQuality(151f))
    }

    @Test
    fun `very slow speed 51-150 returns 20`() {
        assertEquals(20, ScreenCaptureService.computeAutoQuality(150f))
        assertEquals(20, ScreenCaptureService.computeAutoQuality(51f))
    }

    @Test
    fun `minimal quality for very poor speed below or equal 50 returns 15`() {
        assertEquals(15, ScreenCaptureService.computeAutoQuality(50f))
        assertEquals(15, ScreenCaptureService.computeAutoQuality(10f))
        assertEquals(15, ScreenCaptureService.computeAutoQuality(1f))
    }

    // ── Boundary precision ───────────────────────────────────────────────────

    @Test
    fun `quality is monotonically non-decreasing with speed`() {
        val speeds = listOf(1f, 50f, 51f, 100f, 101f, 150f, 151f, 200f, 201f, 300f, 301f, 400f, 401f, 600f, 601f, 800f, 801f, 1000f, 1001f)
        val qualities = speeds.map { ScreenCaptureService.computeAutoQuality(it) }

        for (i in 1 until qualities.size) {
            assertTrue(
                "Quality at ${speeds[i]}KB/s (${qualities[i]}) should be >= quality at ${speeds[i-1]}KB/s (${qualities[i-1]})",
                qualities[i] >= qualities[i - 1]
            )
        }
    }

    @Test
    fun `all quality values are in valid JPEG range 1-100`() {
        val testSpeeds = listOf(0f, 1f, 25f, 50f, 75f, 100f, 150f, 200f, 300f, 400f, 600f, 800f, 1000f, 2000f)
        for (speed in testSpeeds) {
            val q = ScreenCaptureService.computeAutoQuality(speed)
            assertTrue("Quality $q for speed $speed should be >= 1", q >= 1)
            assertTrue("Quality $q for speed $speed should be <= 100", q <= 100)
        }
    }

    // ── Negative speed edge case ─────────────────────────────────────────────

    @Test
    fun `negative speed treated as very poor connection`() {
        assertEquals(15, ScreenCaptureService.computeAutoQuality(-1f))
    }
}
