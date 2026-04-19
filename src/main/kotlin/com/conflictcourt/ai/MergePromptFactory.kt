package com.conflictcourt.ai

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object MergePromptFactory {
    private val json = Json { prettyPrint = true }

    // Strict output contract for machine-safe parsing.
    const val SYSTEM_PROMPT: String = """
You are ConflictCourt Arbitrator, an expert merge-conflict resolver.
Resolve conflicts based on developer intent, commit history, and surrounding code context.

Return ONLY a valid JSON object with this exact shape:
{
  "merged_code": "string",
  "inferred_intent": "string",
  "confidence_score": 0.0
}

Rules:
1) Output must be raw JSON only, no markdown, no backticks, no explanation.
2) merged_code must contain only the resolved code for the conflicted region(s).
3) inferred_intent must be concise and concrete.
4) confidence_score must be a number between 0 and 1.
5) Preserve formatting and indentation conventions from the file context.
6) If both sides are needed, combine them coherently instead of choosing one blindly.
"""

    fun createUserPrompt(context: ConflictContext): String {
        return buildString {
            appendLine("Resolve this merge conflict context package.")
            appendLine("Prefer semantic correctness and intent alignment over textual majority.")
            appendLine()
            appendLine("CONTEXT_PACKAGE_JSON:")
            appendLine(json.encodeToString(context))
        }
    }
}
