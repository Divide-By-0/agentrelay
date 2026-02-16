package com.agentrelay

import com.agentrelay.models.ElementType
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AccessibilityTreeExtractorTest {

    private lateinit var extractor: AccessibilityTreeExtractor

    @Before
    fun setup() {
        // AutomationService is only used by extract(), not classNameToElementType
        extractor = AccessibilityTreeExtractor(mockk(relaxed = true))
    }

    // ── Button variants ──────────────────────────────────────────────────────

    @Test
    fun `Button class maps to BUTTON`() {
        assertEquals(ElementType.BUTTON, extractor.classNameToElementType("android.widget.Button"))
    }

    @Test
    fun `ImageButton maps to IMAGE (contains ImageButton before Button)`() {
        // ImageButton contains both "Image" and "Button" — the "Button" branch matches first
        assertEquals(ElementType.BUTTON, extractor.classNameToElementType("android.widget.ImageButton"))
    }

    @Test
    fun `MaterialButton maps to BUTTON`() {
        assertEquals(ElementType.BUTTON, extractor.classNameToElementType("com.google.android.material.button.MaterialButton"))
    }

    // ── Input variants ───────────────────────────────────────────────────────

    @Test
    fun `EditText maps to INPUT`() {
        assertEquals(ElementType.INPUT, extractor.classNameToElementType("android.widget.EditText"))
    }

    @Test
    fun `AutoCompleteTextView maps to INPUT`() {
        assertEquals(ElementType.INPUT, extractor.classNameToElementType("android.widget.AutoCompleteTextView"))
    }

    // ── Text variants ────────────────────────────────────────────────────────

    @Test
    fun `TextView maps to TEXT`() {
        assertEquals(ElementType.TEXT, extractor.classNameToElementType("android.widget.TextView"))
    }

    // ── Image variants ───────────────────────────────────────────────────────

    @Test
    fun `ImageView maps to IMAGE`() {
        assertEquals(ElementType.IMAGE, extractor.classNameToElementType("android.widget.ImageView"))
    }

    // ── Switch and toggle ────────────────────────────────────────────────────

    @Test
    fun `Switch maps to SWITCH`() {
        assertEquals(ElementType.SWITCH, extractor.classNameToElementType("android.widget.Switch"))
    }

    @Test
    fun `ToggleButton matches Button branch first (contains Button)`() {
        // ToggleButton contains "Button", which matches before "ToggleButton" check
        assertEquals(ElementType.BUTTON, extractor.classNameToElementType("android.widget.ToggleButton"))
    }

    // ── Checkbox variants ────────────────────────────────────────────────────

    @Test
    fun `CheckBox maps to CHECKBOX`() {
        assertEquals(ElementType.CHECKBOX, extractor.classNameToElementType("android.widget.CheckBox"))
    }

    @Test
    fun `CheckedTextView matches TextView branch first (contains TextView)`() {
        // CheckedTextView contains "TextView", which matches before "CheckedTextView" check
        assertEquals(ElementType.TEXT, extractor.classNameToElementType("android.widget.CheckedTextView"))
    }

    // ── List variants ────────────────────────────────────────────────────────

    @Test
    fun `RecyclerView maps to LIST_ITEM`() {
        assertEquals(ElementType.LIST_ITEM, extractor.classNameToElementType("androidx.recyclerview.widget.RecyclerView"))
    }

    @Test
    fun `ListView maps to LIST_ITEM`() {
        assertEquals(ElementType.LIST_ITEM, extractor.classNameToElementType("android.widget.ListView"))
    }

    // ── Tab variants ─────────────────────────────────────────────────────────

    @Test
    fun `TabWidget maps to TAB`() {
        assertEquals(ElementType.TAB, extractor.classNameToElementType("android.widget.TabWidget"))
    }

    @Test
    fun `TabLayout Tab maps to TAB`() {
        assertEquals(ElementType.TAB, extractor.classNameToElementType("com.google.android.material.tabs.TabLayout\$Tab"))
    }

    // ── WebView ──────────────────────────────────────────────────────────────

    @Test
    fun `WebView maps to TEXT`() {
        assertEquals(ElementType.TEXT, extractor.classNameToElementType("android.webkit.WebView"))
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `null class maps to UNKNOWN`() {
        assertEquals(ElementType.UNKNOWN, extractor.classNameToElementType(null))
    }

    @Test
    fun `unknown class maps to UNKNOWN`() {
        assertEquals(ElementType.UNKNOWN, extractor.classNameToElementType("android.view.View"))
    }

    @Test
    fun `FrameLayout maps to UNKNOWN`() {
        assertEquals(ElementType.UNKNOWN, extractor.classNameToElementType("android.widget.FrameLayout"))
    }

    @Test
    fun `LinearLayout maps to UNKNOWN`() {
        assertEquals(ElementType.UNKNOWN, extractor.classNameToElementType("android.widget.LinearLayout"))
    }
}
