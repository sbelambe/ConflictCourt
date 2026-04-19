package com.conflictcourt.supabase

import com.conflictcourt.conflicts.ConflictScanResult
import com.conflictcourt.git.MergePreviewConflict
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

data class MergeLogsConfig(
    val url: String,
    val apiKey: String,
    val tableName: String = "merge_logs",
    val developerName: String
)

class MergeLogsUploader(
    private val project: Project,
    private val config: MergeLogsConfig,
    private val httpClient: HttpClient = HttpClient.newHttpClient()
) {
    fun upload(result: ConflictScanResult, sessionId: String): UploadOutcome {
        if (!result.hasConflicts || result.filePath == null) {
            return UploadOutcome.Skipped("No conflicts to upload")
        }

        val language = detectLanguage(result.filePath)
        val payloadRows = result.blocks.map { block ->
            MergeLogRow(
                filePath = result.filePath,
                language = language,
                codeHead = block.currentText,
                codeIncoming = block.incomingText
            )
        }
        val payload = buildPayload(payloadRows, sessionId)
        return uploadPayload(payload, payloadRows.size)
    }

    fun uploadMergePreview(conflicts: List<MergePreviewConflict>, sessionId: String): UploadOutcome {
        if (conflicts.isEmpty()) {
            return UploadOutcome.Skipped("No merge-preview conflicts to upload")
        }

        val payloadRows = conflicts.map { conflict ->
            MergeLogRow(
                filePath = conflict.filePath,
                language = conflict.language.ifBlank { detectLanguage(conflict.filePath) },
                codeHead = conflict.codeHead,
                codeIncoming = conflict.codeIncoming
            )
        }
        val payload = buildPayload(payloadRows, sessionId)
        return uploadPayload(payload, payloadRows.size)
    }

    private fun uploadPayload(payload: String, rowCount: Int): UploadOutcome {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${config.url}/rest/v1/${config.tableName}"))
            .header("Content-Type", "application/json")
            .header("apikey", config.apiKey)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("Prefer", "return=minimal")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        return if (response.statusCode() in 200..299) {
            UploadOutcome.Success("Uploaded $rowCount row(s) to ${config.tableName}")
        } else {
            val details = response.body().trim().ifBlank { "no response body" }.take(300)
            logger.warn("merge_logs upload failed: status=${response.statusCode()}, body=$details")
            UploadOutcome.Failure("Supabase merge_logs upload failed (${response.statusCode()}): $details")
        }
    }

    private fun buildPayload(rows: List<MergeLogRow>, sessionId: String): String {
        val commitSha = gitHeadSha(project.basePath)
        val projectName = project.name
        val now = Instant.now().toString()

        return rows.joinToString(prefix = "[", postfix = "]") { row ->
            """
            {
              "file_path": ${jsonString(row.filePath)},
              "developer_name": ${jsonString(config.developerName)},
              "code_head": ${jsonString(row.codeHead)},
              "code_incoming": ${jsonString(row.codeIncoming)},
              "ai_resolution": null,
              "intent_summary": null,
              "status": ${jsonString("pending")},
              "session_id": ${jsonString(sessionId)},
              "project_name": ${jsonString(projectName)},
              "commit_sha": ${jsonString(commitSha)},
              "is_flagged": false,
              "programming_language": ${jsonString(row.language)},
              "created_at": ${jsonString(now)}
            }
            """.trimIndent()
        }
    }

    private fun detectLanguage(filePath: String): String {
        return when (filePath.substringAfterLast('.', "").lowercase()) {
            "kt" -> "Kotlin"
            "java" -> "Java"
            "py" -> "Python"
            "ts" -> "TypeScript"
            "tsx" -> "TypeScript React"
            "js" -> "JavaScript"
            "jsx" -> "JavaScript React"
            "go" -> "Go"
            "rb" -> "Ruby"
            "rs" -> "Rust"
            "swift" -> "Swift"
            "cpp", "cc", "cxx" -> "C++"
            "c" -> "C"
            else -> "Unknown"
        }
    }

    private fun gitHeadSha(projectBasePath: String?): String? {
        if (projectBasePath.isNullOrBlank()) return null
        return try {
            val process = ProcessBuilder("git", "rev-parse", "HEAD")
                .directory(File(projectBasePath))
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) output else null
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
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

    companion object {
        private val logger = Logger.getInstance(MergeLogsUploader::class.java)
        private const val URL_KEY = "CONFLICTCOURT_SUPABASE_URL"
        private const val API_KEY = "CONFLICTCOURT_SUPABASE_KEY"
        private const val DEV_KEY = "CONFLICTCOURT_DEVELOPER_NAME"

        fun fromProject(project: Project): MergeLogsUploader? {
            val env = readDotEnv(project.basePath)
            val url = resolve(URL_KEY, env)?.removeSuffix("/")
            val key = resolve(API_KEY, env)
            if (url.isNullOrBlank() || key.isNullOrBlank()) return null

            val developer = resolve(DEV_KEY, env).orEmpty().ifBlank {
                System.getProperty("user.name").orEmpty().ifBlank { "unknown" }
            }

            return MergeLogsUploader(
                project = project,
                config = MergeLogsConfig(url = url, apiKey = key, developerName = developer)
            )
        }

        private fun resolve(key: String, env: Map<String, String>): String? {
            return System.getenv(key)?.trim()?.takeIf { it.isNotBlank() }
                ?: env[key]?.trim()?.takeIf { it.isNotBlank() }
        }

        private fun readDotEnv(projectBasePath: String?): Map<String, String> {
            if (projectBasePath.isNullOrBlank()) return emptyMap()
            val envFile = File(projectBasePath, ".env")
            if (!envFile.exists() || !envFile.isFile) return emptyMap()

            return buildMap {
                envFile.forEachLine { raw ->
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                    val idx = line.indexOf('=')
                    if (idx <= 0) return@forEachLine
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim().trim('"', '\'')
                    if (key.isNotEmpty()) put(key, value)
                }
            }
        }
    }
}

private data class MergeLogRow(
    val filePath: String,
    val language: String,
    val codeHead: String,
    val codeIncoming: String
)
