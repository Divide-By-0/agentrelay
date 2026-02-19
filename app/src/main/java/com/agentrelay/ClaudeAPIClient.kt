package com.agentrelay

import android.util.Log
import com.agentrelay.models.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

interface ClaudeAPI {
    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeRequest
    ): Response<ClaudeResponse>
}

/** Logs OkHttp output on a background thread so body logging doesn't block the HTTP call. */
private val asyncHttpLogger = object : HttpLoggingInterceptor.Logger {
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OkHttpAsyncLogger").apply { isDaemon = true }
    }
    override fun log(message: String) {
        executor.execute { Log.d("OkHttp", message) }
    }
}

/** EventListener that logs TTFB (time-to-first-byte) and total response time. */
private class LatencyEventListener : EventListener() {
    private var callStartMs = 0L
    private var connectEndMs = 0L
    private var requestEndMs = 0L
    private var responseStartMs = 0L

    override fun callStart(call: Call) {
        callStartMs = System.currentTimeMillis()
    }

    override fun connectEnd(call: Call, remoteAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: Protocol?) {
        connectEndMs = System.currentTimeMillis()
        Log.d("ClaudeLatency", "TCP+TLS connect: ${connectEndMs - callStartMs}ms")
    }

    override fun requestBodyEnd(call: Call, byteCount: Long) {
        requestEndMs = System.currentTimeMillis()
        val uploadKB = byteCount / 1024
        Log.d("ClaudeLatency", "Request body sent: ${requestEndMs - callStartMs}ms (${uploadKB}KB uploaded)")
    }

    override fun responseHeadersEnd(call: Call, response: okhttp3.Response) {
        responseStartMs = System.currentTimeMillis()
        val ttfb = responseStartMs - requestEndMs
        val totalSoFar = responseStartMs - callStartMs
        Log.d("ClaudeLatency", "TTFB (server thinking): ${ttfb}ms | total so far: ${totalSoFar}ms")
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        val now = System.currentTimeMillis()
        val downloadTime = now - responseStartMs
        val total = now - callStartMs
        val downloadKB = byteCount / 1024
        Log.d("ClaudeLatency", "Response body: ${downloadTime}ms (${downloadKB}KB) | TOTAL: ${total}ms")
    }

    override fun callFailed(call: Call, ioe: java.io.IOException) {
        val total = System.currentTimeMillis() - callStartMs
        Log.e("ClaudeLatency", "Call failed after ${total}ms: ${ioe.message}")
    }
}

class ClaudeAPIClient(
    apiKey: String,
    model: String = "claude-sonnet-4-6",
    onUploadComplete: ((bytes: Int, milliseconds: Long) -> Unit)? = null
) : LLMClient(apiKey, model, onUploadComplete) {

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
                .addInterceptor(HttpLoggingInterceptor(asyncHttpLogger).apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
                .eventListenerFactory { LatencyEventListener() }
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    private val api = retrofit.create(ClaudeAPI::class.java)

    override suspend fun sendRaw(
        messages: List<Message>,
        systemPrompt: String
    ): Result<String> {
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

            // Estimate upload size
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
                val text = response.body()!!.content.firstOrNull()?.text
                    ?: throw Exception("No text in Claude response")
                Result.success(text)
            } else {
                Result.failure(Exception("API Error: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "API request failed", e)
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "ClaudeAPIClient"
    }
}
