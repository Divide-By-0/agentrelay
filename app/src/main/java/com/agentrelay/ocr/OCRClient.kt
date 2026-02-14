package com.agentrelay.ocr

import android.graphics.Rect
import android.util.Log
import com.agentrelay.ScreenshotInfo
import com.agentrelay.SecureStorage
import com.agentrelay.models.ElementSource
import com.agentrelay.models.ElementType
import com.agentrelay.models.UIElement
import com.google.gson.Gson
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class OCRClient(private val secureStorage: SecureStorage) {

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun recognizeText(screenshotInfo: ScreenshotInfo): List<UIElement> {
        val googleApiKey = secureStorage.getGoogleVisionApiKey()
        if (!googleApiKey.isNullOrBlank()) {
            try {
                val blocks = recognizeWithGoogleVision(screenshotInfo, googleApiKey)
                if (blocks.isNotEmpty()) {
                    return blocksToUIElements(blocks)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Google Vision failed, trying fallback", e)
            }
        }

        val replicateToken = secureStorage.getReplicateApiToken()
        if (!replicateToken.isNullOrBlank()) {
            try {
                val blocks = recognizeWithReplicate(screenshotInfo, replicateToken)
                return blocksToUIElements(blocks)
            } catch (e: Exception) {
                Log.w(TAG, "Replicate OCR also failed", e)
            }
        }

        Log.d(TAG, "No OCR providers configured or all failed")
        return emptyList()
    }

    private fun recognizeWithGoogleVision(
        screenshotInfo: ScreenshotInfo,
        apiKey: String
    ): List<OCRTextBlock> {
        val request = VisionRequest(
            requests = listOf(
                AnnotateImageRequest(
                    image = VisionImage(content = screenshotInfo.base64Data),
                    features = listOf(Feature(type = "TEXT_DETECTION"))
                )
            )
        )

        val jsonBody = gson.toJson(request)
        val httpRequest = Request.Builder()
            .url("https://vision.googleapis.com/v1/images:annotate?key=$apiKey")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()
        val body = response.body?.string() ?: return emptyList()

        if (!response.isSuccessful) {
            Log.e(TAG, "Google Vision error: ${response.code} - $body")
            return emptyList()
        }

        val visionResponse = gson.fromJson(body, VisionResponse::class.java)
        val annotations = visionResponse.responses?.firstOrNull()?.textAnnotations ?: return emptyList()

        // Skip the first annotation (full text) and use individual words
        return annotations.drop(1).mapNotNull { annotation ->
            val text = annotation.description ?: return@mapNotNull null
            val vertices = annotation.boundingPoly?.vertices ?: return@mapNotNull null
            if (vertices.size < 4) return@mapNotNull null

            val left = vertices.minOf { it.x }
            val top = vertices.minOf { it.y }
            val right = vertices.maxOf { it.x }
            val bottom = vertices.maxOf { it.y }

            // Scale from screenshot coordinates to actual screen coordinates
            val scaleX = screenshotInfo.actualWidth.toFloat() / screenshotInfo.scaledWidth
            val scaleY = screenshotInfo.actualHeight.toFloat() / screenshotInfo.scaledHeight

            OCRTextBlock(
                text = text,
                bounds = Rect(
                    (left * scaleX).toInt(),
                    (top * scaleY).toInt(),
                    (right * scaleX).toInt(),
                    (bottom * scaleY).toInt()
                )
            )
        }
    }

    private fun recognizeWithReplicate(
        screenshotInfo: ScreenshotInfo,
        apiToken: String
    ): List<OCRTextBlock> {
        val mediaType = screenshotInfo.mediaType
        val dataUri = "data:$mediaType;base64,${screenshotInfo.base64Data}"

        val request = ReplicateRequest(
            version = "abiruyt/text-extract-ocr:a524caeeb80fc73bce69ade42ee6fbb5",
            input = ReplicateInput(image = dataUri)
        )

        val jsonBody = gson.toJson(request)
        val httpRequest = Request.Builder()
            .url("https://api.replicate.com/v1/predictions")
            .header("Authorization", "Token $apiToken")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(httpRequest).execute()
        val body = response.body?.string() ?: return emptyList()

        if (!response.isSuccessful) {
            Log.e(TAG, "Replicate error: ${response.code} - $body")
            return emptyList()
        }

        val replicateResponse = gson.fromJson(body, ReplicateResponse::class.java)

        // Poll for result
        val getUrl = replicateResponse.urls?.get ?: return emptyList()
        val startTime = System.currentTimeMillis()
        val timeout = 15000L

        while (System.currentTimeMillis() - startTime < timeout) {
            Thread.sleep(1000)
            val pollRequest = Request.Builder()
                .url(getUrl)
                .header("Authorization", "Token $apiToken")
                .get()
                .build()

            val pollResponse = httpClient.newCall(pollRequest).execute()
            val pollBody = pollResponse.body?.string() ?: continue
            val pollResult = gson.fromJson(pollBody, ReplicateResponse::class.java)

            when (pollResult.status) {
                "succeeded" -> {
                    // Output is typically a string of extracted text
                    val outputText = pollResult.output?.toString() ?: return emptyList()
                    // Simple fallback: return single block with full text and full-screen bounds
                    return listOf(
                        OCRTextBlock(
                            text = outputText,
                            bounds = Rect(0, 0, screenshotInfo.actualWidth, screenshotInfo.actualHeight)
                        )
                    )
                }
                "failed", "canceled" -> {
                    Log.e(TAG, "Replicate prediction failed: ${pollResult.error}")
                    return emptyList()
                }
            }
        }

        Log.w(TAG, "Replicate prediction timed out")
        return emptyList()
    }

    private fun blocksToUIElements(blocks: List<OCRTextBlock>): List<UIElement> {
        return blocks.mapIndexed { index, block ->
            UIElement(
                id = "ocr_${index + 1}",
                type = ElementType.TEXT,
                text = block.text,
                bounds = block.bounds,
                source = ElementSource.OCR
            )
        }
    }

    companion object {
        private const val TAG = "OCRClient"
    }
}
