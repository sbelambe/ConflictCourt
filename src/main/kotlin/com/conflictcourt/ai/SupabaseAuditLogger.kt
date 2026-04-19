package com.conflictcourt.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

class SupabaseAuditLogger(
    private val config: SupabaseAuditConfig,
    private val client: OkHttpClient = OkHttpClient()
) {
    suspend fun logResolution(
        context: ConflictContext,
        codexResolution: ConflictResolution,
        codexModel: String,
        codexRequestId: String?,
        codexRawResponse: String?
    ): AuditLogOutcome = withContext(Dispatchers.IO) {
        val url = "${config.url}/rest/v1/${config.tableName}"
        val payload = buildPayload(context, codexResolution, codexModel, codexRequestId, codexRawResponse)

        val request = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .header("apikey", config.apiKey)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Prefer", "return=minimal")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    AuditLogOutcome.Success("Audit log inserted into ${config.tableName}")
                } else {
                    AuditLogOutcome.Failure(
                        statusCode = response.code,
                        failureMessage = "Supabase audit log failed",
                        rawResponse = body
                    )
                }
            }
        } catch (e: Exception) {
            AuditLogOutcome.Failure(
                statusCode = null,
                failureMessage = e.message ?: "Supabase audit error",
                rawResponse = null
            )
        }
    }

    private fun buildPayload(
        context: ConflictContext,
        resolution: ConflictResolution,
        codexModel: String,
        codexRequestId: String?,
        codexRawResponse: String?
    ): String {
        val row = buildJsonObject {
            put("file_path", context.filePath)
            put("language", context.language)
            put("indentation_style", context.indentationStyle)
            put("conflict_blocks_count", context.conflictBlocks.size)
            put("current_branch", context.gitHistory.currentBranch)
            put("incoming_branch", context.gitHistory.incomingBranch)
            put("current_branch_last_commit_message", context.gitHistory.currentBranchLastCommitMessage)
            put("incoming_branch_last_commit_message", context.gitHistory.incomingBranchLastCommitMessage)
            put(
                "function_and_class_signatures",
                buildJsonArray { context.externalLogic.functionAndClassSignatures.forEach { add(JsonPrimitive(it)) } }
            )
            put(
                "imports",
                buildJsonArray { context.externalLogic.imports.forEach { add(JsonPrimitive(it)) } }
            )
            put("merged_code", resolution.mergedCode)
            put("inferred_intent", resolution.inferredIntent)
            put("confidence_score", resolution.confidenceScore)
            put("codex_model", codexModel)
            put("codex_request_id", codexRequestId)
            put("codex_raw_response", codexRawResponse)
            put("captured_at_iso_utc", context.capturedAtIsoUtc)
            put("resolved_at_iso_utc", Instant.now().toString())
        }

        return buildJsonArray { add(row) }.toString()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

data class SupabaseAuditConfig(
    val url: String,
    val apiKey: String,
    val tableName: String = "conflict_resolutions"
) {
    companion object {
        private const val URL_ENV = "CONFLICTCOURT_SUPABASE_URL"
        private const val KEY_ENV = "CONFLICTCOURT_SUPABASE_KEY"
        private const val TABLE_ENV = "CONFLICTCOURT_SUPABASE_AUDIT_TABLE"

        fun fromEnvironment(): SupabaseAuditConfig? {
            val url = System.getenv(URL_ENV)?.trim().orEmpty().removeSuffix("/")
            val apiKey = System.getenv(KEY_ENV)?.trim().orEmpty()
            val tableName = System.getenv(TABLE_ENV)?.trim().orEmpty()

            if (url.isBlank() || apiKey.isBlank()) return null
            return if (tableName.isBlank()) {
                SupabaseAuditConfig(url = url, apiKey = apiKey)
            } else {
                SupabaseAuditConfig(url = url, apiKey = apiKey, tableName = tableName)
            }
        }
    }
}

sealed class AuditLogOutcome(val message: String) {
    class Success(message: String) : AuditLogOutcome(message)
    data class Failure(
        val statusCode: Int?,
        val failureMessage: String,
        val rawResponse: String?
    ) : AuditLogOutcome(failureMessage)
}
