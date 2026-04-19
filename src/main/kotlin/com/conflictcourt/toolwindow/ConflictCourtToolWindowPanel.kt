package com.conflictcourt.toolwindow

import com.conflictcourt.conflicts.ConflictMonitorService
import com.conflictcourt.conflicts.ConflictScanResult
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.JTextArea

class ConflictCourtToolWindowPanel(private val project: Project) : JBPanel<ConflictCourtToolWindowPanel>(BorderLayout()) {
    private val monitorService = project.getService(ConflictMonitorService::class.java)
    private val browser = createBrowser()
    private val statusArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        rows = 10
    }
    private val updateListener: (ConflictScanResult) -> Unit = { result -> updateStatus(result) }

    init {
        add(buildContent(), BorderLayout.CENTER)
        monitorService.addListener(updateListener)
    }

    private fun buildContent(): JComponent {
        val openUrl = WarRoomUrlProvider.currentUrl()
        val notifyButton = JButton("Test Notification").apply {
            addActionListener {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("ConflictCourt")
                    .createNotification(
                        "ConflictCourt panel is active",
                        "Next step is replacing this placeholder UI with real merge-conflict analysis.",
                        NotificationType.INFORMATION
                    )
                    .notify(project)
            }
        }

        val reloadButton = JButton("Reload War Room").apply {
            isEnabled = browser != null
            addActionListener {
                browser?.loadURL(openUrl)
            }
        }

        val statusPanel = FormBuilder.createFormBuilder()
            .addComponent(JBLabel("ConflictCourt"))
            .addComponentFillVertically(JBScrollPane(statusArea), 0)
            .addComponent(JBLabel("War Room URL: $openUrl"))
            .addComponent(reloadButton)
            .addComponent(notifyButton)
            .panel

        val browserComponent = browser?.component ?: fallbackBrowserPanel(openUrl)

        return JSplitPane(JSplitPane.VERTICAL_SPLIT, browserComponent, statusPanel).apply {
            resizeWeight = 0.72
            dividerLocation = 420
            minimumSize = Dimension(320, 240)
        }
    }

    private fun updateStatus(result: ConflictScanResult) {
        val header = result.filePath ?: "No active file selected."
        val body = when {
            result.filePath == null -> "Open a file in the editor. ConflictCourt scans the active editor for Git merge markers like <<<<<<<, =======, and >>>>>>>."
            result.hasConflicts -> buildConflictSummary(result)
            else -> "No Git merge conflict markers found in the active file."
        }

        statusArea.text = "$header\n\nExport status: ${result.uploadStatus}\n\n$body"
    }

    private fun buildConflictSummary(result: ConflictScanResult): String {
        val ranges = result.blocks.joinToString("\n\n") { block ->
            """
            Conflict block at lines ${block.startLine}-${block.endLine} (separator line ${block.separatorLine})
            Current (${block.currentLabel}):
            ${formatBlockText(block.currentText)}
            
            Incoming (${block.incomingLabel}):
            ${formatBlockText(block.incomingText)}
            """.trimIndent()
        }

        return "Detected ${result.blocks.size} conflict block(s).\n\n$ranges"
    }

    private fun formatBlockText(text: String): String {
        return if (text.isBlank()) {
            "[empty]"
        } else {
            text
        }
    }

    private fun createBrowser(): JBCefBrowser? {
        if (!JBCefApp.isSupported()) {
            return null
        }

        return JBCefBrowser(WarRoomUrlProvider.currentUrl())
    }

    private fun fallbackBrowserPanel(url: String): JComponent {
        val fallbackText = JTextArea(
            """
            JCEF is not available in this IDE session.

            Expected War Room URL:
            $url

            If you are running in a headless environment, JCEF may be disabled there but available in a normal IDE window.
            """.trimIndent()
        ).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }

        return JPanel(BorderLayout()).apply {
            add(JBLabel("War Room"), BorderLayout.NORTH)
            add(JBScrollPane(fallbackText), BorderLayout.CENTER)
        }
    }

    override fun removeNotify() {
        monitorService.removeListener(updateListener)
        browser?.let { Disposer.dispose(it) }
        super.removeNotify()
    }
}
