package com.conflictcourt.actions

import com.conflictcourt.ai.ConflictCourtAiFactory
import com.conflictcourt.ai.ConflictContextBuilder
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
        val scanResult = scanner.scan(document.text, filePath)
        if (!scanResult.hasConflicts) {
            notify(project, "ConflictCourt AI Resolve", "No conflict markers found in the active file.", NotificationType.INFORMATION)
            return
        }

        val targetBlock = selectTargetBlock(project, editor, scanResult.blocks) ?: return
        val pipeline = ConflictCourtAiFactory.createPipelineFromEnvironment()
        if (pipeline == null) {
            notify(project, "ConflictCourt AI Resolve", "AI pipeline could not be initialized. Check OPENAI_API_KEY.", NotificationType.ERROR)
            return
        }

        val snapshotStamp = document.modificationStamp
        val context = ConflictContextBuilder.fromConflictBlock(project, filePath, document.text, targetBlock)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "ConflictCourt: Resolve Conflict with AI", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Sending conflict to AI"
                val outcome = runBlocking { pipeline.resolve(context) }
                ApplicationManager.getApplication().invokeLater {
                    applyOutcome(project, editor, document, targetBlock, snapshotStamp, outcome)
                }
            }
        })
    }

    private fun applyOutcome(
        project: Project,
        editor: Editor,
        document: Document,
        block: ConflictBlock,
        snapshotStamp: Long,
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

                if (document.modificationStamp != snapshotStamp) {
                    notify(project, "ConflictCourt AI Resolve Aborted", "Document changed while AI was running. Re-run on latest content.", NotificationType.WARNING)
                    return
                }

                val preview = buildString {
                    appendLine("Apply AI resolution to lines ${block.startLine}-${block.endLine}?")
                    appendLine()
                    appendLine("Preview:")
                    appendLine(mergedCode.take(1200))
                }
                val decision = Messages.showYesNoDialog(project, preview, "ConflictCourt AI Preview", null)
                if (decision != Messages.YES) {
                    notify(project, "ConflictCourt AI Resolve", "Apply cancelled by user.", NotificationType.INFORMATION)
                    return
                }

                val startOffset = document.getLineStartOffset(block.startLine - 1)
                val endOffset = document.getLineEndOffset(block.endLine - 1)
                val textToInsert = mergedCode.trimEnd('\n')

                WriteCommandAction.runWriteCommandAction(project, "ConflictCourt: Apply AI Resolution", null, Runnable {
                    document.replaceString(startOffset, endOffset, textToInsert)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                    FileDocumentManager.getInstance().saveDocument(document)
                })

                project.getService(ConflictMonitorService::class.java).refreshForActiveEditor()
                project.getService(ConflictMonitorService::class.java).overrideUploadStatus("Conflict resolved by AI.")
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
}
