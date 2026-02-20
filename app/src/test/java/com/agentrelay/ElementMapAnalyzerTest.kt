package com.agentrelay

import android.graphics.Rect
import com.agentrelay.models.ElementMap
import com.agentrelay.models.ElementType
import com.agentrelay.models.UIElement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementMapAnalyzerTest {

    @Test
    fun `empty map always includes screenshot`() {
        val map = ElementMap(elements = emptyList(), screenWidth = 1080, screenHeight = 2400)
        val decision = ElementMapAnalyzer.shouldSendScreenshot(
            elementMap = map,
            ocrOnlyCount = 0,
            previousIterationFailed = false
        )
        assertTrue(decision.shouldSend)
    }

    @Test
    fun `rich map skips screenshot`() {
        val elements = (1..12).map { idx ->
            UIElement(
                id = "btn_$idx",
                type = ElementType.BUTTON,
                text = "Action $idx",
                bounds = Rect(10 * idx, 20 * idx, 10 * idx + 120, 20 * idx + 80),
                isClickable = true
            )
        }
        val map = ElementMap(elements = elements, screenWidth = 1080, screenHeight = 2400)
        val decision = ElementMapAnalyzer.shouldSendScreenshot(
            elementMap = map,
            ocrOnlyCount = 0,
            previousIterationFailed = false
        )
        assertFalse(decision.shouldSend)
    }

    @Test
    fun `borderline map includes screenshot more often with updated threshold`() {
        val elements = listOf(
            UIElement("btn_1", ElementType.BUTTON, "Continue", Rect(0, 0, 200, 120), isClickable = true),
            UIElement("btn_2", ElementType.BUTTON, "Continue", Rect(220, 0, 420, 120), isClickable = true),
            UIElement("btn_3", ElementType.BUTTON, "", Rect(440, 0, 640, 120), isClickable = true),
            UIElement("input_1", ElementType.INPUT, "Email", Rect(0, 200, 800, 320), isClickable = true, isFocusable = true),
            UIElement("text_1", ElementType.UNKNOWN, "", Rect(0, 380, 500, 480)),
            UIElement("text_2", ElementType.UNKNOWN, "", Rect(0, 500, 500, 600))
        )
        val map = ElementMap(elements = elements, screenWidth = 1080, screenHeight = 2400)
        val decision = ElementMapAnalyzer.shouldSendScreenshot(
            elementMap = map,
            ocrOnlyCount = 4,
            previousIterationFailed = true,
            sameMapCount = 1,
            structureRepeatCount = 2,
            keyboardVisible = true
        )
        assertTrue(decision.shouldSend)
    }
}
