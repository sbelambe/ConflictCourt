package com.conflictcourt.conflicts

data class ConflictBlock(
    val startLine: Int,
    val separatorLine: Int,
    val endLine: Int,
    val currentLabel: String,
    val incomingLabel: String,
    val currentText: String,
    val incomingText: String
)

data class ConflictScanResult(
    val filePath: String?,
    val blocks: List<ConflictBlock>,
    val uploadStatus: String = "Waiting for scan"
) {
    val hasConflicts: Boolean
        get() = blocks.isNotEmpty()

    companion object {
        fun empty(filePath: String? = null): ConflictScanResult =
            ConflictScanResult(filePath = filePath, blocks = emptyList(), uploadStatus = "No active scan yet")
    }
}
