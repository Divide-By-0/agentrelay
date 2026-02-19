package com.agentrelay

import com.agentrelay.models.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ClaudeAPIClientParsingTest {

    private lateinit var client: ClaudeAPIClient

    @Before
    fun setup() {
        client = ClaudeAPIClient(apiKey = "test-key")
    }

    // ── parseSemanticActionPlan ──────────────────────────────────────────────

    @Test
    fun `parseSemanticActionPlan with valid JSON`() {
        val json = """
            {
                "steps": [
                    {"action": "click", "element": "btn_1", "description": "Tap OK button"},
                    {"action": "type", "element": "input_1", "text": "hello", "description": "Type greeting"}
                ],
                "reasoning": "Need to fill form"
            }
        """.trimIndent()

        val plan = client.parseSemanticActionPlan(json)

        assertEquals(2, plan.steps.size)
        assertEquals(SemanticAction.CLICK, plan.steps[0].action)
        assertEquals("btn_1", plan.steps[0].element)
        assertEquals("Tap OK button", plan.steps[0].description)
        assertEquals(SemanticAction.TYPE, plan.steps[1].action)
        assertEquals("input_1", plan.steps[1].element)
        assertEquals("hello", plan.steps[1].text)
        assertEquals("Need to fill form", plan.reasoning)
    }

    @Test
    fun `parseSemanticActionPlan with markdown code blocks`() {
        val json = """
            ```json
            {"steps": [{"action": "click", "element": "btn_1", "description": "Click button"}], "reasoning": "test"}
            ```
        """.trimIndent()

        val plan = client.parseSemanticActionPlan(json)

        assertEquals(1, plan.steps.size)
        assertEquals(SemanticAction.CLICK, plan.steps[0].action)
    }

    @Test
    fun `parseSemanticActionPlan with generic code blocks`() {
        val json = """
            ```
            {"steps": [{"action": "back", "description": "Go back"}], "reasoning": "navigating"}
            ```
        """.trimIndent()

        val plan = client.parseSemanticActionPlan(json)

        assertEquals(1, plan.steps.size)
        assertEquals(SemanticAction.BACK, plan.steps[0].action)
    }

    @Test
    fun `parseSemanticActionPlan all action types`() {
        val json = """
            {
                "steps": [
                    {"action": "click", "element": "btn_1", "description": "click"},
                    {"action": "type", "element": "input_1", "text": "hi", "description": "type"},
                    {"action": "swipe", "direction": "up", "description": "swipe"},
                    {"action": "back", "description": "back"},
                    {"action": "home", "description": "home"},
                    {"action": "press_enter", "description": "submit"},
                    {"action": "wait", "description": "wait"},
                    {"action": "complete", "description": "done"}
                ],
                "reasoning": "all actions"
            }
        """.trimIndent()

        val plan = client.parseSemanticActionPlan(json)

        assertEquals(8, plan.steps.size)
        assertEquals(SemanticAction.CLICK, plan.steps[0].action)
        assertEquals(SemanticAction.TYPE, plan.steps[1].action)
        assertEquals(SemanticAction.SWIPE, plan.steps[2].action)
        assertEquals("up", plan.steps[2].direction)
        assertEquals(SemanticAction.BACK, plan.steps[3].action)
        assertEquals(SemanticAction.HOME, plan.steps[4].action)
        assertEquals(SemanticAction.PRESS_ENTER, plan.steps[5].action)
        assertEquals(SemanticAction.WAIT, plan.steps[6].action)
        assertEquals(SemanticAction.COMPLETE, plan.steps[7].action)
    }

    @Test
    fun `parseSemanticActionPlan missing fields uses defaults`() {
        val json = """{"steps": [{}], "reasoning": ""}"""

        val plan = client.parseSemanticActionPlan(json)

        assertEquals(1, plan.steps.size)
        assertEquals(SemanticAction.WAIT, plan.steps[0].action)
        assertNull(plan.steps[0].element)
        assertNull(plan.steps[0].text)
        assertEquals("", plan.steps[0].description)
    }

    @Test
    fun `parseSemanticActionPlan malformed JSON returns WAIT with retry description`() {
        val plan = client.parseSemanticActionPlan("not valid json at all")

        assertEquals(1, plan.steps.size)
        assertEquals(SemanticAction.WAIT, plan.steps[0].action)
        assertTrue(plan.steps[0].description.contains("unparseable", ignoreCase = true))
        assertTrue(plan.reasoning.contains("Parse error"))
    }

    @Test
    fun `parseSemanticActionPlan unknown action maps to WAIT`() {
        val json = """{"steps": [{"action": "fly", "description": "fly away"}], "reasoning": ""}"""

        val plan = client.parseSemanticActionPlan(json)

        assertEquals(SemanticAction.WAIT, plan.steps[0].action)
    }

    @Test
    fun `parseSemanticActionPlan with surrounding text`() {
        val json = """Here is my response: {"steps": [{"action": "click", "element": "btn_1", "description": "tap"}], "reasoning": "x"} hope this helps"""

        val plan = client.parseSemanticActionPlan(json)

        assertEquals(1, plan.steps.size)
        assertEquals(SemanticAction.CLICK, plan.steps[0].action)
    }

    // ── parseActionWithDescription ──────────────────────────────────────────

    @Test
    fun `parseActionWithDescription tap action`() {
        val json = """{"action": "tap", "x": 540, "y": 1200, "description": "Tap center"}"""

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Tap)
        val tap = result.action as AgentAction.Tap
        assertEquals(540, tap.x)
        assertEquals(1200, tap.y)
        assertEquals("Tap center", result.description)
    }

    @Test
    fun `parseActionWithDescription swipe action`() {
        val json = """{"action": "swipe", "startX": 100, "startY": 200, "endX": 100, "endY": 800, "duration": 300, "description": "Scroll down"}"""

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Swipe)
        val swipe = result.action as AgentAction.Swipe
        assertEquals(100, swipe.startX)
        assertEquals(200, swipe.startY)
        assertEquals(100, swipe.endX)
        assertEquals(800, swipe.endY)
        assertEquals(300L, swipe.duration)
    }

    @Test
    fun `parseActionWithDescription swipe defaults duration to 500`() {
        val json = """{"action": "swipe", "startX": 0, "startY": 0, "endX": 100, "endY": 100, "description": "Swipe"}"""

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Swipe)
        assertEquals(500L, (result.action as AgentAction.Swipe).duration)
    }

    @Test
    fun `parseActionWithDescription type action`() {
        val json = """{"action": "type", "text": "hello world", "description": "Type greeting"}"""

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Type)
        assertEquals("hello world", (result.action as AgentAction.Type).text)
    }

    @Test
    fun `parseActionWithDescription back action`() {
        val json = """{"action": "back", "description": "Navigate back"}"""

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Back)
    }

    @Test
    fun `parseActionWithDescription home action`() {
        val json = """{"action": "home", "description": "Go home"}"""

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Home)
    }

    @Test
    fun `parseActionWithDescription wait action`() {
        val json = """{"action": "wait", "ms": 2000, "description": "Waiting"}"""

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Wait)
        assertEquals(2000L, (result.action as AgentAction.Wait).ms)
    }

    @Test
    fun `parseActionWithDescription complete action`() {
        val json = """{"action": "complete", "message": "All done", "description": "Task finished"}"""

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Complete)
        assertEquals("All done", (result.action as AgentAction.Complete).message)
    }

    @Test
    fun `parseActionWithDescription complete with no message defaults`() {
        val json = """{"action": "complete", "description": "Done"}"""

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Complete)
        assertEquals("Task completed", (result.action as AgentAction.Complete).message)
    }

    @Test
    fun `parseActionWithDescription unknown action returns Error`() {
        val json = """{"action": "teleport", "description": "Teleporting"}"""

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Error)
        assertTrue((result.action as AgentAction.Error).message.contains("teleport"))
    }

    @Test
    fun `parseActionWithDescription markdown-wrapped JSON`() {
        val json = """
            ```json
            {"action": "tap", "x": 100, "y": 200, "description": "Tap item"}
            ```
        """.trimIndent()

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Tap)
    }

    @Test
    fun `parseActionWithDescription invalid JSON returns error action`() {
        val result = client.parseActionWithDescription("totally broken {{{")

        assertTrue(result.action is AgentAction.Error)
        assertEquals("Failed to parse action", result.description)
    }

    @Test
    fun `parseActionWithDescription missing description defaults`() {
        val json = """{"action": "back"}"""

        val result = client.parseActionWithDescription(json)

        assertTrue(result.action is AgentAction.Back)
        assertEquals("No description provided", result.description)
    }
}
