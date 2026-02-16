package com.agentrelay

import android.graphics.Rect
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ElementMapGeneratorIoUTest {

    private lateinit var generator: ElementMapGenerator

    @Before
    fun setup() {
        generator = ElementMapGenerator(screenWidth = 1080, screenHeight = 2400)
    }

    // ── IoU computation ──────────────────────────────────────────────────────

    @Test
    fun `identical rects return IoU of 1`() {
        val rect = Rect(100, 100, 300, 300)
        assertEquals(1f, generator.computeIoU(rect, rect), 0.001f)
    }

    @Test
    fun `no overlap returns IoU of 0`() {
        val a = Rect(0, 0, 100, 100)
        val b = Rect(200, 200, 300, 300)
        assertEquals(0f, generator.computeIoU(a, b), 0.001f)
    }

    @Test
    fun `touching edges return IoU of 0`() {
        val a = Rect(0, 0, 100, 100)
        val b = Rect(100, 0, 200, 100)
        assertEquals(0f, generator.computeIoU(a, b), 0.001f)
    }

    @Test
    fun `50 percent overlap returns expected IoU`() {
        // a = 100x100, b = 100x100, overlap = 50x100
        val a = Rect(0, 0, 100, 100)
        val b = Rect(50, 0, 150, 100)
        // intersection = 50*100 = 5000
        // union = 10000 + 10000 - 5000 = 15000
        // IoU = 5000/15000 = 0.333
        assertEquals(0.333f, generator.computeIoU(a, b), 0.01f)
    }

    @Test
    fun `one rect contained in another`() {
        val outer = Rect(0, 0, 200, 200)
        val inner = Rect(50, 50, 150, 150)
        // intersection = 100*100 = 10000
        // union = 40000 + 10000 - 10000 = 40000
        // IoU = 10000/40000 = 0.25
        assertEquals(0.25f, generator.computeIoU(outer, inner), 0.001f)
    }

    @Test
    fun `IoU is symmetric`() {
        val a = Rect(0, 0, 100, 100)
        val b = Rect(25, 25, 125, 125)
        assertEquals(generator.computeIoU(a, b), generator.computeIoU(b, a), 0.001f)
    }

    @Test
    fun `zero-area rect returns IoU of 0`() {
        val a = Rect(0, 0, 0, 100)  // zero width
        val b = Rect(0, 0, 100, 100)
        assertEquals(0f, generator.computeIoU(a, b), 0.001f)
    }

    @Test
    fun `large rects with small overlap`() {
        val a = Rect(0, 0, 1000, 1000)
        val b = Rect(990, 990, 2000, 2000)
        // intersection = 10*10 = 100
        // union = 1000000 + 1010000 - 100 = 2009900
        val iou = generator.computeIoU(a, b)
        assertTrue("IoU should be very small", iou < 0.001f)
    }

    // ── textToSlug ───────────────────────────────────────────────────────────

    @Test
    fun `simple text to slug`() {
        assertEquals("submit", generator.textToSlug("Submit"))
    }

    @Test
    fun `multi-word text to slug`() {
        assertEquals("search_results", generator.textToSlug("Search results"))
    }

    @Test
    fun `text with special characters`() {
        assertEquals("12_45_pm", generator.textToSlug("12:45 PM"))
    }

    @Test
    fun `blank text returns empty slug`() {
        assertEquals("", generator.textToSlug(""))
        assertEquals("", generator.textToSlug("   "))
    }

    @Test
    fun `slug truncated to max length`() {
        val longText = "This is a very long button label that should be truncated"
        val slug = generator.textToSlug(longText)
        assertTrue("Slug should be <= 24 chars, was ${slug.length}", slug.length <= 24)
    }

    @Test
    fun `consecutive special chars collapsed to single underscore`() {
        assertEquals("hello_world", generator.textToSlug("hello...world"))
    }

    @Test
    fun `leading and trailing special chars trimmed`() {
        assertEquals("hello", generator.textToSlug("...hello..."))
    }

    @Test
    fun `all caps lowercased`() {
        assertEquals("ok", generator.textToSlug("OK"))
    }

    @Test
    fun `slug does not end with underscore after truncation`() {
        // Create text that when slugged and truncated would end with _
        val text = "a".repeat(23) + " b"  // slug = "aaa...aaa_b", truncated might end with _
        val slug = generator.textToSlug(text)
        assertFalse("Slug should not end with underscore: $slug", slug.endsWith("_"))
    }

    @Test
    fun `emoji and unicode stripped`() {
        val slug = generator.textToSlug("Hello 🌍 World")
        assertEquals("hello_world", slug)
    }
}
