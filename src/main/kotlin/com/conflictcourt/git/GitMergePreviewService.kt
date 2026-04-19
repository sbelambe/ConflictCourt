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
            ?: return MergePreviewOutcome.Failure(
                "Git ref not found: $targetBranch. Try a local branch name or remote ref like origin/main."
            )

        val headCommit = git(repoRoot, "rev-parse", "--verify", "HEAD^{commit}").trim()
        val base = git(repoRoot, "merge-base", headCommit, targetCommit).trim()
        if (base.isBlank()) {
            return MergePreviewOutcome.Failure("Could not determine merge base for $targetBranch")
        }

        val oursFiles = changedFiles(repoRoot, base, headCommit) + workingTreeChangedFiles(repoRoot)
        val theirsFiles = changedFiles(repoRoot, base, targetCommit)
        val candidateFiles = oursFiles.intersect(theirsFiles).sorted()
        if (candidateFiles.isEmpty()) {
            return MergePreviewOutcome.Skipped(
                buildString {
                    append("No overlapping file changes between HEAD and $targetBranch.")
                    append("\n")
                    append("Changed in current branch/working tree: ${oursFiles.size}")
                    append("\n")
                    append("Changed in target branch: ${theirsFiles.size}")
                    if (oursFiles.isNotEmpty()) {
                        append("\n")
                        append("Current examples: ${oursFiles.take(5).joinToString(", ")}")
                    }
                    if (theirsFiles.isNotEmpty()) {
                        append("\n")
                        append("Target examples: ${theirsFiles.take(5).joinToString(", ")}")
                    }
                }
            )
        }

        val commitHead = latestCommitMessage(repoRoot, headCommit)
        val commitIncoming = latestCommitMessage(repoRoot, targetCommit)
        val conflicts = candidateFiles.mapNotNull { path ->
            previewFileConflict(repoRoot, base, headCommit, targetCommit, path, commitHead, commitIncoming)
        }

        return if (conflicts.isEmpty()) {
            MergePreviewOutcome.Skipped("No merge conflicts detected against $targetBranch")
        } else {
            MergePreviewOutcome.Success(
                message = "Detected ${conflicts.size} merge-preview conflict(s) against $targetBranch",
                conflicts = conflicts
            )
        }
    }

    fun diffAgainst(targetBranch: String): MergePreviewOutcome {
        val projectRoot = project.basePath?.let(::File) ?: return MergePreviewOutcome.Failure("Project base path not found")
        val repoRoot = findRepoRoot(projectRoot)
            ?: return MergePreviewOutcome.Failure("Git repository not found for project path: ${projectRoot.absolutePath}")
        val targetCommit = verifyRef(repoRoot, targetBranch)
            ?: return MergePreviewOutcome.Failure(
                "Git ref not found: $targetBranch. Try a local branch name or remote ref like origin/main."
            )

        val headCommit = git(repoRoot, "rev-parse", "--verify", "HEAD^{commit}").trim()
        val commitHead = latestCommitMessage(repoRoot, headCommit)
        val commitIncoming = latestCommitMessage(repoRoot, targetCommit)

        val trackedDiffFiles = changedFiles(repoRoot, targetCommit, headCommit)
        val workingTreeFiles = workingTreeChangedFiles(repoRoot)
        val candidateFiles = (trackedDiffFiles + workingTreeFiles).sorted()

        if (candidateFiles.isEmpty()) {
            return MergePreviewOutcome.Skipped("No file differences detected between current state and $targetBranch")
        }

        val diffs = candidateFiles.mapNotNull { path ->
            buildDiffConflict(repoRoot, headCommit, targetCommit, path, commitHead, commitIncoming)
        }

        return if (diffs.isEmpty()) {
            MergePreviewOutcome.Skipped("No content differences detected after filtering unchanged files")
        } else {
            MergePreviewOutcome.Success(
                message = "Detected ${diffs.size} differing file(s) against $targetBranch",
                conflicts = diffs
            )
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

        if (oursContent == theirsContent) {
            return null
        }

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

    private fun buildDiffConflict(
        repoRoot: File,
        headCommit: String,
        targetCommit: String,
        path: String,
        commitHead: String?,
        commitIncoming: String?
    ): MergePreviewConflict? {
        val oursContent = readWorkingTreeFile(repoRoot, path) ?: showFile(repoRoot, headCommit, path) ?: ""
        val theirsContent = showFile(repoRoot, targetCommit, path) ?: ""

        if (oursContent == theirsContent) {
            return null
        }

        val oursLines = oursContent.lines()
        val theirsLines = theirsContent.lines()
        val change = findPrimaryDifference(oursLines, theirsLines)

        val aboveStart = maxOf(0, change.startLine - 1 - 25)
        val aboveEnd = maxOf(0, change.startLine - 1)
        val belowStart = minOf(oursLines.size, change.endLine)
        val belowEnd = minOf(oursLines.size, change.endLine + 25)

        return MergePreviewConflict(
            filePath = path,
            fileName = path.substringAfterLast('/'),
            language = detectLanguage(path),
            indentation = detectIndentation(oursLines),
            importsList = detectImports(oursLines, path),
            parentSignature = detectParentSignature(oursLines, oursContent, path, change.startLine),
            commitMsgHead = commitHead,
            commitMsgIncoming = commitIncoming,
            codeHead = change.oursSnippet,
            codeIncoming = change.theirsSnippet,
            bufferAbove = oursLines.subList(aboveStart, aboveEnd).joinToString("\n"),
            bufferBelow = oursLines.subList(belowStart, belowEnd).joinToString("\n")
        )
    }

    private data class DiffSlice(
        val startLine: Int,
        val endLine: Int,
        val oursSnippet: String,
        val theirsSnippet: String
    )

    private fun findPrimaryDifference(oursLines: List<String>, theirsLines: List<String>): DiffSlice {
        var prefix = 0
        val minSize = minOf(oursLines.size, theirsLines.size)
        while (prefix < minSize && oursLines[prefix] == theirsLines[prefix]) {
            prefix++
        }

        var oursSuffix = oursLines.size - 1
        var theirsSuffix = theirsLines.size - 1
        while (oursSuffix >= prefix && theirsSuffix >= prefix && oursLines[oursSuffix] == theirsLines[theirsSuffix]) {
            oursSuffix--
            theirsSuffix--
        }

        val oursChanged = if (prefix <= oursSuffix) oursLines.subList(prefix, oursSuffix + 1) else emptyList()
        val theirsChanged = if (prefix <= theirsSuffix) theirsLines.subList(prefix, theirsSuffix + 1) else emptyList()
        val startLine = prefix + 1
        val endLine = if (oursChanged.isEmpty()) startLine else prefix + oursChanged.size

        return DiffSlice(
            startLine = startLine,
            endLine = endLine,
            oursSnippet = oursChanged.joinToString("\n"),
            theirsSnippet = theirsChanged.joinToString("\n")
        )
    }

    private fun changedFiles(repoRoot: File, fromRef: String, toRef: String): Set<String> {
        return git(repoRoot, "diff", "--name-only", "--diff-filter=ACMR", fromRef, toRef)
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun workingTreeChangedFiles(repoRoot: File): Set<String> {
        val tracked = git(repoRoot, "diff", "--name-only", "--diff-filter=ACMR", "HEAD")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        val untracked = git(repoRoot, "ls-files", "--others", "--exclude-standard")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        return tracked + untracked
    }

    private fun verifyRef(repoRoot: File, ref: String): String? {
        val trimmed = ref.trim()
        if (trimmed.isBlank()) return null

        val candidates = listOf(
            trimmed,
            "refs/heads/$trimmed",
            "refs/remotes/$trimmed",
            "origin/$trimmed",
            "refs/remotes/origin/$trimmed"
        ).distinct()

        for (candidate in candidates) {
            val resolved = runCatching {
                git(repoRoot, "rev-parse", "--verify", "${candidate}^{commit}").trim()
            }.getOrNull()
            if (!resolved.isNullOrBlank()) {
                return resolved
            }
        }

        return null
    }

    private fun findRepoRoot(projectRoot: File): File? {
        val repoPath = runCatching { git(projectRoot, "rev-parse", "--show-toplevel").trim() }.getOrNull()
        return repoPath?.takeIf { it.isNotBlank() }?.let(::File)
    }

    private fun latestCommitMessage(repoRoot: File, ref: String): String? {
        return runCatching { git(repoRoot, "log", "-1", "--pretty=%s", ref).trim() }
            .getOrNull()
            ?.ifBlank { null }
    }

    private fun showFile(repoRoot: File, ref: String, path: String): String? {
        return runCatching { git(repoRoot, "show", "$ref:$path") }.getOrNull()
    }

    private fun readWorkingTreeFile(repoRoot: File, path: String): String? {
        val file = File(repoRoot, path)
        if (!file.exists() || !file.isFile) {
            return null
        }
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

            val process = ProcessBuilder(
                "git", "merge-file", "-p",
                oursFile.toString(),
                baseFile.toString(),
                theirsFile.toString()
            )
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
        val process = ProcessBuilder(listOf("git", *args))
            .directory(repoRoot)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) {
            error(output.trim().ifBlank { "Git command failed: ${args.joinToString(" ")}" })
        }
        return output
    }

    private fun detectLanguage(path: String): String {
        return when (path.substringAfterLast('.', "").lowercase()) {
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
