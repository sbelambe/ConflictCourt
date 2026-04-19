package com.conflictcourt.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

object ConflictContextInputParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseFirst(rawJson: String): ConflictContext? = parseAll(rawJson).firstOrNull()

    fun parseAll(rawJson: String): List<ConflictContext> {
        val directContext = runCatching { json.decodeFromString(ConflictContext.serializer(), rawJson) }.getOrNull()
        if (directContext != null) return listOf(directContext)

        val root = runCatching { json.parseToJsonElement(rawJson) }.getOrNull() ?: return emptyList()

        return when (root) {
            is JsonObject -> parseObjectRoot(root)
            is JsonArray -> parseArrayRoot(root)
            else -> emptyList()
        }
    }

    private fun parseObjectRoot(root: JsonObject): List<ConflictContext> {
        val conflicts = root["conflicts"]?.jsonArray
        if (conflicts != null) {
            val targetBranch = root["target_branch"]?.jsonPrimitive?.contentOrNull
            return conflicts.mapNotNull { element ->
                val parsed = runCatching {
                    json.decodeFromJsonElement(FlatConflictInput.serializer(), element)
                }.getOrNull() ?: return@mapNotNull null
                toConflictContext(parsed, targetBranch)
            }
        }

        val parsedFlat = runCatching { json.decodeFromJsonElement(FlatConflictInput.serializer(), root) }.getOrNull()
        if (parsedFlat != null && parsedFlat.looksLikeConflict()) {
            return listOf(toConflictContext(parsedFlat, incomingBranch = null))
        }

        val parsedContext = runCatching { json.decodeFromJsonElement(ConflictContext.serializer(), root) }.getOrNull()
        return parsedContext?.let(::listOf).orEmpty()
    }

    private fun parseArrayRoot(root: JsonArray): List<ConflictContext> {
        return root.mapNotNull { element ->
            val asContext = runCatching {
                json.decodeFromJsonElement(ConflictContext.serializer(), element)
            }.getOrNull()
            if (asContext != null) return@mapNotNull asContext

            val asFlat = runCatching {
                json.decodeFromJsonElement(FlatConflictInput.serializer(), element)
            }.getOrNull() ?: return@mapNotNull null
            if (!asFlat.looksLikeConflict()) return@mapNotNull null
            toConflictContext(asFlat, incomingBranch = null)
        }
    }

    private fun toConflictContext(flat: FlatConflictInput, incomingBranch: String?): ConflictContext {
        return ConflictContext(
            filePath = flat.filePath.ifBlank { "unknown" },
            language = flat.language.ifBlank { "Unknown" },
            indentationStyle = flat.indentation.ifBlank { "Unknown" },
            conflictBlocks = listOf(
                ConflictBlockContext(
                    startLine = 1,
                    separatorLine = 2,
                    endLine = 3,
                    currentLabel = "HEAD",
                    incomingLabel = incomingBranch ?: "incoming",
                    currentText = flat.codeHead,
                    incomingText = flat.codeIncoming,
                    contextAbove = splitLines(flat.bufferAbove),
                    contextBelow = splitLines(flat.bufferBelow)
                )
            ),
            gitHistory = GitHistoryContext(
                currentBranch = "HEAD",
                incomingBranch = incomingBranch,
                currentBranchLastCommitMessage = flat.commitMsgHead,
                incomingBranchLastCommitMessage = flat.commitMsgIncoming
            ),
            externalLogic = ExternalLogicContext(
                functionAndClassSignatures = listOfNotNull(flat.parentSignature.takeIf { it.isNotBlank() }),
                imports = flat.importsList
            ),
            capturedAtIsoUtc = Instant.now().toString()
        )
    }

    private fun splitLines(value: String): List<String> {
        if (value.isBlank()) return emptyList()
        return value.lines().filter { it.isNotBlank() }
    }
}

private fun FlatConflictInput.looksLikeConflict(): Boolean {
    return codeHead.isNotBlank() || codeIncoming.isNotBlank() || filePath.isNotBlank()
}

@Serializable
private data class FlatConflictInput(
    @SerialName("file_path")
    val filePath: String = "",
    @SerialName("language")
    val language: String = "",
    @SerialName("indentation")
    val indentation: String = "",
    @SerialName("code_head")
    val codeHead: String = "",
    @SerialName("code_incoming")
    val codeIncoming: String = "",
    @SerialName("buffer_above")
    val bufferAbove: String = "",
    @SerialName("buffer_below")
    val bufferBelow: String = "",
    @SerialName("imports_list")
    val importsList: List<String> = emptyList(),
    @SerialName("parent_signature")
    val parentSignature: String = "",
    @SerialName("commit_msg_head")
    val commitMsgHead: String = "",
    @SerialName("commit_msg_incoming")
    val commitMsgIncoming: String = ""
)
