package com.agentrelay

import android.graphics.Rect
import com.agentrelay.models.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ElementMapGeneratorTest {

    private lateinit var generator: ElementMapGenerator

    @Before
    fun setup() {
        generator = ElementMapGenerator(screenWidth = 1080, screenHeight = 2400)
    }

    // ── Accessibility-only elements ──────────────────────────────────────────

    @Test
    fun `generate with a11y elements creates semantic IDs`() {
        val elements = listOf(
            makeElement("old_1", ElementType.BUTTON, "OK", Rect(100, 100, 200, 150)),
            makeElement("old_2", ElementType.BUTTON, "Cancel", Rect(300, 100, 400, 150))
        )

        val map = generator.generate(elements)

        assertEquals(2, map.elements.size)
        assertEquals("btn_ok", map.elements[0].id)
        assertEquals("btn_cancel", map.elements[1].id)
        assertEquals("OK", map.elements[0].text)
        assertEquals("Cancel", map.elements[1].text)
    }

    @Test
    fun `generate assigns correct type prefixes`() {
        val elements = listOf(
            makeElement("x", ElementType.BUTTON, "Submit", Rect(0, 0, 100, 50)),
            makeElement("x", ElementType.INPUT, "Field", Rect(0, 100, 100, 150)),
            makeElement("x", ElementType.TEXT, "Label", Rect(0, 200, 100, 250)),
            makeElement("x", ElementType.SWITCH, "Toggle", Rect(0, 300, 100, 350))
        )

        val map = generator.generate(elements)

        assertEquals("btn_submit", map.elements[0].id)
        assertEquals("input_field", map.elements[1].id)
        assertEquals("text_label", map.elements[2].id)
        assertEquals("switch_toggle", map.elements[3].id)
    }

    @Test
    fun `generate deduplicates same-text same-type elements`() {
        val elements = listOf(
            makeElement("x", ElementType.BUTTON, "OK", Rect(100, 100, 200, 150)),
            makeElement("x", ElementType.BUTTON, "OK", Rect(300, 100, 400, 150))
        )

        val map = generator.generate(elements)

        assertEquals("btn_ok", map.elements[0].id)
        assertEquals("btn_ok_2", map.elements[1].id)
    }

    @Test
    fun `generate handles blank text elements`() {
        val elements = listOf(
            makeElement("x", ElementType.BUTTON, "", Rect(0, 0, 100, 50)),
            makeElement("x", ElementType.BUTTON, "", Rect(0, 100, 100, 150))
        )

        val map = generator.generate(elements)

        assertEquals("btn", map.elements[0].id)
        assertEquals("btn_2", map.elements[1].id)
    }

    // ── A11y + OCR merge ─────────────────────────────────────────────────────

    @Test
    fun `overlapping OCR enriches blank a11y text`() {
        val a11y = listOf(
            makeElement("a", ElementType.BUTTON, "", Rect(100, 100, 300, 200))
        )
        val ocr = listOf(
            makeElement("o", ElementType.TEXT, "Submit", Rect(110, 110, 290, 190), source = ElementSource.OCR)
        )

        val map = generator.generate(a11y, ocr)

        assertEquals(1, map.elements.size)
        assertEquals("Submit", map.elements[0].text)
        assertEquals(ElementSource.MERGED, map.elements[0].source)
    }

    @Test
    fun `non-overlapping OCR added as standalone`() {
        val a11y = listOf(
            makeElement("a", ElementType.BUTTON, "OK", Rect(100, 100, 200, 150))
        )
        val ocr = listOf(
            makeElement("o", ElementType.TEXT, "Footer", Rect(100, 2300, 300, 2350), source = ElementSource.OCR)
        )

        val map = generator.generate(a11y, ocr)

        assertEquals(2, map.elements.size)
        assertEquals("OK", map.elements[0].text)
        assertEquals("Footer", map.elements[1].text)
        assertEquals(ElementSource.OCR, map.elements[1].source)
    }

    @Test
    fun `overlapping OCR does not replace existing a11y text`() {
        val a11y = listOf(
            makeElement("a", ElementType.BUTTON, "Existing", Rect(100, 100, 300, 200))
        )
        val ocr = listOf(
            makeElement("o", ElementType.TEXT, "OCR Text", Rect(110, 110, 290, 190), source = ElementSource.OCR)
        )

        val map = generator.generate(a11y, ocr)

        assertEquals(1, map.elements.size)
        assertEquals("Existing", map.elements[0].text)
    }

    // ── Relative positions ───────────────────────────────────────────────────

    @Test
    fun `top-left position`() {
        val elements = listOf(
            makeElement("a", ElementType.BUTTON, "X", Rect(10, 10, 50, 50))
        )
        val map = generator.generate(elements)
        assertEquals("top-left", map.elements[0].relativePosition)
    }

    @Test
    fun `bottom-right position`() {
        val elements = listOf(
            makeElement("a", ElementType.BUTTON, "X", Rect(900, 2300, 1050, 2380))
        )
        val map = generator.generate(elements)
        assertEquals("bottom-right", map.elements[0].relativePosition)
    }

    @Test
    fun `middle-center position`() {
        val elements = listOf(
            makeElement("a", ElementType.BUTTON, "X", Rect(450, 1100, 650, 1300))
        )
        val map = generator.generate(elements)
        assertEquals("middle-center", map.elements[0].relativePosition)
    }

    @Test
    fun `upper-center position`() {
        val elements = listOf(
            makeElement("a", ElementType.BUTTON, "X", Rect(450, 500, 650, 700))
        )
        val map = generator.generate(elements)
        assertEquals("upper-center", map.elements[0].relativePosition)
    }

    @Test
    fun `lower-left position`() {
        val elements = listOf(
            makeElement("a", ElementType.BUTTON, "X", Rect(10, 1600, 200, 1800))
        )
        val map = generator.generate(elements)
        assertEquals("lower-left", map.elements[0].relativePosition)
    }

    // ── Empty inputs ─────────────────────────────────────────────────────────

    @Test
    fun `empty inputs returns empty element map`() {
        val map = generator.generate(emptyList(), emptyList())

        assertEquals(0, map.elements.size)
        assertEquals(1080, map.screenWidth)
        assertEquals(2400, map.screenHeight)
    }

    // ── Screen dimensions ────────────────────────────────────────────────────

    @Test
    fun `element map preserves screen dimensions`() {
        val map = generator.generate(emptyList())
        assertEquals(1080, map.screenWidth)
        assertEquals(2400, map.screenHeight)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeElement(
        id: String,
        type: ElementType,
        text: String,
        bounds: Rect,
        isClickable: Boolean = false,
        source: ElementSource = ElementSource.ACCESSIBILITY_TREE
    ) = UIElement(
        id = id,
        type = type,
        text = text,
        bounds = bounds,
        isClickable = isClickable,
        source = source
    )
}
