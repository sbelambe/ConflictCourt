package com.conflictcourt.startup

import com.conflictcourt.conflicts.ConflictMonitorService
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.StartupActivity

class ConflictListenerStartupActivity : StartupActivity.DumbAware {
    override fun runActivity(project: Project) {
        val monitorService = project.getService(ConflictMonitorService::class.java)

        project.messageBus.connect(project).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    monitorService.refreshForActiveEditor()
                }
            }
        )

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    if (monitorService.isActiveDocument(event.document)) {
                        monitorService.refreshForActiveEditor()
                    }
                }
            },
            project
        )

        monitorService.refreshForActiveEditor()
    }
}
