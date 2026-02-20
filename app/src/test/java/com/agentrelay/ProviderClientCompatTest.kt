package com.agentrelay

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderClientCompatTest {

    @Test
    fun `openai extractAssistantText handles primitive string`() {
        val content = JsonParser.parseString("\"{\\\"steps\\\":[]}\"")
        val text = OpenAIClient.extractAssistantText(content)
        assertEquals("{\"steps\":[]}", text)
    }

    @Test
    fun `openai extractAssistantText joins text parts from array`() {
        val content = JsonParser.parseString(
            """
            [
              {"type":"output_text","text":"{\"steps\":["},
              {"type":"output_text","text":"{\"action\":\"wait\"}]}"},
              {"type":"reasoning","summary":"ignored"}
            ]
            """.trimIndent()
        )
        val text = OpenAIClient.extractAssistantText(content)
        assertEquals("{\"steps\":[\n{\"action\":\"wait\"}]}", text)
    }

    @Test
    fun `openai extractAssistantText returns null for empty array`() {
        val content = JsonParser.parseString("[]")
        val text = OpenAIClient.extractAssistantText(content)
        assertNull(text)
    }

    @Test
    fun `gemini normalizeModel maps flash alias`() {
        assertEquals("gemini-2.5-flash", GeminiClient.normalizeModel("gemini-3.0-flash"))
    }

    @Test
    fun `gemini normalizeModel keeps supported ids unchanged`() {
        assertEquals("gemini-2.5-pro", GeminiClient.normalizeModel("gemini-2.5-pro"))
    }
}
