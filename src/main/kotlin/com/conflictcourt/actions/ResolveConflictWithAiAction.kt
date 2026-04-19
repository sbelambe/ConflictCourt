package com.conflictcourt.actions

import com.conflictcourt.ai.ConflictContext
import com.conflictcourt.ai.ConflictContextBuilder
import com.conflictcourt.ai.ConflictContextInputParser
import com.conflictcourt.ai.ConflictCourtAiFactory
import com.conflictcourt.ai.PipelineOutcome
import com.conflictcourt.conflicts.ConflictBlock
import com.conflictcourt.conflicts.ConflictMonitorService
import com.conflictcourt.conflicts.ConflictScanner
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.runBlocking
import java.io.File

class ResolveConflictWithAiAction : AnAction() {
    private val scanner = ConflictScanner()

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            notify(project, "ConflictCourt AI Resolve", "No active editor found.", NotificationType.WARNING)
            return
        }

        val document = editor.document
        val filePath = FileDocumentManager.getInstance().getFile(document)?.path
        if (filePath == null) {
            notify(project, "ConflictCourt AI Resolve", "Active file path not found.", NotificationType.WARNING)
            return
        }

        val target = resolveTarget(project, editor, document, filePath) ?: return
        val pipeline = ConflictCourtAiFactory.createPipelineFromEnvironment()
        if (pipeline == null) {
            notify(project, "ConflictCourt AI Resolve", "AI pipeline could not be initialized. Check OPENAI_API_KEY.", NotificationType.ERROR)
            return
        }

        val snapshotStamp = document.modificationStamp
        val snapshotHash = document.text.hashCode()

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "ConflictCourt: Resolve Conflict with AI", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Sending conflict to AI"
                val outcome = runBlocking { pipeline.resolve(target.context) }
                ApplicationManager.getApplication().invokeLater {
                    applyOutcome(project, document, target, snapshotStamp, snapshotHash, outcome)
                }
            }
        })
    }

    private fun resolveTarget(
        project: Project,
        editor: Editor,
        document: Document,
        filePath: String
    ): ApplyTarget? {
        val markerTarget = resolveMarkerTarget(project, editor, document, filePath)
        if (markerTarget != null) return markerTarget
        return resolvePreviewJsonTarget(project, editor, document, filePath)
    }

    private fun resolveMarkerTarget(
        project: Project,
        editor: Editor,
        document: Document,
        filePath: String
    ): ApplyTarget? {
        val scanResult = scanner.scan(document.text, filePath)
        if (!scanResult.hasConflicts) return null

        val targetBlock = selectTargetBlock(project, editor, scanResult.blocks) ?: return null
        val context = ConflictContextBuilder.fromConflictBlock(project, filePath, document.text, targetBlock)
        val startOffset = document.getLineStartOffset(targetBlock.startLine - 1)
        val endOffset = document.getLineEndOffset(targetBlock.endLine - 1)

        return ApplyTarget(
            startOffset = startOffset,
            endOffset = endOffset,
            context = context,
            source = "in-file conflict markers",
            lineStart = targetBlock.startLine,
            lineEnd = targetBlock.endLine
        )
    }

    private fun resolvePreviewJsonTarget(
        project: Project,
        editor: Editor,
        document: Document,
        activeFilePath: String
    ): ApplyTarget? {
        val projectBase = project.basePath ?: run {
            notify(project, "ConflictCourt AI Resolve", "Project base path not found.", NotificationType.WARNING)
            return null
        }
        val previewFile = File(projectBase, "conflictcourt_merge_preview.json")
        if (!previewFile.exists() || !previewFile.isFile) {
            notify(
                project,
                "ConflictCourt AI Resolve",
                "No conflict markers found and no conflictcourt_merge_preview.json found in project root.",
                NotificationType.INFORMATION
            )
            return null
        }

        val raw = runCatching { previewFile.readText() }.getOrElse {
            notify(project, "ConflictCourt AI Resolve", "Failed to read ${previewFile.name}: ${it.message}", NotificationType.ERROR)
            return null
        }

        val contexts = ConflictContextInputParser.parseAll(raw)
        if (contexts.isEmpty()) {
            notify(project, "ConflictCourt AI Resolve", "No parseable conflicts found in ${previewFile.name}.", NotificationType.WARNING)
            return null
        }

        val matchingByPath = contexts.filter { contextPathMatchesActive(it.filePath, activeFilePath, projectBase) }
        if (matchingByPath.isEmpty()) {
            notify(project, "ConflictCourt AI Resolve", "No preview conflicts match the active file path.", NotificationType.WARNING)
            return null
        }

        val located = matchingByPath.mapNotNull { context ->
            locateRangeInDocument(document.text, context, editor.caretModel.offset)?.let { range ->
                ApplyTarget(
                    startOffset = range.first,
                    endOffset = range.second,
                    context = context,
                    source = "merge preview JSON",
                    lineStart = document.getLineNumber(range.first) + 1,
                    lineEnd = document.getLineNumber(maxOf(range.first, range.second - 1)) + 1
                )
            }
        }

        if (located.isEmpty()) {
            notify(
                project,
                "ConflictCourt AI Resolve",
                "Matched preview conflict(s), but could not uniquely locate code_head/code_incoming in active file.",
                NotificationType.WARNING
            )
            return null
        }

        val caretOffset = editor.caretModel.offset
        val caretMatches = located.filter { caretOffset in it.startOffset until it.endOffset.coerceAtLeast(it.startOffset + 1) }
        return when {
            caretMatches.size == 1 -> caretMatches.first()
            located.size == 1 -> located.first()
            else -> {
                notify(
                    project,
                    "ConflictCourt AI Resolve",
                    "Multiple preview conflicts match this file. Move caret inside the target region or reduce preview to one conflict.",
                    NotificationType.WARNING
                )
                null
            }
        }
    }

    private fun contextPathMatchesActive(contextPath: String, activeFilePath: String, projectBase: String): Boolean {
        val normalizedContext = contextPath.replace('\\', '/')
        val normalizedActive = activeFilePath.replace('\\', '/')
        if (normalizedContext == normalizedActive) return true
        if (normalizedActive.endsWith("/$normalizedContext")) return true

        val relativeActive = runCatching {
            val base = File(projectBase).canonicalFile.toPath()
            val active = File(activeFilePath).canonicalFile.toPath()
            base.relativize(active).toString().replace('\\', '/')
        }.getOrNull()
        return relativeActive == normalizedContext
    }

    private fun locateRangeInDocument(documentText: String, context: ConflictContext, caretOffset: Int): Pair<Int, Int>? {
        val block = context.conflictBlocks.firstOrNull() ?: return null
        val currentRanges = findAllRanges(documentText, block.currentText)
        val incomingRanges = findAllRanges(documentText, block.incomingText)

        if (currentRanges.size == 1) return currentRanges.first()
        if (incomingRanges.size == 1) return incomingRanges.first()

        val allRanges = (currentRanges + incomingRanges).distinct()
        if (allRanges.size == 1) return allRanges.first()

        val containingCaret = allRanges.filter { caretOffset in it.first until it.second.coerceAtLeast(it.first + 1) }
        return if (containingCaret.size == 1) containingCaret.first() else null
    }

    private fun findAllRanges(haystack: String, needle: String): List<Pair<Int, Int>> {
        if (needle.isBlank()) return emptyList()
        val ranges = mutableListOf<Pair<Int, Int>>()
        var index = 0
        while (true) {
            val found = haystack.indexOf(needle, startIndex = index)
            if (found < 0) break
            ranges += found to (found + needle.length)
            index = found + 1
        }
        return ranges
    }

    private fun applyOutcome(
        project: Project,
        document: Document,
        target: ApplyTarget,
        snapshotStamp: Long,
        snapshotHash: Int,
        outcome: PipelineOutcome
    ) {
        when (outcome) {
            is PipelineOutcome.CodexFailure -> {
                notify(
                    project,
                    "ConflictCourt AI Resolve Failed",
                    "Codex error: ${outcome.failure.message} (status=${outcome.failure.statusCode})",
                    NotificationType.ERROR
                )
            }

            is PipelineOutcome.Success -> {
                val mergedCode = outcome.resolution.mergedCode
                val validationError = validateMergedCode(mergedCode)
                if (validationError != null) {
                    notify(project, "ConflictCourt AI Resolve Rejected", validationError, NotificationType.ERROR)
                    return
                }

                if (document.modificationStamp != snapshotStamp || document.text.hashCode() != snapshotHash) {
                    notify(project, "ConflictCourt AI Resolve Aborted", "Document changed while AI was running. Re-run on latest content.", NotificationType.WARNING)
                    return
                }

                val preview = buildString {
                    appendLine("Apply AI resolution from ${target.source} to lines ${target.lineStart}-${target.lineEnd}?")
                    appendLine()
                    appendLine("Preview:")
                    appendLine(mergedCode.take(1200))
                }
                val decision = Messages.showYesNoDialog(project, preview, "ConflictCourt AI Preview", null)
                if (decision != Messages.YES) {
                    notify(project, "ConflictCourt AI Resolve", "Apply cancelled by user.", NotificationType.INFORMATION)
                    return
                }

                val textToInsert = mergedCode.trimEnd('\n')
                WriteCommandAction.runWriteCommandAction(project, "ConflictCourt: Apply AI Resolution", null, Runnable {
                    document.replaceString(target.startOffset, target.endOffset, textToInsert)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                    FileDocumentManager.getInstance().saveDocument(document)
                })

                val monitor = project.getService(ConflictMonitorService::class.java)
                monitor.refreshForActiveEditor()
                monitor.overrideUploadStatus("Conflict resolved by AI.")
                ToolWindowManager.getInstance(project).getToolWindow("ConflictCourt")?.show()
                notify(project, "ConflictCourt AI Resolve", "Conflict resolved and applied to the document.", NotificationType.INFORMATION)
            }
        }
    }

    private fun selectTargetBlock(project: Project, editor: Editor, blocks: List<ConflictBlock>): ConflictBlock? {
        val document = editor.document
        val selectionModel = editor.selectionModel
        val candidates = if (selectionModel.hasSelection()) {
            val selectionStartLine = document.getLineNumber(selectionModel.selectionStart) + 1
            val selectionEndOffset = maxOf(selectionModel.selectionStart, selectionModel.selectionEnd - 1)
            val selectionEndLine = document.getLineNumber(selectionEndOffset) + 1
            blocks.filter { overlap(selectionStartLine, selectionEndLine, it.startLine, it.endLine) }
        } else {
            val caretLine = document.getLineNumber(editor.caretModel.offset) + 1
            blocks.filter { caretLine in it.startLine..it.endLine }
        }

        return when (candidates.size) {
            1 -> candidates.first()
            0 -> {
                notify(
                    project,
                    "ConflictCourt AI Resolve",
                    if (selectionModel.hasSelection()) {
                        "Selection does not intersect a conflict block. Select exactly one conflict block."
                    } else {
                        "Place caret inside a conflict block or select exactly one conflict block."
                    },
                    NotificationType.WARNING
                )
                null
            }
            else -> {
                notify(project, "ConflictCourt AI Resolve", "Multiple conflict blocks targeted. Select exactly one block.", NotificationType.WARNING)
                null
            }
        }
    }

    private fun overlap(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean {
        return aStart <= bEnd && bStart <= aEnd
    }

    private fun validateMergedCode(mergedCode: String): String? {
        if (mergedCode.isBlank()) return "AI returned empty merged_code."
        if ("<<<<<<<" in mergedCode || "=======" in mergedCode || ">>>>>>>" in mergedCode) {
            return "AI output still contains merge conflict markers."
        }
        return null
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ConflictCourt")
            .createNotification(title, content, type)
            .notify(project)
    }

    private data class ApplyTarget(
        val startOffset: Int,
        val endOffset: Int,
        val context: ConflictContext,
        val source: String,
        val lineStart: Int,
        val lineEnd: Int
    )
}
