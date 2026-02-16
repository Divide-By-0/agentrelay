package com.agentrelay

import org.junit.Assert.*
import org.junit.Test

class ScreenCaptureQualityTest {

    // ── First-time default ───────────────────────────────────────────────────

    @Test
    fun `zero speed returns 60 (first-time default)`() {
        assertEquals(60, ScreenCaptureService.computeAutoQuality(0f))
    }

    // ── Speed tiers from fastest to slowest ──────────────────────────────────

    @Test
    fun `very fast speed above 1000 returns 95`() {
        assertEquals(95, ScreenCaptureService.computeAutoQuality(1500f))
        assertEquals(95, ScreenCaptureService.computeAutoQuality(1001f))
    }

    @Test
    fun `fast speed 801-1000 returns 90`() {
        assertEquals(90, ScreenCaptureService.computeAutoQuality(1000f))
        assertEquals(90, ScreenCaptureService.computeAutoQuality(801f))
    }

    @Test
    fun `good speed 601-800 returns 85`() {
        assertEquals(85, ScreenCaptureService.computeAutoQuality(800f))
        assertEquals(85, ScreenCaptureService.computeAutoQuality(601f))
    }

    @Test
    fun `above average speed 401-600 returns 80`() {
        assertEquals(80, ScreenCaptureService.computeAutoQuality(600f))
        assertEquals(80, ScreenCaptureService.computeAutoQuality(401f))
    }

    @Test
    fun `average speed 301-400 returns 75`() {
        assertEquals(75, ScreenCaptureService.computeAutoQuality(400f))
        assertEquals(75, ScreenCaptureService.computeAutoQuality(301f))
    }

    @Test
    fun `below average speed 201-300 returns 65`() {
        assertEquals(65, ScreenCaptureService.computeAutoQuality(300f))
        assertEquals(65, ScreenCaptureService.computeAutoQuality(201f))
    }

    @Test
    fun `slow speed 151-200 returns 55`() {
        assertEquals(55, ScreenCaptureService.computeAutoQuality(200f))
        assertEquals(55, ScreenCaptureService.computeAutoQuality(151f))
    }

    @Test
    fun `very slow speed 101-150 returns 45`() {
        assertEquals(45, ScreenCaptureService.computeAutoQuality(150f))
        assertEquals(45, ScreenCaptureService.computeAutoQuality(101f))
    }

    @Test
    fun `extremely slow speed 51-100 returns 35`() {
        assertEquals(35, ScreenCaptureService.computeAutoQuality(100f))
        assertEquals(35, ScreenCaptureService.computeAutoQuality(51f))
    }

    @Test
    fun `minimal quality for very poor speed below 50 returns 25`() {
        assertEquals(25, ScreenCaptureService.computeAutoQuality(50f))
        assertEquals(25, ScreenCaptureService.computeAutoQuality(10f))
        assertEquals(25, ScreenCaptureService.computeAutoQuality(1f))
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
        assertEquals(25, ScreenCaptureService.computeAutoQuality(-1f))
    }
}
