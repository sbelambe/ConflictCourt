package com.conflictcourt.actions

import com.conflictcourt.conflicts.ConflictMonitorService
import com.conflictcourt.conflicts.ConflictScanResult
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

class OpenWarRoomAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project: Project? = event.project
        val result = project?.getService(ConflictMonitorService::class.java)?.getLatestResult()

        project?.let {
            ToolWindowManager.getInstance(it).getToolWindow("ConflictCourt")?.show()
        }

        NotificationGroupManager.getInstance()
            .getNotificationGroup("ConflictCourt")
            .createNotification(
                "ConflictCourt status",
                buildMessage(project, result),
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    private fun buildMessage(project: Project?, result: ConflictScanResult?): String {
        val projectName = project?.name ?: "the current project"
        val status = when {
            result == null -> "No active editor."
            result.hasConflicts -> "Detected ${result.blocks.size} conflict block(s) in the active file."
            else -> "No merge conflict markers found in the active file."
        }

        return "ConflictCourt is active for $projectName. $status"
    }
}
