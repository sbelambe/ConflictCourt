package com.conflictcourt.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.system.exitProcess

fun main(): Unit = runBlocking {
    val fixturesDir = File("testdata/ai-cases")
    println("[Batch] Fixture directory: ${fixturesDir.absolutePath}")
    if (!fixturesDir.exists() || !fixturesDir.isDirectory) {
        println("[ERROR] Fixture directory not found.")
        exitProcess(1)
    }

    if (Config.openAiKey.isBlank()) {
        println("[ERROR] OPENAI_API_KEY missing. ${Config.loadError ?: ""}".trim())
        exitProcess(1)
    }

    val pipeline = ConflictCourtAiFactory.createPipelineFromEnvironment()
    if (pipeline == null) {
        println("[ERROR] Failed to initialize ConflictResolutionPipeline. ${Config.loadError ?: ""}".trim())
        exitProcess(1)
    }

    val manifest = loadManifest(fixturesDir)
    val expectations = manifest?.cases?.associateBy { it.fileName }.orEmpty()
    val caseFiles = fixturesDir
        .listFiles { file -> file.isFile && file.extension.lowercase() == "json" && file.name != MANIFEST_FILE }
        ?.sortedBy { it.name }
        .orEmpty()

    if (caseFiles.isEmpty()) {
        println("[ERROR] No JSON fixtures found in ${fixturesDir.absolutePath}")
        exitProcess(1)
    }

    var passed = 0
    var failed = 0
    var apiCalls = 0

    for (file in caseFiles) {
        val expected = expectations[file.name] ?: CaseExpectation(fileName = file.name)
        val raw = try {
            file.readText()
        } catch (err: Exception) {
            println("[FAIL] ${file.name}: read error: ${err.message}")
            failed++
            continue
        }

        val contexts = ConflictContextInputParser.parseAll(raw)
        if (contexts.isEmpty()) {
            if (expected.expectParseSuccess) {
                println("[FAIL] ${file.name}: parse returned 0 contexts")
                failed++
            } else {
                println("[PASS] ${file.name}: parse failure expected and observed")
                passed++
            }
            continue
        }

        if (!expected.expectParseSuccess) {
            println("[FAIL] ${file.name}: expected parse failure but parsed ${contexts.size} context(s)")
            failed++
            continue
        }

        var caseFailed = false
        println("[CASE] ${file.name}: parsed ${contexts.size} context(s)")

        for ((index, context) in contexts.withIndex()) {
            apiCalls++
            val outcome = resolveWithRetries(pipeline, context, maxAttempts = 3)
            when (outcome) {
                is PipelineOutcome.CodexFailure -> {
                    if (expected.expectAiSuccess) {
                        println(
                            "[FAIL] ${file.name} [${index + 1}/${contexts.size}]: " +
                                "Codex failure status=${outcome.failure.statusCode} message=${outcome.failure.message}"
                        )
                        if (!outcome.failure.rawResponse.isNullOrBlank()) {
                            println("  raw: ${truncate(outcome.failure.rawResponse)}")
                        }
                        caseFailed = true
                    } else {
                        println("[PASS] ${file.name} [${index + 1}/${contexts.size}]: Codex failure expected")
                    }
                }

                is PipelineOutcome.Success -> {
                    val errors = validateResolution(outcome.resolution)
                    if (errors.isNotEmpty()) {
                        println("[FAIL] ${file.name} [${index + 1}/${contexts.size}]: ${errors.joinToString("; ")}")
                        caseFailed = true
                    } else {
                        println("[PASS] ${file.name} [${index + 1}/${contexts.size}]")
                        println("  merged_code: ${truncate(outcome.resolution.mergedCode)}")
                        println("  inferred_intent: ${truncate(outcome.resolution.inferredIntent)}")
                    }
                }
            }
        }

        if (caseFailed) failed++ else passed++
    }

    println(
        """
        [Batch Summary]
        cases_passed=$passed
        cases_failed=$failed
        api_calls=$apiCalls
        """.trimIndent()
    )

    exitProcess(if (failed == 0) 0 else 1)
}

private suspend fun resolveWithRetries(
    pipeline: ConflictResolutionPipeline,
    context: ConflictContext,
    maxAttempts: Int
): PipelineOutcome {
    var attempt = 1
    var last: PipelineOutcome = pipeline.resolve(context)
    while (attempt < maxAttempts) {
        val failure = (last as? PipelineOutcome.CodexFailure)?.failure ?: break
        val msg = failure.message.lowercase()
        val retryable = "timeout" in msg || "no textual model output" in msg
        if (!retryable) break
        attempt++
        println("[Retry] transient failure, retrying attempt $attempt/$maxAttempts")
        last = pipeline.resolve(context)
    }
    return last
}

private fun validateResolution(resolution: ConflictResolution): List<String> {
    val errors = mutableListOf<String>()
    if (resolution.mergedCode.isBlank()) errors += "merged_code is blank"
    if (resolution.inferredIntent.isBlank()) errors += "inferred_intent is blank"
    if (resolution.confidenceScore !in 0.0..1.0) errors += "confidence_score out of range"

    val merged = resolution.mergedCode
    if ("<<<<<<<" in merged || "=======" in merged || ">>>>>>>" in merged) {
        errors += "merged_code still contains conflict markers"
    }

    return errors
}

private fun truncate(value: String, limit: Int = 120): String {
    val compact = value.replace('\n', ' ').trim()
    if (compact.length <= limit) return compact
    return compact.take(limit - 3) + "..."
}

private fun loadManifest(fixturesDir: File): BatchManifest? {
    val manifestFile = File(fixturesDir, MANIFEST_FILE)
    if (!manifestFile.exists()) return null
    val json = Json { ignoreUnknownKeys = true }
    return runCatching {
        json.decodeFromString(BatchManifest.serializer(), manifestFile.readText())
    }.getOrNull()
}

private const val MANIFEST_FILE = "cases_manifest.json"

@Serializable
private data class BatchManifest(
    val cases: List<CaseExpectation> = emptyList()
)

@Serializable
private data class CaseExpectation(
    val fileName: String,
    val expectParseSuccess: Boolean = true,
    val expectAiSuccess: Boolean = true
)
