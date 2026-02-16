package com.agentrelay

import io.mockk.every
import io.mockk.mockk
import android.content.Context
import android.view.WindowManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class TaskSuggestionTest {

    private lateinit var overlay: TaskSuggestionOverlay

    @Before
    fun setup() {
        overlay = TaskSuggestionOverlay(RuntimeEnvironment.getApplication())
    }

    // ── generateSuggestions: "not found" pattern ─────────────────────────────

    @Test
    fun `not found failure suggests opening app first`() {
        val suggestions = overlay.generateSuggestions("send a message", "Element not found on screen")

        assertTrue(suggestions.any { it.contains("First open the app") })
        assertTrue(suggestions.any { it.contains("Navigate to home screen") })
    }

    @Test
    fun `cannot find failure suggests opening app first`() {
        val suggestions = overlay.generateSuggestions("open settings", "Cannot find the settings icon")

        assertTrue(suggestions.any { it.contains("First open the app") })
    }

    // ── generateSuggestions: "permission" pattern ────────────────────────────

    @Test
    fun `permission failure suggests granting permissions`() {
        val suggestions = overlay.generateSuggestions("take a photo", "Permission denied for camera")

        assertTrue(suggestions.any { it.contains("Grant necessary permissions") })
    }

    // ── generateSuggestions: "login" pattern ─────────────────────────────────

    @Test
    fun `login failure suggests signing in first`() {
        val suggestions = overlay.generateSuggestions("post a tweet", "Need to login first")

        assertTrue(suggestions.any { it.contains("Sign in") })
    }

    @Test
    fun `sign in failure suggests signing in first`() {
        val suggestions = overlay.generateSuggestions("check email", "Requires sign in")

        assertTrue(suggestions.any { it.contains("Sign in") })
    }

    // ── generateSuggestions: generic fallback ────────────────────────────────

    @Test
    fun `generic failure suggests simplification and step breakdown`() {
        val suggestions = overlay.generateSuggestions("do something complex", "Unknown error occurred")

        assertTrue(suggestions.any { it.contains("simpler version") })
        assertTrue(suggestions.any { it.contains("Break into steps") })
    }

    // ── generateSuggestions: max 3 results ───────────────────────────────────

    @Test
    fun `suggestions capped at 3`() {
        val suggestions = overlay.generateSuggestions("any task", "any failure")

        assertTrue(suggestions.size <= 3)
    }

    // ── simplifyTask ─────────────────────────────────────────────────────────

    @Test
    fun `simplifyTask truncates long tasks to 5 words`() {
        val result = overlay.simplifyTask("open the settings app and change the wallpaper to something nice")

        assertEquals("open the settings app and...", result)
    }

    @Test
    fun `simplifyTask keeps short tasks unchanged`() {
        val result = overlay.simplifyTask("open settings")

        assertEquals("open settings", result)
    }

    @Test
    fun `simplifyTask keeps exactly 5 word tasks unchanged`() {
        val result = overlay.simplifyTask("one two three four five")

        assertEquals("one two three four five", result)
    }

    // ── breakIntoSteps ───────────────────────────────────────────────────────

    @Test
    fun `breakIntoSteps extracts first comma-separated step`() {
        val result = overlay.breakIntoSteps("open Chrome, go to Google, search for weather")

        assertEquals("First step: open Chrome", result)
    }

    @Test
    fun `breakIntoSteps with no commas uses full task`() {
        val result = overlay.breakIntoSteps("open Chrome and search")

        assertEquals("First step: open Chrome and search", result)
    }

    // ── Case insensitivity ───────────────────────────────────────────────────

    @Test
    fun `not found matching is case insensitive`() {
        val suggestions = overlay.generateSuggestions("task", "NOT FOUND anywhere")

        assertTrue(suggestions.any { it.contains("First open the app") })
    }

    @Test
    fun `permission matching is case insensitive`() {
        val suggestions = overlay.generateSuggestions("task", "PERMISSION required")

        assertTrue(suggestions.any { it.contains("Grant necessary permissions") })
    }

    @Test
    fun `login matching is case insensitive`() {
        val suggestions = overlay.generateSuggestions("task", "Please LOGIN to continue")

        assertTrue(suggestions.any { it.contains("Sign in") })
    }
}
