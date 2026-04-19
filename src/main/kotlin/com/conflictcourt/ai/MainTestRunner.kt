package com.conflictcourt.ai

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>): Unit = runBlocking {
    val inputFile = File("input.json")
    println("[Input Path] ${inputFile.absolutePath}")
    if (!inputFile.exists() || !inputFile.isFile) {
        println("[ERROR] input.json not found at the path above.")
        exitProcess(1)
    }

    val rawJson = runCatching { inputFile.readText() }.getOrElse { err ->
        println("[ERROR] Failed to read input.json: ${err.message}")
        exitProcess(1)
    }

    val context = ConflictContextInputParser.parseFirst(rawJson)
    if (context == null) {
        println("[ERROR] Could not parse input.json as ConflictContext or conflict list payload.")
        exitProcess(1)
    }

    val firstBlock = context.conflictBlocks.firstOrNull()
    if (firstBlock == null) {
        println("[ERROR] Parsed context has no conflict blocks.")
        exitProcess(1)
    }

    println("[Step 1: JSON Data]")
    println("code_head:")
    println(firstBlock.currentText)
    println("code_incoming:")
    println(firstBlock.incomingText)

    if (Config.openAiKey.isBlank()) {
        println("[ERROR] OPENAI_API_KEY missing. ${Config.loadError ?: ""}".trim())
        exitProcess(1)
    }

    val pipeline = ConflictCourtAiFactory.createPipelineFromEnvironment()
    if (pipeline == null) {
        println("[ERROR] Failed to initialize ConflictResolutionPipeline. ${Config.loadError ?: ""}".trim())
        exitProcess(1)
    }

    println("[Step 2: AI Request]")
    println("Sending to Codex...")

    when (val result = pipeline.resolve(context)) {
        is PipelineOutcome.CodexFailure -> {
            println("[ERROR] Codex request failed: ${result.failure.message}")
            println("[ERROR] status=${result.failure.statusCode}, raw=${result.failure.rawResponse}")
            exitProcess(1)
        }
        is PipelineOutcome.Success -> {
            println("[Step 3: The Result]")
            println(result.resolution.mergedCode)
            println("[Step 4: The Logic]")
            println(result.resolution.inferredIntent)
            exitProcess(0)
        }
    }
}
