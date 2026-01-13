package com.agentrelay

import android.util.Log
import com.agentrelay.models.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface ClaudeAPI {
    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeRequest
    ): Response<ClaudeResponse>
}

class ClaudeAPIClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-5", // Default model
    private val onUploadComplete: ((bytes: Int, milliseconds: Long) -> Unit)? = null
) {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(ContentBlock::class.java, JsonDeserializer { json, _, _ ->
            val jsonObject = json.asJsonObject
            when (jsonObject.get("type").asString) {
                "text" -> ContentBlock.TextContent(
                    text = jsonObject.get("text").asString
                )
                "image" -> ContentBlock.ImageContent(
                    source = Gson().fromJson(jsonObject.get("source"), ImageSource::class.java)
                )
                else -> null
            }
        })
        .create()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.anthropic.com/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    private val api = retrofit.create(ClaudeAPI::class.java)

    suspend fun sendMessage(
        messages: List<Message>,
        systemPrompt: String
    ): Result<ClaudeResponse> {
        return try {
            val request = ClaudeRequest(
                model = model,
                maxTokens = 4096,
                messages = messages,
                system = systemPrompt
            )

            val startTime = System.currentTimeMillis()
            val response = api.createMessage(apiKey, request = request)
            val endTime = System.currentTimeMillis()

            // Estimate upload size (rough approximation based on image data)
            val estimatedBytes = messages.sumOf { message ->
                message.content.sumOf { content ->
                    when (content) {
                        is ContentBlock.ImageContent -> content.source.data.length / 4 * 3 // Base64 to bytes
                        is ContentBlock.TextContent -> content.text.length
                        else -> 0
                    }
                }
            }
            onUploadComplete?.invoke(estimatedBytes, endTime - startTime)

            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API Error: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "API request failed", e)
            Result.failure(e)
        }
    }

    suspend fun sendWithScreenshot(
        screenshotInfo: ScreenshotInfo,
        userTask: String,
        conversationHistory: List<Message>
    ): Result<ActionWithDescription> {
        val systemPrompt = """
            You are an Android automation agent. Analyze the screenshot and respond with a JSON action.

            CRITICAL: Be CONCISE. Descriptions should be 3-7 words max. Get to the goal efficiently.

            IMPORTANT SCREEN INFORMATION:
            - The screenshot you see is ${screenshotInfo.scaledWidth}x${screenshotInfo.scaledHeight} pixels
            - The screenshot has a NUMBERED GRID OVERLAY with lines every 100 pixels
            - Yellow numbers show X coordinates (at top) and Y coordinates (at left)
            - Use these grid numbers to determine precise tap coordinates
            - Return coordinates based on what you see in the screenshot
            - The system will automatically scale your coordinates to the actual device screen

            Available actions:
            - {"action": "tap", "x": 100, "y": 200, "description": "Tapping Settings button"}
            - {"action": "swipe", "startX": 100, "startY": 200, "endX": 300, "endY": 400, "duration": 500, "description": "Scrolling down"}
            - {"action": "type", "text": "hello", "description": "Typing 'hello'"}
            - {"action": "back", "description": "Going back"}
            - {"action": "home", "description": "Going to home screen"}
            - {"action": "complete", "message": "Task finished", "description": "Task completed"}
            - {"action": "wait", "ms": 1000, "description": "Waiting for UI to settle"}

            Rules:
            1. Respond with ONLY ONE action as valid JSON (no markdown, no explanation)
            2. ALWAYS include a brief "description" field (max 5-10 words)
            3. Use coordinates based on the ${screenshotInfo.scaledWidth}x${screenshotInfo.scaledHeight} screenshot
            4. Be precise with tap coordinates - aim for the center of visible elements
            5. For multi-step tasks, choose the MOST EFFICIENT path with fewest actions
            6. Minimize unnecessary steps - go directly to the goal when possible
            7. When you need to type and then submit, tap the input field in one action, then type in the next iteration
            8. Use "complete" when the task is done or impossible
            9. If stuck after 3 attempts, use "back" or report completion failure
           10. Keep descriptions brief and action-focused (5-10 words max)

            User task: $userTask

            Respond with ONLY a single JSON action object.
        """.trimIndent()

        val messages = conversationHistory.toMutableList()
        messages.add(
            Message(
                role = "user",
                content = listOf(
                    ContentBlock.ImageContent(
                        source = ImageSource(
                            data = screenshotInfo.base64Data,
                            mediaType = screenshotInfo.mediaType
                        )
                    ),
                    ContentBlock.TextContent(text = "What should I do next?")
                )
            )
        )

        return sendMessage(messages, systemPrompt).mapCatching { response ->
            val text = response.content.firstOrNull()?.text
                ?: throw Exception("No text in response")

            parseActionWithDescription(text)
        }
    }

    private fun parseActionWithDescription(jsonText: String): ActionWithDescription {
        return try {
            // Extract JSON from markdown code blocks or find first JSON object
            var cleanJson = jsonText.trim()

            // Remove markdown code blocks
            if (cleanJson.contains("```json")) {
                cleanJson = cleanJson.substringAfter("```json").substringBefore("```").trim()
            } else if (cleanJson.contains("```")) {
                cleanJson = cleanJson.substringAfter("```").substringBefore("```").trim()
            }

            // Find first { and last } to extract just the JSON object
            val startIndex = cleanJson.indexOf('{')
            val endIndex = cleanJson.lastIndexOf('}')

            if (startIndex >= 0 && endIndex > startIndex) {
                cleanJson = cleanJson.substring(startIndex, endIndex + 1)
            }

            val json = JsonParser.parseString(cleanJson).asJsonObject
            val description = json.get("description")?.asString ?: "No description provided"

            val action = when (json.get("action").asString) {
                "tap" -> AgentAction.Tap(
                    x = json.get("x").asInt,
                    y = json.get("y").asInt
                )
                "swipe" -> AgentAction.Swipe(
                    startX = json.get("startX").asInt,
                    startY = json.get("startY").asInt,
                    endX = json.get("endX").asInt,
                    endY = json.get("endY").asInt,
                    duration = json.get("duration")?.asLong ?: 500
                )
                "type" -> AgentAction.Type(text = json.get("text").asString)
                "back" -> AgentAction.Back
                "home" -> AgentAction.Home
                "wait" -> AgentAction.Wait(ms = json.get("ms").asLong)
                "complete" -> AgentAction.Complete(
                    message = json.get("message")?.asString ?: "Task completed"
                )
                else -> AgentAction.Error("Unknown action: ${json.get("action").asString}")
            }

            ActionWithDescription(action, description)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse action from: $jsonText", e)
            ActionWithDescription(
                AgentAction.Error("Failed to parse action: ${e.message}"),
                "Failed to parse action"
            )
        }
    }

    companion object {
        private const val TAG = "ClaudeAPIClient"
    }
}
