package com.conflictcourt.export

import com.conflictcourt.conflicts.ConflictBlock
import com.conflictcourt.conflicts.ConflictScanResult
import com.conflictcourt.git.MergePreviewConflict
import com.intellij.openapi.project.Project
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.format.DateTimeFormatter

class ConflictJsonExporter(private val project: Project) {
    fun export(result: ConflictScanResult): ExportOutcome {
        val projectBasePath = project.basePath ?: return ExportOutcome.Failure("Project base path not found")
        val filePath = result.filePath ?: return ExportOutcome.Failure("Active file path not found")
        if (!result.hasConflicts) {
            return ExportOutcome.Skipped("No conflicts to export")
        }

        val exportPath = buildExportPath(Path.of(projectBasePath), Path.of(filePath))
        Files.writeString(exportPath, buildJson(result), StandardCharsets.UTF_8)
        return ExportOutcome.Success("Exported conflict JSON to ${exportPath.toAbsolutePath()}")
    }

    fun exportMergePreview(targetBranch: String, conflicts: List<MergePreviewConflict>): ExportOutcome {
        val projectBasePath = project.basePath ?: return ExportOutcome.Failure("Project base path not found")
        if (conflicts.isEmpty()) {
            return ExportOutcome.Skipped("No merge-preview conflicts to export")
        }

        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").format(java.time.LocalDateTime.now())
        val sanitizedTarget = targetBranch.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val exportPath = Path.of(projectBasePath).resolve("conflictcourt_merge_preview_${sanitizedTarget}_$timestamp.json")
        Files.writeString(exportPath, buildMergePreviewJson(targetBranch, conflicts), StandardCharsets.UTF_8)
        return ExportOutcome.Success("Exported merge-preview JSON to ${exportPath.toAbsolutePath()}")
    }

    fun exportDiffPreview(targetBranch: String, diffs: List<MergePreviewConflict>): ExportOutcome {
        val projectBasePath = project.basePath ?: return ExportOutcome.Failure("Project base path not found")
        if (diffs.isEmpty()) {
            return ExportOutcome.Skipped("No diff-preview entries to export")
        }

        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").format(java.time.LocalDateTime.now())
        val sanitizedTarget = targetBranch.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val exportPath = Path.of(projectBasePath).resolve("conflictcourt_diff_preview_${sanitizedTarget}_$timestamp.json")
        Files.writeString(exportPath, buildDiffPreviewJson(targetBranch, diffs), StandardCharsets.UTF_8)
        return ExportOutcome.Success("Exported diff-preview JSON to ${exportPath.toAbsolutePath()}")
    }

    private fun buildExportPath(projectRoot: Path, conflictFile: Path): Path {
        val timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS").format(java.time.LocalDateTime.now())
        val relativePath = runCatching { projectRoot.relativize(conflictFile).toString() }.getOrDefault(conflictFile.toString())
        val sanitizedPath = relativePath.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val pathHash = relativePath.hashCode().toUInt().toString(16)
        return projectRoot.resolve("conflictcourt_${sanitizedPath}_${pathHash}_$timestamp.json")
    }

    private fun buildJson(result: ConflictScanResult): String {
        val filePath = result.filePath ?: ""
        val lines = Files.readAllLines(Path.of(filePath), StandardCharsets.UTF_8)
        val fileName = Path.of(filePath).fileName.toString()

        val entries = result.blocks.joinToString(",\n") { block ->
            buildEntryJson(block, lines, fileName, filePath)
        }

        return """
        {
          "generated_at": ${jsonString(Instant.now().toString())},
          "file_name": ${jsonString(fileName)},
          "file_path": ${jsonString(filePath)},
          "language": ${jsonString(detectLanguage(fileName))},
          "indentation": ${jsonString(detectIndentation(lines))},
          "imports_list": ${jsonArray(detectImports(lines, fileName))},
          "commit_msg_head": ${jsonString(readGitMessage("HEAD"))},
          "commit_msg_incoming": ${jsonString(readGitMessage(result.blocks.firstOrNull()?.incomingLabel))},
          "conflicts": [
        $entries
          ]
        }
        """.trimIndent()
    }

    private fun buildMergePreviewJson(targetBranch: String, conflicts: List<MergePreviewConflict>): String {
        val entries = conflicts.joinToString(",\n") { conflict ->
            """
            {
              "file_name": ${jsonString(conflict.fileName)},
              "file_path": ${jsonString(conflict.filePath)},
              "language": ${jsonString(conflict.language)},
              "indentation": ${jsonString(conflict.indentation)},
              "code_head": ${jsonString(conflict.codeHead)},
              "code_incoming": ${jsonString(conflict.codeIncoming)},
              "buffer_above": ${jsonString(conflict.bufferAbove)},
              "buffer_below": ${jsonString(conflict.bufferBelow)},
              "imports_list": ${jsonArray(conflict.importsList)},
              "parent_signature": ${jsonString(conflict.parentSignature)},
              "commit_msg_head": ${jsonString(conflict.commitMsgHead)},
              "commit_msg_incoming": ${jsonString(conflict.commitMsgIncoming)}
            }
            """.trimIndent()
        }

        return """
        {
          "generated_at": ${jsonString(Instant.now().toString())},
          "mode": "merge_preview",
          "target_branch": ${jsonString(targetBranch)},
          "conflicts": [
        $entries
          ]
        }
        """.trimIndent()
    }

    private fun buildDiffPreviewJson(targetBranch: String, diffs: List<MergePreviewConflict>): String {
        val entries = diffs.joinToString(",\n") { conflict ->
            """
            {
              "file_name": ${jsonString(conflict.fileName)},
              "file_path": ${jsonString(conflict.filePath)},
              "language": ${jsonString(conflict.language)},
              "indentation": ${jsonString(conflict.indentation)},
              "code_head": ${jsonString(conflict.codeHead)},
              "code_incoming": ${jsonString(conflict.codeIncoming)},
              "buffer_above": ${jsonString(conflict.bufferAbove)},
              "buffer_below": ${jsonString(conflict.bufferBelow)},
              "imports_list": ${jsonArray(conflict.importsList)},
              "parent_signature": ${jsonString(conflict.parentSignature)},
              "commit_msg_head": ${jsonString(conflict.commitMsgHead)},
              "commit_msg_incoming": ${jsonString(conflict.commitMsgIncoming)}
            }
            """.trimIndent()
        }

        return """
        {
          "generated_at": ${jsonString(Instant.now().toString())},
          "mode": "diff_preview",
          "target_branch": ${jsonString(targetBranch)},
          "differences": [
        $entries
          ]
        }
        """.trimIndent()
    }

    private fun buildEntryJson(
        block: ConflictBlock,
        lines: List<String>,
        fileName: String,
        filePath: String
    ): String {
        val aboveStart = maxOf(0, block.startLine - 1 - 25)
        val aboveEnd = maxOf(0, block.startLine - 1)
        val belowStart = minOf(lines.size, block.endLine)
        val belowEnd = minOf(lines.size, block.endLine + 25)

        return """
            {
              "file_name": ${jsonString(fileName)},
              "file_path": ${jsonString(filePath)},
              "language": ${jsonString(detectLanguage(fileName))},
              "indentation": ${jsonString(detectIndentation(lines))},
              "code_head": ${jsonString(block.currentText)},
              "code_incoming": ${jsonString(block.incomingText)},
              "buffer_above": ${jsonString(lines.subList(aboveStart, aboveEnd).joinToString("\n"))},
              "buffer_below": ${jsonString(lines.subList(belowStart, belowEnd).joinToString("\n"))},
              "imports_list": ${jsonArray(detectImports(lines, fileName))},
              "parent_signature": ${jsonString(detectParentSignature(lines, block.startLine - 1, fileName))},
              "commit_msg_head": ${jsonString(readGitMessage("HEAD"))},
              "commit_msg_incoming": ${jsonString(readGitMessage(block.incomingLabel))}
            }
        """.trimIndent()
    }

    private fun detectLanguage(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
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

        return if (indentedLine.startsWith("\t")) {
            "Tabs"
        } else {
            val spaces = indentedLine.takeWhile { it == ' ' }.length
            "Spaces ($spaces)"
        }
    }

    private fun detectImports(lines: List<String>, fileName: String): List<String> {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return lines.mapNotNull { line ->
            val trimmed = line.trim()
            when {
                ext in setOf("py") && (trimmed.startsWith("import ") || trimmed.startsWith("from ")) -> trimmed
                ext in setOf("kt", "java") && trimmed.startsWith("import ") -> trimmed
                ext in setOf("ts", "tsx", "js", "jsx") && trimmed.startsWith("import ") -> trimmed
                ext == "go" && trimmed.startsWith("import ") -> trimmed
                else -> null
            }
        }
    }

    private fun detectParentSignature(lines: List<String>, startIndex: Int, fileName: String): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        for (index in startIndex downTo 0) {
            val trimmed = lines[index].trim()
            val isMatch = when (ext) {
                "py" -> trimmed.startsWith("def ") || trimmed.startsWith("class ")
                "kt" -> trimmed.startsWith("fun ") || trimmed.startsWith("class ") || trimmed.startsWith("object ")
                "java" -> trimmed.startsWith("class ") || trimmed.matches(Regex(".*\\)\\s*\\{?$"))
                "ts", "tsx", "js", "jsx" -> trimmed.startsWith("function ") || trimmed.startsWith("class ") || trimmed.contains("=>")
                else -> trimmed.startsWith("class ") || trimmed.startsWith("fun ") || trimmed.startsWith("def ")
            }

            if (isMatch) {
                return trimmed
            }
        }
        return null
    }

    private fun readGitMessage(ref: String?): String? {
        if (ref.isNullOrBlank()) {
            return null
        }

        return try {
            val process = ProcessBuilder("git", "log", "-1", "--pretty=%s", ref)
                .directory(project.basePath?.let { java.io.File(it) })
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && output.isNotBlank()) output else null
        } catch (_: Exception) {
            null
        }
    }

    private fun jsonArray(values: List<String>): String {
        return values.joinToString(prefix = "[", postfix = "]") { jsonString(it) }
    }

    private fun jsonString(value: String?): String {
        if (value == null) return "null"
        val escaped = buildString {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(ch)
                }
            }
        }
        return "\"$escaped\""
    }
}

sealed class ExportOutcome(val message: String) {
    class Success(message: String) : ExportOutcome(message)
    class Failure(message: String) : ExportOutcome(message)
    class Skipped(message: String) : ExportOutcome(message)
}
