package com.conflictcourt.toolwindow

import com.conflictcourt.conflicts.ConflictMonitorService
import com.conflictcourt.conflicts.ConflictScanResult
import com.conflictcourt.git.GitMergePreviewService
import com.conflictcourt.git.MergePreviewOutcome
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter

class ConflictCourtToolWindowPanel(private val project: Project) : JBPanel<ConflictCourtToolWindowPanel>(BorderLayout()) {
    private val workingTreeOptionValue = "__WORKING_TREE__"
    private val monitorService = project.getService(ConflictMonitorService::class.java)
    private val previewService = project.getService(GitMergePreviewService::class.java)
    private val browser = createBrowser()
    private val json = Json { prettyPrint = false }

    private var getStateQuery: JBCefJSQuery? = null
    private var runBranchCheckQuery: JBCefJSQuery? = null
    private var applyResolutionQuery: JBCefJSQuery? = null

    private var lastScanTimeMs: Long = System.currentTimeMillis()
    private var lastUploadStatus: String? = null
    private var branchCheckMessage: String = "Branch comparison not run yet."
    private var branchCheckHead: String? = null
    private var branchCheckIncoming: String? = null
    private var branchCheckConflicts: List<UiConflict> = emptyList()

    private val updateListener: (ConflictScanResult) -> Unit = { result -> pushState(result) }

    init {
        add(buildContent(), BorderLayout.CENTER)
        monitorService.addListener(updateListener)
        pushState(monitorService.getLatestResult())
    }

    private fun buildContent(): JComponent {
        val browserInstance = browser ?: return fallbackBrowserPanel()
        val url = WarRoomUrlProvider.currentUrl()

        getStateQuery = JBCefJSQuery.create(browserInstance).also { query ->
            query.addHandler {
                val payload = createHostPayload(monitorService.getLatestResult(), lastScanTimeMs, lastUploadStatus)
                JBCefJSQuery.Response(json.encodeToString(JsonObject.serializer(), payload))
            }
        }

        runBranchCheckQuery = JBCefJSQuery.create(browserInstance).also { query ->
            query.addHandler { request ->
                val response = handleBranchCheckRequest(request)
                JBCefJSQuery.Response(json.encodeToString(JsonObject.serializer(), response))
            }
        }

        applyResolutionQuery = JBCefJSQuery.create(browserInstance).also { query ->
            query.addHandler { request ->
                val response = handleApplyResolutionRequest(request)
                JBCefJSQuery.Response(json.encodeToString(JsonObject.serializer(), response))
            }
        }

        browserInstance.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(browser: org.cef.browser.CefBrowser?, frame: CefFrame?, httpStatusCode: Int) {
                if (frame == null || !frame.isMain) return
                injectBridge()
                pushState(monitorService.getLatestResult())
            }
        }, browserInstance.cefBrowser)

        browserInstance.loadURL(url)
        return browserInstance.component
    }

    private fun handleBranchCheckRequest(request: String): JsonObject {
        val requestJson = parseRequest(request)
        val head = requestJson["head"]?.toString()?.trim('"').orEmpty()
        val incoming = requestJson["incoming"]?.toString()?.trim('"').orEmpty()
        if (head.isBlank() || incoming.isBlank()) {
            branchCheckMessage = "Select both source and incoming branches."
            branchCheckConflicts = emptyList()
            pushState(monitorService.getLatestResult())
            return buildJsonObject { put("ok", JsonPrimitive(false)); put("message", JsonPrimitive(branchCheckMessage)) }
        }

        branchCheckHead = head
        branchCheckIncoming = incoming
        val includeWorkingTree = head == workingTreeOptionValue
        val oursRef = if (includeWorkingTree) previewService.currentBranchName().orEmpty().ifBlank { "HEAD" } else head

        branchCheckMessage = "Checking merge conflicts between $head and $incoming..."
        branchCheckConflicts = emptyList()
        pushState(monitorService.getLatestResult())

        val outcome = previewService.previewBetween(oursRef, incoming, includeWorkingTree = includeWorkingTree)
        when (outcome) {
            is MergePreviewOutcome.Success -> {
                branchCheckMessage = outcome.message
                branchCheckConflicts = outcome.conflicts.map { it.toUiConflict() }
            }
            is MergePreviewOutcome.Skipped -> {
                branchCheckMessage = outcome.message
                branchCheckConflicts = emptyList()
            }
            is MergePreviewOutcome.Failure -> {
                branchCheckMessage = outcome.message
                branchCheckConflicts = emptyList()
            }
        }
        pushState(monitorService.getLatestResult())

        return buildJsonObject {
            put("ok", JsonPrimitive(outcome !is MergePreviewOutcome.Failure))
            put("message", JsonPrimitive(branchCheckMessage))
            put("conflictCount", JsonPrimitive(branchCheckConflicts.size))
        }
    }

    private fun handleApplyResolutionRequest(request: String): JsonObject {
        val requestJson = parseRequest(request)
        val conflictId = requestJson["conflictId"]?.toString()?.trim('"').orEmpty()
        val mergedCode = requestJson["mergedCode"]?.toString()?.trim('"').orEmpty()

        if (conflictId.isBlank()) {
            return buildJsonObject {
                put("ok", JsonPrimitive(false))
                put("message", JsonPrimitive("Missing conflictId"))
            }
        }

        val result = applyMergedCode(conflictId, mergedCode)
        return buildJsonObject {
            put("ok", JsonPrimitive(result.first))
            put("message", JsonPrimitive(result.second))
        }
    }

    private fun parseRequest(raw: String): JsonObject {
        return runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { buildJsonObject { } }
    }

    private fun applyMergedCode(conflictId: String, mergedCode: String): Pair<Boolean, String> {
        val activeEditor = FileEditorManager.getInstance(project).selectedTextEditor ?: return false to "No active editor"
        val document = activeEditor.document
        val activeResult = monitorService.getLatestResult()
        val block = activeResult.blocks.firstOrNull { "active-${it.startLine}-${it.endLine}" == conflictId }
            ?: return false to "Conflict not found in active editor"

        val startLineIndex = (block.startLine - 1).coerceAtLeast(0)
        val endLineIndexExclusive = block.endLine.coerceAtMost(document.lineCount)
        if (startLineIndex >= document.lineCount || startLineIndex >= endLineIndexExclusive) {
            return false to "Conflict line range is out of bounds"
        }

        val startOffset = document.getLineStartOffset(startLineIndex)
        val endOffset = if (endLineIndexExclusive >= document.lineCount) document.textLength else document.getLineStartOffset(endLineIndexExclusive)

        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "Apply ConflictCourt Resolution", null, Runnable {
                document.replaceString(startOffset, endOffset, mergedCode)
            })
        }

        FileDocumentManager.getInstance().saveDocument(document)
        monitorService.overrideUploadStatus("Applied AI resolution to lines ${block.startLine}-${block.endLine}")
        monitorService.refreshForActiveEditor()
        return true to "Applied merged code"
    }

    private fun pushState(result: ConflictScanResult) {
        lastScanTimeMs = System.currentTimeMillis()
        ApplicationManager.getApplication().invokeLater {
            val browserInstance = browser ?: return@invokeLater
            val payload = createHostPayload(result, lastScanTimeMs, lastUploadStatus)
            val payloadString = json.encodeToString(JsonObject.serializer(), payload)
            val script = """
                window.__conflictCourtState = $payloadString;
                window.dispatchEvent(new CustomEvent('conflictcourt:state', { detail: $payloadString }));
                if (window.conflictCourtBridge && window.conflictCourtBridge._onHostUpdate) { window.conflictCourtBridge._onHostUpdate($payloadString); }
            """.trimIndent()
            browserInstance.cefBrowser.executeJavaScript(script, browserInstance.cefBrowser.url, 0)
            lastUploadStatus = result.uploadStatus
        }
    }

    private fun createHostPayload(result: ConflictScanResult, scanTimeMs: Long, previousUploadStatus: String?): JsonObject {
        val activeConflicts = result.blocks.map { block ->
            UiConflict(
                id = "active-${block.startLine}-${block.endLine}",
                filePath = result.filePath.orEmpty(),
                startLine = block.startLine,
                separatorLine = block.separatorLine,
                endLine = block.endLine,
                currentLabel = block.currentLabel,
                incomingLabel = block.incomingLabel,
                currentText = block.currentText,
                incomingText = block.incomingText,
                bufferAbove = contextForBlock(result.filePath, block, true),
                bufferBelow = contextForBlock(result.filePath, block, false),
                language = detectLanguage(result.filePath.orEmpty()),
                parentSignature = detectParentSignature(result.filePath, block.startLine),
                source = "active_editor"
            )
        }
        val effectiveConflicts = if (branchCheckConflicts.isNotEmpty()) branchCheckConflicts else activeConflicts

        val currentBranch = previewService.currentBranchName().orEmpty()
        val branches = previewService.listBranchRefs()
        val selectedHead = branchCheckHead ?: currentBranch.ifBlank { "HEAD" }
        val selectedIncoming = branchCheckIncoming ?: branches.firstOrNull { it != selectedHead }.orEmpty()

        return buildJsonObject {
            put("filePath", JsonPrimitive(result.filePath ?: ""))
            put("uploadStatus", JsonPrimitive(result.uploadStatus))
            put("previousUploadStatus", JsonPrimitive(previousUploadStatus ?: ""))
            put("hasConflicts", JsonPrimitive(result.hasConflicts))
            put("activeConflictCount", JsonPrimitive(activeConflicts.size))
            put("conflictCount", JsonPrimitive(effectiveConflicts.size))
            put("conflictSource", JsonPrimitive(if (branchCheckConflicts.isNotEmpty()) "branch_preview" else "active_editor"))
            put("lastScanTimeMs", JsonPrimitive(scanTimeMs))
            put("branchCheckMessage", JsonPrimitive(branchCheckMessage))
            put("currentBranch", JsonPrimitive(currentBranch))
            put("selectedHead", JsonPrimitive(selectedHead))
            put("selectedIncoming", JsonPrimitive(selectedIncoming))
            put("workingTreeOptionValue", JsonPrimitive(workingTreeOptionValue))
            put("webUrl", JsonPrimitive(WarRoomUrlProvider.currentUrl()))
            put("branches", buildJsonArray { branches.forEach { add(JsonPrimitive(it)) } })
            put(
                "conflicts",
                buildJsonArray {
                    effectiveConflicts.forEach { c ->
                        add(
                            buildJsonObject {
                                put("id", JsonPrimitive(c.id))
                                put("filePath", JsonPrimitive(c.filePath))
                                put("startLine", JsonPrimitive(c.startLine))
                                put("separatorLine", JsonPrimitive(c.separatorLine))
                                put("endLine", JsonPrimitive(c.endLine))
                                put("currentLabel", JsonPrimitive(c.currentLabel))
                                put("incomingLabel", JsonPrimitive(c.incomingLabel))
                                put("currentText", JsonPrimitive(c.currentText))
                                put("incomingText", JsonPrimitive(c.incomingText))
                                put("bufferAbove", JsonPrimitive(c.bufferAbove))
                                put("bufferBelow", JsonPrimitive(c.bufferBelow))
                                put("language", JsonPrimitive(c.language))
                                put("parentSignature", JsonPrimitive(c.parentSignature))
                                put("source", JsonPrimitive(c.source))
                            }
                        )
                    }
                }
            )
        }
    }

    private fun contextForBlock(filePath: String?, block: com.conflictcourt.conflicts.ConflictBlock, above: Boolean): String {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return "[context unavailable]"
        if (filePath.isNullOrBlank()) return "[context unavailable]"
        val activePath = FileDocumentManager.getInstance().getFile(editor.document)?.path
        if (activePath != filePath) return "[context unavailable]"
        val lines = editor.document.text.lines()
        if (lines.isEmpty()) return "[context unavailable]"
        return if (above) {
            val s = (block.startLine - 6).coerceAtLeast(0)
            val e = (block.startLine - 1).coerceAtLeast(0)
            lines.subList(s, e).joinToString("\n").ifBlank { "[no context]" }
        } else {
            val s = block.endLine.coerceAtMost(lines.size)
            val e = (block.endLine + 5).coerceAtMost(lines.size)
            lines.subList(s, e).joinToString("\n").ifBlank { "[no context]" }
        }
    }

    private fun detectParentSignature(filePath: String?, conflictStartLine: Int): String {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return "Unknown"
        if (filePath.isNullOrBlank()) return "Unknown"
        val activePath = FileDocumentManager.getInstance().getFile(editor.document)?.path
        if (activePath != filePath) return "Unknown"
        val lines = editor.document.text.lines()
        val startIndex = (conflictStartLine - 2).coerceAtLeast(0).coerceAtMost(lines.lastIndex)
        for (i in startIndex downTo 0) {
            val t = lines[i].trim()
            if (
                t.startsWith("fun ") || t.startsWith("class ") || t.startsWith("object ") || t.startsWith("def ") ||
                t.startsWith("function ") || t.matches(Regex(".*\\)\\s*\\{\\s*$"))
            ) return t
        }
        return "Unknown"
    }

    private fun detectLanguage(filePath: String): String {
        return when (filePath.substringAfterLast('.', "").lowercase()) {
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

    private fun injectBridge() {
        val browserInstance = browser ?: return
        val getStateInject = getStateQuery?.inject("request", "resolve", "reject") ?: return
        val runBranchInject = runBranchCheckQuery?.inject("request", "resolve", "reject") ?: return
        val applyInject = applyResolutionQuery?.inject("request", "resolve", "reject") ?: return

        val script = """
            (function() {
              function parseOrRaw(v) { try { return JSON.parse(v); } catch (_) { return v; } }
              window.conflictCourtBridge = {
                getCurrentConflictState: function() {
                  return new Promise(function(resolve, reject) {
                    var request = "{}";
                    $getStateInject
                  }).then(parseOrRaw);
                },
                runBranchCheck: function(head, incoming) {
                  return new Promise(function(resolve, reject) {
                    var request = JSON.stringify({ head: head || "", incoming: incoming || "" });
                    $runBranchInject
                  }).then(parseOrRaw);
                },
                applyResolvedCode: function(conflictId, mergedCode) {
                  return new Promise(function(resolve, reject) {
                    var request = JSON.stringify({ conflictId: conflictId || "", mergedCode: mergedCode || "" });
                    $applyInject
                  }).then(parseOrRaw);
                },
                subscribe: function(handler) {
                  var listener = function(e) { handler(e.detail); };
                  window.addEventListener('conflictcourt:state', listener);
                  return function() { window.removeEventListener('conflictcourt:state', listener); };
                },
                _onHostUpdate: null
              };
              window.dispatchEvent(new CustomEvent('conflictcourt:bridge-ready'));
            })();
        """.trimIndent()
        browserInstance.cefBrowser.executeJavaScript(script, browserInstance.cefBrowser.url, 0)
    }

    private fun createBrowser(): JBCefBrowser? {
        if (!JBCefApp.isSupported()) return null
        return JBCefBrowser("about:blank")
    }

    private fun fallbackBrowserPanel(): JComponent {
        val fallbackText = JTextArea(
            """
            JCEF is not available in this IDE session.

            ConflictCourt webview requires JCEF.
            Configure CONFLICTCOURT_WEB_URL and run in a normal desktop IntelliJ session.
            """.trimIndent()
        ).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }
        return JPanel(BorderLayout()).apply { add(fallbackText, BorderLayout.CENTER) }
    }

    private fun com.conflictcourt.git.MergePreviewConflict.toUiConflict(): UiConflict {
        val lineCount = maxOf(1, codeHead.lines().size)
        return UiConflict(
            id = "branch-${filePath}-${codeHead.hashCode()}-${codeIncoming.hashCode()}",
            filePath = filePath,
            startLine = 1,
            separatorLine = 1 + lineCount,
            endLine = 1 + lineCount + maxOf(1, codeIncoming.lines().size),
            currentLabel = "source",
            incomingLabel = "incoming",
            currentText = codeHead,
            incomingText = codeIncoming,
            bufferAbove = bufferAbove.ifBlank { "[no context]" },
            bufferBelow = bufferBelow.ifBlank { "[no context]" },
            language = language.ifBlank { "Unknown" },
            parentSignature = parentSignature ?: "Unknown",
            source = "branch_preview"
        )
    }

    override fun removeNotify() {
        monitorService.removeListener(updateListener)
        getStateQuery?.let { Disposer.dispose(it) }
        runBranchCheckQuery?.let { Disposer.dispose(it) }
        applyResolutionQuery?.let { Disposer.dispose(it) }
        browser?.let { Disposer.dispose(it) }
        super.removeNotify()
    }
}

private data class UiConflict(
    val id: String,
    val filePath: String,
    val startLine: Int,
    val separatorLine: Int,
    val endLine: Int,
    val currentLabel: String,
    val incomingLabel: String,
    val currentText: String,
    val incomingText: String,
    val bufferAbove: String,
    val bufferBelow: String,
    val language: String,
    val parentSignature: String,
    val source: String
)
