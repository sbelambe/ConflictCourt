package com.conflictcourt.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CodexService(
    private val config: CodexConfig,
    private val client: OkHttpClient = defaultClient(config),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    fun modelName(): String = config.model

    suspend fun resolveConflict(context: ConflictContext): CodexOutcome = withContext(Dispatchers.IO) {
        val endpoint = "${config.apiBaseUrl}/responses"
        val payload = buildRequestPayload(context)
        var lastBody: String? = null
        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.await(request).use { response ->
                val body = response.body?.string().orEmpty()
                lastBody = body
                if (!response.isSuccessful) {
                    return@withContext CodexOutcome.Failure(
                        statusCode = response.code,
                        message = "Codex API call failed",
                        rawResponse = body
                    )
                }

                val requestId = response.header("x-request-id")
                val modelOutput = extractModelOutputText(body)
                val cleanJson = unwrapMarkdownFence(modelOutput)
                val resolution = parseResolution(cleanJson)
                CodexOutcome.Success(
                    resolution = resolution,
                    requestId = requestId,
                    rawResponse = body
                )
            }
        } catch (e: Exception) {
            CodexOutcome.Failure(
                statusCode = null,
                message = e.message ?: "Unexpected Codex error",
                rawResponse = lastBody
            )
        }
    }

    private fun buildRequestPayload(context: ConflictContext): String {
        val schema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("merged_code", buildJsonObject { put("type", "string") })
                    put("inferred_intent", buildJsonObject { put("type", "string") })
                    put(
                        "confidence_score",
                        buildJsonObject {
                            put("type", "number")
                            put("minimum", 0.0)
                            put("maximum", 1.0)
                        }
                    )
                }
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("merged_code"))
                    add(JsonPrimitive("inferred_intent"))
                    add(JsonPrimitive("confidence_score"))
                }
            )
            put("additionalProperties", false)
        }

        val input = buildJsonArray {
            add(message("system", MergePromptFactory.SYSTEM_PROMPT))
            add(message("user", MergePromptFactory.createUserPrompt(context)))
        }

        val root = buildJsonObject {
            put("model", config.model)
            put("input", input)
            put(
                "text",
                buildJsonObject {
                    put(
                        "format",
                        buildJsonObject {
                            put("type", "json_schema")
                            put("name", "conflict_resolution")
                            put("strict", true)
                            put("schema", schema)
                        }
                    )
                }
            )
            put("max_output_tokens", config.maxOutputTokens)
        }

        return root.toString()
    }

    private fun message(role: String, text: String): JsonObject {
        return buildJsonObject {
            put("role", role)
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "input_text")
                            put("text", text)
                        }
                    )
                }
            )
        }
    }

    private fun extractModelOutputText(responseJson: String): String {
        val root = json.parseToJsonElement(responseJson).jsonObject
        val directOutputText = root["output_text"]?.jsonPrimitive?.contentOrNull
        if (!directOutputText.isNullOrBlank()) return directOutputText

        val output = root["output"]?.jsonArray.orEmpty()
        for (item in output) {
            val content = item.jsonObject["content"]?.jsonArray.orEmpty()
            for (part in content) {
                val partObj = part.jsonObject
                val type = partObj["type"]?.jsonPrimitive?.contentOrNull
                if (type == "output_text") {
                    val text = partObj["text"]?.jsonPrimitive?.contentOrNull
                    if (!text.isNullOrBlank()) return text
                }
            }
        }

        // Fallback shape for compatibility if backend route changes.
        val choices = root["choices"]?.jsonArray.orEmpty()
        if (choices.isNotEmpty()) {
            val content = choices.first().jsonObject["message"]?.jsonObject
                ?.get("content")?.jsonPrimitive?.contentOrNull
            if (!content.isNullOrBlank()) return content
        }

        throw IllegalStateException("No textual model output was found in Codex response")
    }

    private fun parseResolution(modelJson: String): ConflictResolution {
        return json.decodeFromString(ConflictResolution.serializer(), modelJson)
    }

    private fun unwrapMarkdownFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        return trimmed
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultClient(config: CodexConfig): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds.toLong()))
                .readTimeout(Duration.ofSeconds(config.readTimeoutSeconds.toLong()))
                .writeTimeout(Duration.ofSeconds(config.writeTimeoutSeconds.toLong()))
                .build()
        }
    }
}

@Serializable
data class ConflictResolution(
    @SerialName("merged_code")
    val mergedCode: String,
    @SerialName("inferred_intent")
    val inferredIntent: String,
    @SerialName("confidence_score")
    val confidenceScore: Double
)

sealed class CodexOutcome {
    data class Success(
        val resolution: ConflictResolution,
        val requestId: String?,
        val rawResponse: String
    ) : CodexOutcome()

    data class Failure(
        val statusCode: Int?,
        val message: String,
        val rawResponse: String?
    ) : CodexOutcome()
}

data class CodexConfig(
    val apiKey: String,
    val model: String = "gpt-5-codex",
    val apiBaseUrl: String = "https://api.openai.com/v1",
    val temperature: Double = 0.1,
    val maxOutputTokens: Int = 1200,
    val connectTimeoutSeconds: Int = 20,
    val readTimeoutSeconds: Int = 90,
    val writeTimeoutSeconds: Int = 20
) {
    companion object {
        private const val MODEL_ENV = "CONFLICTCOURT_CODEX_MODEL"

        fun fromEnvironment(): CodexConfig? {
            val apiKey = Config.openAiKey
            if (apiKey.isBlank()) return null

            val model = System.getenv(MODEL_ENV)?.trim().orEmpty()
            return if (model.isBlank()) CodexConfig(apiKey = apiKey) else CodexConfig(apiKey = apiKey, model = model)
        }
    }
}

private suspend fun OkHttpClient.await(request: Request): Response = suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        }
    )
}
