package com.conflictcourt.git

import com.conflictcourt.conflicts.ConflictScanner
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.io.path.deleteIfExists

@Service(Service.Level.PROJECT)
class GitMergePreviewService(private val project: Project) {
    private val scanner = ConflictScanner()

    fun previewAgainst(targetBranch: String): MergePreviewOutcome {
        val projectRoot = project.basePath?.let(::File) ?: return MergePreviewOutcome.Failure("Project base path not found")
        val repoRoot = findRepoRoot(projectRoot)
            ?: return MergePreviewOutcome.Failure("Git repository not found for project path: ${projectRoot.absolutePath}")
        val targetCommit = verifyRef(repoRoot, targetBranch)
            ?: return MergePreviewOutcome.Failure("Git ref not found: $targetBranch")
        val headCommit = git(repoRoot, "rev-parse", "--verify", "HEAD^{commit}").trim()

        val base = git(repoRoot, "merge-base", headCommit, targetCommit).trim()
        if (base.isBlank()) return MergePreviewOutcome.Failure("Could not determine merge base for $targetBranch")

        val oursFiles = changedFiles(repoRoot, base, headCommit) + workingTreeChangedFiles(repoRoot)
        val theirsFiles = changedFiles(repoRoot, base, targetCommit)
        val candidateFiles = oursFiles.intersect(theirsFiles).sorted()

        if (candidateFiles.isEmpty()) {
            return MergePreviewOutcome.Skipped("No overlapping file changes between HEAD and $targetBranch")
        }

        val commitHead = latestCommitMessage(repoRoot, headCommit)
        val commitIncoming = latestCommitMessage(repoRoot, targetCommit)
        val conflicts = candidateFiles.mapNotNull { path ->
            previewFileConflict(repoRoot, base, headCommit, targetCommit, path, commitHead, commitIncoming)
        }

        return if (conflicts.isEmpty()) {
            MergePreviewOutcome.Skipped("No merge conflicts detected against $targetBranch")
        } else {
            MergePreviewOutcome.Success("Detected ${conflicts.size} merge-preview conflict(s) against $targetBranch", conflicts)
        }
    }

    private fun previewFileConflict(
        repoRoot: File,
        base: String,
        headCommit: String,
        targetCommit: String,
        path: String,
        commitHead: String?,
        commitIncoming: String?
    ): MergePreviewConflict? {
        val baseContent = showFile(repoRoot, base, path) ?: ""
        val oursContent = readWorkingTreeFile(repoRoot, path) ?: showFile(repoRoot, headCommit, path) ?: return null
        val theirsContent = showFile(repoRoot, targetCommit, path) ?: return null
        if (oursContent == theirsContent) return null

        val mergedOutput = mergeFile(repoRoot, oursContent, baseContent, theirsContent) ?: return null
        val scanResult = scanner.scan(mergedOutput, path)
        val firstBlock = scanResult.blocks.firstOrNull() ?: return null
        val lines = mergedOutput.lines()
        val aboveStart = maxOf(0, firstBlock.startLine - 1 - 25)
        val aboveEnd = maxOf(0, firstBlock.startLine - 1)
        val belowStart = minOf(lines.size, firstBlock.endLine)
        val belowEnd = minOf(lines.size, firstBlock.endLine + 25)

        return MergePreviewConflict(
            filePath = path,
            fileName = path.substringAfterLast('/'),
            language = detectLanguage(path),
            indentation = detectIndentation(oursContent.lines()),
            importsList = detectImports(oursContent.lines(), path),
            parentSignature = detectParentSignature(oursContent.lines(), mergedOutput, path, firstBlock.startLine),
            commitMsgHead = commitHead,
            commitMsgIncoming = commitIncoming,
            codeHead = firstBlock.currentText,
            codeIncoming = firstBlock.incomingText,
            bufferAbove = lines.subList(aboveStart, aboveEnd).joinToString("\n"),
            bufferBelow = lines.subList(belowStart, belowEnd).joinToString("\n")
        )
    }

    private fun changedFiles(repoRoot: File, fromRef: String, toRef: String): Set<String> =
        git(repoRoot, "diff", "--name-only", "--diff-filter=ACMR", fromRef, toRef)
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()

    private fun workingTreeChangedFiles(repoRoot: File): Set<String> {
        val tracked = git(repoRoot, "diff", "--name-only", "--diff-filter=ACMR", "HEAD")
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        val untracked = git(repoRoot, "ls-files", "--others", "--exclude-standard")
            .lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        return tracked + untracked
    }

    private fun verifyRef(repoRoot: File, ref: String): String? {
        val trimmed = ref.trim()
        if (trimmed.isBlank()) return null
        val candidates = listOf(
            trimmed,
            "refs/heads/$trimmed",
            "origin/$trimmed",
            "refs/remotes/origin/$trimmed",
            "refs/remotes/$trimmed"
        ).distinct()
        for (candidate in candidates) {
            val resolved = runCatching { git(repoRoot, "rev-parse", "--verify", "${candidate}^{commit}").trim() }.getOrNull()
            if (!resolved.isNullOrBlank()) return resolved
        }
        return null
    }

    private fun findRepoRoot(projectRoot: File): File? =
        runCatching { git(projectRoot, "rev-parse", "--show-toplevel").trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)

    private fun latestCommitMessage(repoRoot: File, ref: String): String? =
        runCatching { git(repoRoot, "log", "-1", "--pretty=%s", ref).trim() }.getOrNull()?.ifBlank { null }

    private fun showFile(repoRoot: File, ref: String, path: String): String? =
        runCatching { git(repoRoot, "show", "$ref:$path") }.getOrNull()

    private fun readWorkingTreeFile(repoRoot: File, path: String): String? {
        val file = File(repoRoot, path)
        if (!file.exists() || !file.isFile) return null
        return runCatching { file.readText(StandardCharsets.UTF_8) }.getOrNull()
    }

    private fun mergeFile(repoRoot: File, ours: String, base: String, theirs: String): String? {
        val oursFile = Files.createTempFile("conflictcourt-ours", ".tmp")
        val baseFile = Files.createTempFile("conflictcourt-base", ".tmp")
        val theirsFile = Files.createTempFile("conflictcourt-theirs", ".tmp")
        return try {
            Files.writeString(oursFile, ours, StandardCharsets.UTF_8)
            Files.writeString(baseFile, base, StandardCharsets.UTF_8)
            Files.writeString(theirsFile, theirs, StandardCharsets.UTF_8)
            val process = ProcessBuilder("git", "merge-file", "-p", oursFile.toString(), baseFile.toString(), theirsFile.toString())
                .directory(repoRoot)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exit = process.waitFor()
            if (exit == 0 || exit == 1) output else null
        } finally {
            oursFile.deleteIfExists()
            baseFile.deleteIfExists()
            theirsFile.deleteIfExists()
        }
    }

    private fun git(repoRoot: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", *args)).directory(repoRoot).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) error(output.trim().ifBlank { "Git command failed: ${args.joinToString(" ")}" })
        return output
    }

    private fun detectLanguage(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
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

    private fun detectIndentation(lines: List<String>): String {
        val indentedLine = lines.firstOrNull { it.startsWith("    ") || it.startsWith("\t") || it.startsWith("  ") } ?: return "Unknown"
        return if (indentedLine.startsWith("\t")) "Tabs" else "Spaces (${indentedLine.takeWhile { it == ' ' }.length})"
    }

    private fun detectImports(lines: List<String>, path: String): List<String> {
        val ext = path.substringAfterLast('.', "").lowercase()
        return lines.mapNotNull { line ->
            val trimmed = line.trim()
            when {
                ext == "py" && (trimmed.startsWith("import ") || trimmed.startsWith("from ")) -> trimmed
                ext in setOf("kt", "java", "ts", "tsx", "js", "jsx", "go") && trimmed.startsWith("import ") -> trimmed
                else -> null
            }
        }
    }

    private fun detectParentSignature(lines: List<String>, mergedOutput: String, path: String, conflictStartLine: Int): String? {
        val ext = path.substringAfterLast('.', "").lowercase()
        val scanLine = minOf(lines.size - 1, maxOf(0, conflictStartLine - 1))
        for (index in scanLine downTo 0) {
            val trimmed = lines[index].trim()
            val isMatch = when (ext) {
                "py" -> trimmed.startsWith("def ") || trimmed.startsWith("class ")
                "kt" -> trimmed.startsWith("fun ") || trimmed.startsWith("class ") || trimmed.startsWith("object ")
                "java" -> trimmed.startsWith("class ") || trimmed.matches(Regex(".*\\)\\s*\\{?$"))
                "ts", "tsx", "js", "jsx" -> trimmed.startsWith("function ") || trimmed.startsWith("class ") || trimmed.contains("=>")
                else -> trimmed.startsWith("class ") || trimmed.startsWith("fun ") || trimmed.startsWith("def ")
            }
            if (isMatch) return trimmed
        }
        return mergedOutput.lineSequence().take(conflictStartLine).lastOrNull { it.isNotBlank() }?.trim()
    }
}

