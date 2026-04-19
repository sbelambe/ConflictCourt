package com.conflictcourt.supabase

import com.conflictcourt.conflicts.ConflictScanResult
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

class SupabaseConflictUploader(
    private val config: SupabaseConfig,
    private val httpClient: HttpClient = HttpClient.newHttpClient()
) {
    fun upload(result: ConflictScanResult): UploadOutcome {
        if (!result.hasConflicts || result.filePath == null) {
            return UploadOutcome.Skipped("No conflicts to upload")
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.url}/rest/v1/conflicts"))
            .header("Content-Type", "application/json")
            .header("apikey", config.apiKey)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Prefer", "return=minimal")
            .POST(HttpRequest.BodyPublishers.ofString(buildPayload(result)))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() in 200..299) {
            UploadOutcome.Success("Uploaded ${result.blocks.size} conflict block(s) to Supabase")
        } else {
            UploadOutcome.Failure("Supabase upload failed (${response.statusCode()})")
        }
    }

    private fun buildPayload(result: ConflictScanResult): String {
        return result.blocks.joinToString(prefix = "[", postfix = "]") { block ->
            """
            {
              "file_path": ${jsonString(result.filePath)},
              "start_line": ${block.startLine},
              "separator_line": ${block.separatorLine},
              "end_line": ${block.endLine},
              "current_label": ${jsonString(block.currentLabel)},
              "incoming_label": ${jsonString(block.incomingLabel)},
              "current_text": ${jsonString(block.currentText)},
              "incoming_text": ${jsonString(block.incomingText)},
              "detected_at": ${jsonString(Instant.now().toString())}
            }
            """.trimIndent()
        }
    }

    private fun jsonString(value: String?): String {
        if (value == null) {
            return "null"
        }

        val escaped = buildString {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }

        return "\"$escaped\""
    }
}

sealed class UploadOutcome(val message: String) {
    class Success(message: String) : UploadOutcome(message)
    class Failure(message: String) : UploadOutcome(message)
    class Skipped(message: String) : UploadOutcome(message)
}
