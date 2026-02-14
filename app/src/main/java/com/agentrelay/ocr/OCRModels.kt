package com.agentrelay.ocr

import android.graphics.Rect
import com.google.gson.annotations.SerializedName

// Google Cloud Vision API models
data class VisionRequest(
    val requests: List<AnnotateImageRequest>
)

data class AnnotateImageRequest(
    val image: VisionImage,
    val features: List<Feature>
)

data class VisionImage(
    val content: String // base64 image data
)

data class Feature(
    val type: String = "TEXT_DETECTION"
)

data class VisionResponse(
    val responses: List<AnnotateImageResponse>?
)

data class AnnotateImageResponse(
    val textAnnotations: List<TextAnnotation>?,
    val error: VisionError?
)

data class TextAnnotation(
    val description: String?,
    val boundingPoly: BoundingPoly?
)

data class BoundingPoly(
    val vertices: List<Vertex>?
)

data class Vertex(
    val x: Int = 0,
    val y: Int = 0
)

data class VisionError(
    val code: Int?,
    val message: String?
)

// Replicate API models
data class ReplicateRequest(
    val version: String,
    val input: ReplicateInput
)

data class ReplicateInput(
    val image: String // data URI
)

data class ReplicateResponse(
    val id: String?,
    val status: String?,
    val output: Any?,
    val error: String?,
    val urls: ReplicateUrls?
)

data class ReplicateUrls(
    val get: String?,
    val cancel: String?
)

// Internal OCR result
data class OCRTextBlock(
    val text: String,
    val bounds: Rect
)
