package com.conflictcourt.conflicts

class ConflictScanner {
    fun scan(text: String, filePath: String?): ConflictScanResult {
        val blocks = mutableListOf<ConflictBlock>()
        val currentLines = mutableListOf<String>()
        val incomingLines = mutableListOf<String>()
        var startLine: Int? = null
        var separatorLine: Int? = null
        var currentLabel = "HEAD"
        var incomingLabel: String
        var inIncomingSection = false

        text.lineSequence().forEachIndexed { index, line ->
            val lineNumber = index + 1

            when {
                line.startsWith("<<<<<<<") -> {
                    startLine = lineNumber
                    separatorLine = null
                    currentLabel = line.removePrefix("<<<<<<<").trim().ifBlank { "HEAD" }
                    incomingLabel = "incoming"
                    currentLines.clear()
                    incomingLines.clear()
                    inIncomingSection = false
                }

                line == "=======" && startLine != null -> {
                    separatorLine = lineNumber
                    inIncomingSection = true
                }

                line.startsWith(">>>>>>>") && startLine != null && separatorLine != null -> {
                    incomingLabel = line.removePrefix(">>>>>>>").trim().ifBlank { "incoming" }
                    blocks += ConflictBlock(
                        startLine = startLine!!,
                        separatorLine = separatorLine!!,
                        endLine = lineNumber,
                        currentLabel = currentLabel,
                        incomingLabel = incomingLabel,
                        currentText = currentLines.joinToString("\n"),
                        incomingText = incomingLines.joinToString("\n")
                    )
                    startLine = null
                    separatorLine = null
                    currentLines.clear()
                    incomingLines.clear()
                    inIncomingSection = false
                }

                startLine != null && separatorLine == null -> {
                    currentLines += line
                }

                startLine != null && separatorLine != null && !line.startsWith(">>>>>>>") -> {
                    if (inIncomingSection) {
                        incomingLines += line
                    }
                }
            }
        }

        return ConflictScanResult(filePath = filePath, blocks = blocks)
    }
}
