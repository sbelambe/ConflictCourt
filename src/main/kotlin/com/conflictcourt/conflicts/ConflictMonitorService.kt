package com.conflictcourt.conflicts

import com.conflictcourt.export.ConflictJsonExporter
import com.conflictcourt.export.ExportOutcome
import com.intellij.openapi.components.Service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil

@Service(Service.Level.PROJECT)
class ConflictMonitorService(private val project: Project) {
    private val scanner = ConflictScanner()
    private val listeners = linkedSetOf<(ConflictScanResult) -> Unit>()
    private val executor = AppExecutorUtil.getAppExecutorService()
    private val exporter = ConflictJsonExporter(project)
    private var latestResult: ConflictScanResult = ConflictScanResult.empty()
    private var lastExportedSignature: String? = null

    fun refreshForActiveEditor() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        latestResult = applyLocalStatus(scan(editor?.document))
        notifyListeners()
        maybeExport(latestResult)
    }

    fun isActiveDocument(document: Document): Boolean {
        val activeDocument = FileEditorManager.getInstance(project).selectedTextEditor?.document
        return activeDocument == document
    }

    fun getLatestResult(): ConflictScanResult = latestResult

    fun addListener(listener: (ConflictScanResult) -> Unit) {
        listeners += listener
        listener(latestResult)
    }

    fun removeListener(listener: (ConflictScanResult) -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() {
        listeners.forEach { it(latestResult) }
    }

    private fun scan(document: Document?): ConflictScanResult {
        if (document == null) {
            return ConflictScanResult.empty()
        }

        val filePath = FileDocumentManager.getInstance().getFile(document)?.path
        return scanner.scan(document.text, filePath)
    }

    private fun applyLocalStatus(result: ConflictScanResult): ConflictScanResult {
        return when {
            result.filePath == null -> result.copy(uploadStatus = "No active editor")
            !result.hasConflicts -> {
                lastExportedSignature = null
                result.copy(uploadStatus = "No conflicts to export")
            }
            else -> result.copy(uploadStatus = "Conflict detected. JSON export pending.")
        }
    }

    private fun maybeExport(result: ConflictScanResult) {
        if (!result.hasConflicts || result.filePath == null) {
            return
        }

        val signature = buildSignature(result)
        if (signature == lastExportedSignature) {
            latestResult = latestResult.copy(uploadStatus = "Conflict already exported for current content")
            notifyListeners()
            return
        }

        lastExportedSignature = signature

        executor.execute {
            val outcome = try {
                exporter.export(result)
            } catch (exception: Exception) {
                ExportOutcome.Failure("JSON export error: ${exception.message ?: "unknown error"}")
            }

            latestResult = latestResult.copy(uploadStatus = outcome.message)
            notifyListeners()
        }
    }

    private fun buildSignature(result: ConflictScanResult): String {
        val serializedBlocks = result.blocks.joinToString("|") { block ->
            listOf(
                block.startLine,
                block.separatorLine,
                block.endLine,
                block.currentLabel,
                block.incomingLabel,
                block.currentText,
                block.incomingText
            ).joinToString("::")
        }

        return "${result.filePath}|$serializedBlocks"
    }
}
