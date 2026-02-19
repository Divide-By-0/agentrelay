package com.agentrelay

import android.util.Log
import com.agentrelay.models.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// ── OpenAI request/response models ──────────────────────────────────────────

private data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    @SerializedName("response_format")
    val responseFormat: OpenAIResponseFormat? = null,
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    @SerializedName("max_completion_tokens")
    val maxCompletionTokens: Int? = null
)

private data class OpenAIMessage(
    val role: String,
    val content: Any // String or List<OpenAIContentPart>
)

private data class OpenAIResponseFormat(
    val type: String = "json_object"
)

private sealed class OpenAIContentPart {
    data class Text(
        val type: String = "text",
        val text: String
    ) : OpenAIContentPart()

    data class ImageUrl(
        val type: String = "image_url",
        @SerializedName("image_url")
        val imageUrl: ImageUrlDetail
    ) : OpenAIContentPart()
}

private data class ImageUrlDetail(
    val url: String
)

private data class OpenAIResponse(
    val id: String?,
    val choices: List<OpenAIChoice>?,
    val usage: OpenAIUsage?,
    val error: OpenAIError?
)

private data class OpenAIChoice(
    val message: OpenAIChoiceMessage?
)

private data class OpenAIChoiceMessage(
    val role: String?,
    val content: JsonElement?,
    val refusal: String?
)

private data class OpenAIUsage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int?,
    @SerializedName("completion_tokens")
    val completionTokens: Int?
)

private data class OpenAIError(
    val message: String?,
    val type: String?
)

// ── Retrofit interface ──────────────────────────────────────────────────────

private interface OpenAIAPI {
    @POST("v1/chat/completions")
    suspend fun createChatCompletion(
        @Header("Authorization") authorization: String,
        @Body request: OpenAIRequest
    ): Response<OpenAIResponse>
}

// ── Client ──────────────────────────────────────────────────────────────────

class OpenAIClient(
    apiKey: String,
    model: String,
    onUploadComplete: ((bytes: Int, milliseconds: Long) -> Unit)? = null
) : LLMClient(apiKey, model, onUploadComplete) {

    private val gson: Gson = GsonBuilder().create()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.openai.com/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    private val api = retrofit.create(OpenAIAPI::class.java)

    override suspend fun sendRaw(
        messages: List<Message>,
        systemPrompt: String
    ): Result<String> {
        return try {
            val openAIMessages = mutableListOf<OpenAIMessage>()

            // System prompt as first message
            openAIMessages.add(OpenAIMessage(role = "system", content = systemPrompt))

            // Convert conversation messages
            for (msg in messages) {
                val parts = mutableListOf<OpenAIContentPart>()
                for (block in msg.content) {
                    when (block) {
                        is ContentBlock.TextContent -> {
                            parts.add(OpenAIContentPart.Text(text = block.text))
                        }
                        is ContentBlock.ImageContent -> {
                            val dataUrl = "data:${block.source.mediaType};base64,${block.source.data}"
                            parts.add(OpenAIContentPart.ImageUrl(
                                imageUrl = ImageUrlDetail(url = dataUrl)
                            ))
                        }
                    }
                }
                // If only one text part, send as plain string for efficiency
                val content: Any = if (parts.size == 1 && parts[0] is OpenAIContentPart.Text) {
                    (parts[0] as OpenAIContentPart.Text).text
                } else {
                    parts
                }
                openAIMessages.add(OpenAIMessage(role = msg.role, content = content))
            }

            val isReasoningModel = model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4")
            val request = OpenAIRequest(
                model = model,
                messages = openAIMessages,
                responseFormat = OpenAIResponseFormat(type = "json_object"),
                maxTokens = if (isReasoningModel) null else 4096,
                maxCompletionTokens = if (isReasoningModel) 4096 else null
            )

            val startTime = System.currentTimeMillis()
            val response = api.createChatCompletion(
                authorization = "Bearer $apiKey",
                request = request
            )
            val endTime = System.currentTimeMillis()

            // Report upload stats
            val estimatedBytes = messages.sumOf { message ->
                message.content.sumOf { content ->
                    when (content) {
                        is ContentBlock.ImageContent -> content.source.data.length / 4 * 3
                        is ContentBlock.TextContent -> content.text.length
                        else -> 0
                    }
                }
            }
            onUploadComplete?.invoke(estimatedBytes, endTime - startTime)

            if (response.isSuccessful && response.body() != null) {
                val body = response.body()!!
                if (body.error != null) {
                    Result.failure(Exception("OpenAI API Error: ${body.error.message}"))
                } else {
                    val firstMessage = body.choices?.firstOrNull()?.message
                    val text = extractAssistantText(firstMessage?.content)
                        ?: firstMessage?.refusal
                        ?: throw Exception("No parseable assistant content in OpenAI response")
                    Result.success(text)
                }
            } else {
                Result.failure(Exception("OpenAI API Error: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "OpenAI API request failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "OpenAIClient"

        @androidx.annotation.VisibleForTesting
        internal fun extractAssistantText(content: JsonElement?): String? {
            if (content == null || content.isJsonNull) return null

            if (content.isJsonPrimitive) {
                return content.asString.takeIf { it.isNotBlank() }
            }

            if (!content.isJsonArray) return null

            val combined = content.asJsonArray.mapNotNull { part ->
                val obj = part.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val text = obj.get("text")?.takeIf { !it.isJsonNull }?.asString ?: return@mapNotNull null
                text.takeIf { it.isNotBlank() }
            }.joinToString("\n")

            return combined.takeIf { it.isNotBlank() }
        }
    }
}
