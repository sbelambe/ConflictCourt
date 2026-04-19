package com.conflictcourt.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object TestRunner {
    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        val inputPath = findInputJson()
        if (inputPath == null) {
            println("[ERROR]: input.json not found. Expected at project root.")
            return@runBlocking
        }

        val rawJson = Files.readString(inputPath, StandardCharsets.UTF_8)
        val json = Json { ignoreUnknownKeys = true }
        val context = try {
            json.decodeFromString(ConflictContext.serializer(), rawJson)
        } catch (e: Exception) {
            println("[ERROR]: Failed to parse input.json: ${e.message}")
            return@runBlocking
        }

        println("[INPUT CHECK]: file_path=${context.filePath}, language=${context.language}, indentation=${context.indentationStyle}, conflicts=${context.conflictBlocks.size}")
        val firstBlock = context.conflictBlocks.firstOrNull()
        if (firstBlock != null) {
            println("[INPUT CHECK]: current_label=${firstBlock.currentLabel}, incoming_label=${firstBlock.incomingLabel}")
            println("[INPUT CHECK]: code_head=\n${firstBlock.currentText}")
            println("[INPUT CHECK]: code_incoming=\n${firstBlock.incomingText}")
        }

        val pipeline = ConflictCourtAiFactory.createPipelineFromEnvironment()
        if (pipeline == null) {
            println("[ERROR]: Could not initialize pipeline.")
            println("[ERROR]: ${Config.loadError ?: "Missing Codex/Supabase configuration."}")
            return@runBlocking
        }

        println("[AI THINKING]: Sending conflict context to Codex...")
        when (val result = pipeline.resolve(context)) {
            is PipelineOutcome.CodexFailure -> {
                println("[ERROR]: Codex call failed: ${result.failure.message}")
                println("[ERROR]: status=${result.failure.statusCode}, raw=${result.failure.rawResponse}")
            }

            is PipelineOutcome.Success -> {
                println("[THE RESOLUTION]:")
                println(result.resolution.mergedCode)
                println("[THE WHY]:")
                println(result.resolution.inferredIntent)

                val audit = result.auditOutcome
                val auditStatus = when (audit) {
                    null -> "Audit logger not configured (Supabase env vars missing), so no audit attempt."
                    is AuditLogOutcome.Success -> "Success: ${audit.message}"
                    is AuditLogOutcome.Failure -> "Failure: status=${audit.statusCode}, message=${audit.failureMessage}, raw=${audit.rawResponse}"
                }
                println("[AUDIT STATUS]: $auditStatus")
            }
        }
    }

    private fun findInputJson(): Path? {
        var current: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (current != null) {
            val candidate = current.resolve("input.json")
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate
            }
            current = current.parent
        }
        return null
    }
}
