package com.agentrelay

import android.graphics.Rect
import com.agentrelay.models.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ElementMapModelTest {

    // ── findById ─────────────────────────────────────────────────────────────

    @Test
    fun `findById returns matching element`() {
        val map = makeMap(
            makeElement("btn_1", ElementType.BUTTON, "OK"),
            makeElement("input_1", ElementType.INPUT, "Search")
        )

        val found = map.findById("input_1")

        assertNotNull(found)
        assertEquals("input_1", found!!.id)
        assertEquals("Search", found.text)
    }

    @Test
    fun `findById returns null for missing ID`() {
        val map = makeMap(
            makeElement("btn_1", ElementType.BUTTON, "OK")
        )

        assertNull(map.findById("btn_99"))
    }

    @Test
    fun `findById on empty map returns null`() {
        val map = ElementMap(elements = emptyList(), screenWidth = 1080, screenHeight = 2400)

        assertNull(map.findById("btn_1"))
    }

    // ── toTextRepresentation ─────────────────────────────────────────────────

    @Test
    fun `toTextRepresentation includes screen dimensions`() {
        val map = ElementMap(elements = emptyList(), screenWidth = 1080, screenHeight = 2400)
        val text = map.toTextRepresentation()

        assertTrue(text.contains("1080x2400"))
    }

    @Test
    fun `toTextRepresentation includes element details`() {
        val map = makeMap(
            UIElement(
                id = "btn_1",
                type = ElementType.BUTTON,
                text = "Submit",
                bounds = Rect(10, 20, 200, 80),
                isClickable = true,
                relativePosition = "top-left"
            )
        )

        val text = map.toTextRepresentation()

        assertTrue(text.contains("[btn_1]"))
        assertTrue(text.contains("BUTTON"))
        assertTrue(text.contains("\"Submit\""))
        assertTrue(text.contains("clickable"))
        assertTrue(text.contains("(10,20,200,80)"))
        assertTrue(text.contains("top-left"))
    }

    @Test
    fun `toTextRepresentation includes multiple flags`() {
        val map = makeMap(
            UIElement(
                id = "input_1",
                type = ElementType.INPUT,
                text = "Name",
                bounds = Rect(0, 0, 100, 50),
                isClickable = true,
                isFocusable = true,
                isScrollable = true,
                isChecked = true
            )
        )

        val text = map.toTextRepresentation()

        assertTrue(text.contains("clickable"))
        assertTrue(text.contains("focusable"))
        assertTrue(text.contains("scrollable"))
        assertTrue(text.contains("checked"))
    }

    @Test
    fun `toTextRepresentation omits flags when none set`() {
        val map = makeMap(
            UIElement(
                id = "text_1",
                type = ElementType.TEXT,
                text = "Hello",
                bounds = Rect(0, 0, 100, 50)
            )
        )

        val text = map.toTextRepresentation()

        assertFalse(text.contains("clickable"))
        assertFalse(text.contains("focusable"))
        assertFalse(text.contains("scrollable"))
        assertFalse(text.contains("checked"))
    }

    @Test
    fun `toTextRepresentation omits text when blank`() {
        val map = makeMap(
            UIElement(
                id = "btn_1",
                type = ElementType.BUTTON,
                text = "",
                bounds = Rect(0, 0, 100, 50)
            )
        )

        val text = map.toTextRepresentation()

        // Should not have quotes for blank text
        assertFalse(text.contains("\"\""))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun makeElement(id: String, type: ElementType, text: String) = UIElement(
        id = id,
        type = type,
        text = text,
        bounds = Rect(0, 0, 100, 50)
    )

    private fun makeMap(vararg elements: UIElement) = ElementMap(
        elements = elements.toList(),
        screenWidth = 1080,
        screenHeight = 2400
    )
}
