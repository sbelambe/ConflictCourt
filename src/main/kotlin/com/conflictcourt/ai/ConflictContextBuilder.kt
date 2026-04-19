package com.conflictcourt.ai

import com.conflictcourt.conflicts.ConflictBlock
import com.intellij.openapi.project.Project
import java.io.File
import java.time.Instant

object ConflictContextBuilder {
    fun fromConflictBlock(
        project: Project,
        filePath: String?,
        documentText: String,
        block: ConflictBlock
    ): ConflictContext {
        val lines = documentText.lines()
        val fileName = filePath?.substringAfterLast('/') ?: "unknown"
        val extension = fileName.substringAfterLast('.', "").lowercase()

        val aboveStart = maxOf(0, block.startLine - 1 - 25)
        val aboveEnd = maxOf(0, block.startLine - 1)
        val belowStart = minOf(lines.size, block.endLine)
        val belowEnd = minOf(lines.size, block.endLine + 25)

        val gitCurrentBranch = git(project, "rev-parse", "--abbrev-ref", "HEAD")
        val gitHeadMsg = git(project, "log", "-1", "--pretty=%s", "HEAD")
        val gitIncomingMsg = git(project, "log", "-1", "--pretty=%s", block.incomingLabel)
            ?: block.incomingLabel

        return ConflictContext(
            filePath = filePath ?: "unknown",
            language = detectLanguage(extension),
            indentationStyle = detectIndentation(lines),
            conflictBlocks = listOf(
                ConflictBlockContext(
                    startLine = block.startLine,
                    separatorLine = block.separatorLine,
                    endLine = block.endLine,
                    currentLabel = block.currentLabel,
                    incomingLabel = block.incomingLabel,
                    currentText = block.currentText,
                    incomingText = block.incomingText,
                    contextAbove = lines.subList(aboveStart, aboveEnd),
                    contextBelow = lines.subList(belowStart, belowEnd)
                )
            ),
            gitHistory = GitHistoryContext(
                currentBranch = gitCurrentBranch,
                incomingBranch = block.incomingLabel,
                currentBranchLastCommitMessage = gitHeadMsg ?: "Unknown",
                incomingBranchLastCommitMessage = gitIncomingMsg
            ),
            externalLogic = ExternalLogicContext(
                functionAndClassSignatures = listOfNotNull(detectParentSignature(lines, block.startLine - 1, extension)),
                imports = detectImports(lines, extension)
            ),
            capturedAtIsoUtc = Instant.now().toString()
        )
    }

    private fun detectLanguage(ext: String): String {
        return when (ext) {
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

    private fun detectIndentation(lines: List<String>): String {
        val indentedLine = lines.firstOrNull { it.startsWith("    ") || it.startsWith("\t") || it.startsWith("  ") }
            ?: return "Unknown"
        if (indentedLine.startsWith("\t")) return "Tabs"
        val spaces = indentedLine.takeWhile { it == ' ' }.length
        return "Spaces ($spaces)"
    }

    private fun detectImports(lines: List<String>, ext: String): List<String> {
        return lines.mapNotNull { raw ->
            val line = raw.trim()
            when {
                ext == "py" && (line.startsWith("import ") || line.startsWith("from ")) -> line
                ext in setOf("kt", "java", "ts", "tsx", "js", "jsx", "go") && line.startsWith("import ") -> line
                else -> null
            }
        }
    }

    private fun detectParentSignature(lines: List<String>, startIndex: Int, ext: String): String? {
        for (index in startIndex downTo 0) {
            val line = lines[index].trim()
            val match = when (ext) {
                "py" -> line.startsWith("def ") || line.startsWith("class ")
                "kt" -> line.startsWith("fun ") || line.startsWith("class ") || line.startsWith("object ")
                "java" -> line.startsWith("class ") || line.matches(Regex(".*\\)\\s*\\{?$"))
                "ts", "tsx", "js", "jsx" -> line.startsWith("function ") || line.startsWith("class ") || line.contains("=>")
                else -> line.startsWith("class ") || line.startsWith("fun ") || line.startsWith("def ")
            }
            if (match) return line
        }
        return null
    }

    private fun git(project: Project, vararg args: String): String? {
        val root = project.basePath?.let(::File) ?: return null
        return runCatching {
            val process = ProcessBuilder(listOf("git", *args))
                .directory(root)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exit = process.waitFor()
            if (exit == 0) output.ifBlank { null } else null
        }.getOrNull()
    }
}
