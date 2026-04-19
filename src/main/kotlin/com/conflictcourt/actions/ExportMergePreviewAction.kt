package com.conflictcourt.actions

import com.conflictcourt.export.ConflictJsonExporter
import com.conflictcourt.export.ExportOutcome
import com.conflictcourt.git.GitMergePreviewService
import com.conflictcourt.git.MergePreviewOutcome
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

class ExportMergePreviewAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val targetBranch = Messages.showInputDialog(
            project,
            "Enter the branch or ref to merge against, such as main or origin/main.",
            "ConflictCourt Merge Preview",
            null,
            "main",
            null
        )?.trim().orEmpty()

        if (targetBranch.isBlank()) {
            notify(project, "Merge preview cancelled", "No target branch was provided.", NotificationType.WARNING)
            return
        }

        val previewService = project.getService(GitMergePreviewService::class.java)
        val exporter = ConflictJsonExporter(project)

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "ConflictCourt Merge Preview", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Comparing HEAD with $targetBranch"
                val result = runCatching {
                    when (val outcome = previewService.previewAgainst(targetBranch)) {
                        is MergePreviewOutcome.Success -> {
                            val export = exporter.exportMergePreview(targetBranch, outcome.conflicts)
                            UiResult(
                                title = "Merge preview exported",
                                message = "${outcome.message}\n${export.message}",
                                type = when (export) {
                                    is ExportOutcome.Success -> NotificationType.INFORMATION
                                    is ExportOutcome.Skipped -> NotificationType.WARNING
                                    is ExportOutcome.Failure -> NotificationType.ERROR
                                }
                            )
                        }
                        is MergePreviewOutcome.Skipped -> UiResult("Merge preview", outcome.message, NotificationType.INFORMATION)
                        is MergePreviewOutcome.Failure -> UiResult("Merge preview failed", outcome.message, NotificationType.ERROR)
                    }
                }.getOrElse { ex -> UiResult("Merge preview failed", ex.message ?: "Unexpected error", NotificationType.ERROR) }

                ApplicationManager.getApplication().invokeLater {
                    ToolWindowManager.getInstance(project).getToolWindow("ConflictCourt")?.show()
                    notify(project, result.title, result.message, result.type)
                    if (result.type == NotificationType.ERROR) {
                        Messages.showErrorDialog(project, result.message, result.title)
                    } else {
                        Messages.showInfoMessage(project, result.message, result.title)
                    }
                }
            }
        })
    }

    private fun notify(project: Project, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("ConflictCourt")
            .createNotification(title, content, type)
            .notify(project)
    }

    private data class UiResult(val title: String, val message: String, val type: NotificationType)
}

