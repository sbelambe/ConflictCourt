package com.conflictcourt.ai

import kotlinx.serialization.Serializable

@Serializable
data class ConflictContext(
    val filePath: String,
    val language: String,
    val indentationStyle: String,
    val conflictBlocks: List<ConflictBlockContext>,
    val gitHistory: GitHistoryContext,
    val externalLogic: ExternalLogicContext,
    val capturedAtIsoUtc: String
)

@Serializable
data class ConflictBlockContext(
    val startLine: Int,
    val separatorLine: Int,
    val endLine: Int,
    val currentLabel: String,
    val incomingLabel: String,
    val currentText: String,
    val incomingText: String,
    val contextAbove: List<String>,
    val contextBelow: List<String>
)

@Serializable
data class GitHistoryContext(
    val currentBranch: String? = null,
    val incomingBranch: String? = null,
    val currentBranchLastCommitMessage: String,
    val incomingBranchLastCommitMessage: String
)

@Serializable
data class ExternalLogicContext(
    val functionAndClassSignatures: List<String>,
    val imports: List<String>
)
