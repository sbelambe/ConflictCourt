package com.conflictcourt.git

data class MergePreviewConflict(
    val filePath: String,
    val fileName: String,
    val language: String,
    val indentation: String,
    val importsList: List<String>,
    val parentSignature: String?,
    val commitMsgHead: String?,
    val commitMsgIncoming: String?,
    val codeHead: String,
    val codeIncoming: String,
    val bufferAbove: String,
    val bufferBelow: String
)

sealed class MergePreviewOutcome(val message: String) {
    class Success(message: String, val conflicts: List<MergePreviewConflict>) : MergePreviewOutcome(message)
    class Failure(message: String) : MergePreviewOutcome(message)
    class Skipped(message: String) : MergePreviewOutcome(message)
}

