package com.conflictcourt.actions

import com.conflictcourt.export.ConflictJsonExporter
import com.conflictcourt.export.ExportOutcome
import com.conflictcourt.git.GitMergePreviewService
import com.conflictcourt.git.MergePreviewOutcome
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager

class ExportMergePreviewAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val targetBranch = Messages.showInputDialog(
            project,
            "Enter the branch or ref you want to merge into, such as main or origin/main.",
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
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.text = "Comparing HEAD with $targetBranch"

                val result = runCatching {
                    when (val outcome = previewService.previewAgainst(targetBranch)) {
                        is MergePreviewOutcome.Success -> {
                            val exportOutcome = exporter.exportMergePreview(targetBranch, outcome.conflicts)
                            PreviewUiResult(
                                title = "Merge preview exported",
                                message = buildString {
                                    append(outcome.message)
                                    append("\n")
                                    append(exportOutcome.message)
                                },
                                type = when (exportOutcome) {
                                    is ExportOutcome.Failure -> NotificationType.ERROR
                                    is ExportOutcome.Skipped -> NotificationType.WARNING
                                    is ExportOutcome.Success -> NotificationType.INFORMATION
                                }
                            )
                        }
                        is MergePreviewOutcome.Skipped -> PreviewUiResult(
                            title = "Merge preview",
                            message = outcome.message,
                            type = NotificationType.INFORMATION
                        )
                        is MergePreviewOutcome.Failure -> PreviewUiResult(
                            title = "Merge preview failed",
                            message = outcome.message,
                            type = NotificationType.ERROR
                        )
                    }
                }.getOrElse { exception ->
                    PreviewUiResult(
                        title = "Merge preview failed",
                        message = exception.message ?: "Unexpected error during merge preview",
                        type = NotificationType.ERROR
                    )
                }

                ApplicationManager.getApplication().invokeLater {
                    ToolWindowManager.getInstance(project).getToolWindow("ConflictCourt")?.show()
                    notify(project, result.title, result.message, result.type)
                    when (result.type) {
                        NotificationType.ERROR -> Messages.showErrorDialog(project, result.message, result.title)
                        else -> Messages.showInfoMessage(project, result.message, result.title)
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

    private data class PreviewUiResult(
        val title: String,
        val message: String,
        val type: NotificationType
    )
}
