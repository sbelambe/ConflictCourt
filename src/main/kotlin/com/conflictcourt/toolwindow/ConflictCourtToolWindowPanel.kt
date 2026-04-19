package com.conflictcourt.toolwindow

import com.conflictcourt.conflicts.ConflictBlock
import com.conflictcourt.conflicts.ConflictMonitorService
import com.conflictcourt.conflicts.ConflictScanResult
import com.conflictcourt.git.GitMergePreviewService
import com.conflictcourt.git.MergePreviewConflict
import com.conflictcourt.git.MergePreviewOutcome
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBPanel
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.concurrency.AppExecutorUtil
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

class ConflictCourtToolWindowPanel(private val project: Project) : JBPanel<ConflictCourtToolWindowPanel>(BorderLayout()) {
    private val workingTreeOptionValue = "__WORKING_TREE__"
    private val monitorService = project.getService(ConflictMonitorService::class.java)
    private val previewService = project.getService(GitMergePreviewService::class.java)
    private val browser = createBrowser()
    private var branchCheckQuery: JBCefJSQuery? = null
    private val json = Json { prettyPrint = false }
    private val executor = AppExecutorUtil.getAppExecutorService()

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
        val browserInstance = browser
        if (browserInstance != null) {
            val query = JBCefJSQuery.create(browserInstance).also { created ->
                created.addHandler { request ->
                    handleBranchCheckRequest(request)
                    null
                }
            }
            branchCheckQuery = query
            val injected = query.inject("request")
            browserInstance.loadHTML(buildWebviewHtml(injected))
            return browserInstance.component
        }
        return fallbackBrowserPanel()
    }

    private fun handleBranchCheckRequest(request: String) {
        val requestJson = runCatching { json.parseToJsonElement(request).jsonObject }.getOrNull() ?: return
        val head = requestJson["head"]?.toString()?.trim('"').orEmpty()
        val incoming = requestJson["incoming"]?.toString()?.trim('"').orEmpty()

        if (head.isBlank() || incoming.isBlank()) {
            branchCheckMessage = "Select both source and incoming branches."
            branchCheckConflicts = emptyList()
            pushState(monitorService.getLatestResult())
            return
        }

        branchCheckHead = head
        branchCheckIncoming = incoming
        branchCheckMessage = "Checking merge conflicts between $head and $incoming..."
        branchCheckConflicts = emptyList()
        pushState(monitorService.getLatestResult())

        executor.execute {
        val includeWorkingTree = head == workingTreeOptionValue
        val oursRef = if (includeWorkingTree) {
            previewService.currentBranchName().orEmpty().ifBlank { "HEAD" }
        } else {
            head
        }

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
        }
    }

    private fun pushState(result: ConflictScanResult) {
        lastScanTimeMs = System.currentTimeMillis()
        ApplicationManager.getApplication().invokeLater {
            val browserInstance = browser ?: return@invokeLater
            val payload = createHostPayload(result, lastScanTimeMs, lastUploadStatus)
            val payloadString = json.encodeToString(JsonObject.serializer(), payload)
            val script = "window.conflictCourtHostUpdate && window.conflictCourtHostUpdate($payloadString);"
            browserInstance.cefBrowser.executeJavaScript(script, browserInstance.cefBrowser.url, 0)
            lastUploadStatus = result.uploadStatus
        }
    }

    private fun createHostPayload(result: ConflictScanResult, scanTimeMs: Long, previousUploadStatus: String?): JsonObject {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        val lines = editor?.document?.text?.lines().orEmpty()
        val language = detectLanguage(result.filePath.orEmpty())
        val currentBranch = previewService.currentBranchName().orEmpty()
        val branches = previewService.listBranchRefs()
        val selectedHead = branchCheckHead ?: currentBranch.ifBlank { "HEAD" }
        val selectedIncoming = branchCheckIncoming ?: branches.firstOrNull { it != selectedHead }.orEmpty()
        val effectiveConflicts = if (branchCheckConflicts.isNotEmpty()) {
            branchCheckConflicts
        } else {
            result.blocks.mapIndexed { index, block ->
                val context = computeContext(lines, block)
                UiConflict(
                    id = "conflict-${index + 1}-${block.startLine}-${block.endLine}",
                    startLine = block.startLine,
                    separatorLine = block.separatorLine,
                    endLine = block.endLine,
                    currentLabel = block.currentLabel,
                    incomingLabel = block.incomingLabel,
                    currentText = block.currentText,
                    incomingText = block.incomingText,
                    bufferAbove = context.first,
                    bufferBelow = context.second,
                    language = language,
                    parentSignature = detectParentSignature(lines, block.startLine)
                )
            }
        }

        val conflictsArray = buildJsonArray {
            effectiveConflicts.forEach { conflict ->
                add(
                    buildJsonObject {
                        put("id", JsonPrimitive(conflict.id))
                        put("startLine", JsonPrimitive(conflict.startLine))
                        put("separatorLine", JsonPrimitive(conflict.separatorLine))
                        put("endLine", JsonPrimitive(conflict.endLine))
                        put("currentLabel", JsonPrimitive(conflict.currentLabel))
                        put("incomingLabel", JsonPrimitive(conflict.incomingLabel))
                        put("currentText", JsonPrimitive(conflict.currentText))
                        put("incomingText", JsonPrimitive(conflict.incomingText))
                        put("bufferAbove", JsonPrimitive(conflict.bufferAbove))
                        put("bufferBelow", JsonPrimitive(conflict.bufferBelow))
                        put("language", JsonPrimitive(conflict.language))
                        put("parentSignature", JsonPrimitive(conflict.parentSignature))
                    }
                )
            }
        }

        return buildJsonObject {
            put("filePath", JsonPrimitive(result.filePath ?: ""))
            put("uploadStatus", JsonPrimitive(result.uploadStatus))
            put("previousUploadStatus", JsonPrimitive(previousUploadStatus ?: ""))
            put("hasConflicts", JsonPrimitive(result.hasConflicts))
            put("activeConflictCount", JsonPrimitive(result.blocks.size))
            put("conflictCount", JsonPrimitive(effectiveConflicts.size))
            put("conflictSource", JsonPrimitive(if (branchCheckConflicts.isNotEmpty()) "branch_preview" else "active_editor"))
            put("lastScanTimeMs", JsonPrimitive(scanTimeMs))
            put("branchCheckMessage", JsonPrimitive(branchCheckMessage))
            put("currentBranch", JsonPrimitive(currentBranch))
            put("selectedHead", JsonPrimitive(selectedHead))
            put("selectedIncoming", JsonPrimitive(selectedIncoming))
            put("workingTreeOptionValue", JsonPrimitive(workingTreeOptionValue))
            put(
                "branches",
                buildJsonArray {
                    branches.forEach { add(JsonPrimitive(it)) }
                }
            )
            put("conflicts", conflictsArray)
        }
    }

    private fun computeContext(lines: List<String>, block: ConflictBlock): Pair<String, String> {
        if (lines.isEmpty()) {
            return "[context unavailable]" to "[context unavailable]"
        }

        val aboveStart = (block.startLine - 6).coerceAtLeast(0)
        val aboveEnd = (block.startLine - 1).coerceAtLeast(0)
        val belowStart = block.endLine.coerceAtMost(lines.size)
        val belowEnd = (block.endLine + 5).coerceAtMost(lines.size)

        val above = lines.subList(aboveStart, aboveEnd).joinToString("\n").ifBlank { "[no context]" }
        val below = lines.subList(belowStart, belowEnd).joinToString("\n").ifBlank { "[no context]" }
        return above to below
    }

    private fun detectParentSignature(lines: List<String>, conflictStartLine: Int): String {
        if (lines.isEmpty()) return "Unknown"

        val startIndex = (conflictStartLine - 2).coerceAtLeast(0)
        for (i in startIndex downTo 0) {
            val trimmed = lines[i].trim()
            if (trimmed.isBlank()) continue
            if (
                trimmed.startsWith("fun ") ||
                trimmed.startsWith("class ") ||
                trimmed.startsWith("object ") ||
                trimmed.startsWith("def ") ||
                trimmed.startsWith("function ") ||
                trimmed.matches(Regex(".*\\)\\s*\\{\\s*$"))
            ) {
                return trimmed
            }
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

    private fun createBrowser(): JBCefBrowser? {
        if (!JBCefApp.isSupported()) {
            return null
        }
        return JBCefBrowser("about:blank")
    }

    private fun fallbackBrowserPanel(): JComponent {
        val fallbackText = JTextArea(
            """
            JCEF is not available in this IDE session.

            ConflictCourt webview requires JCEF.
            Run this plugin in a standard desktop IntelliJ IDEA session to use the full V1 ConflictCourt UI.
            """.trimIndent()
        ).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
        }

        return JPanel(BorderLayout()).apply {
            add(fallbackText, BorderLayout.CENTER)
        }
    }

    private fun buildWebviewHtml(branchCheckInjectJs: String): String {
        return """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1" />
          <title>ConflictCourt</title>
          <style>
            :root {
              --bg: #0e141b;
              --surface: #131d27;
              --surface-2: #1a2634;
              --card: #0f1a24;
              --text: #ebf2fa;
              --muted: #9eb0c2;
              --accent: #4dc4a2;
              --warn: #f0b35b;
              --danger: #f97f7f;
              --border: #29394b;
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              font-family: "IBM Plex Sans", "Segoe UI", system-ui, sans-serif;
              background: radial-gradient(circle at 10% 0%, #1b2c3f 0%, var(--bg) 42%);
              color: var(--text);
              height: 100vh;
              overflow: auto;
            }
            .app { display: grid; grid-template-rows: auto auto 1fr auto; min-height: 100vh; }
            .top {
              padding: 12px 16px;
              border-bottom: 1px solid var(--border);
              background: rgba(10, 17, 24, 0.78);
              backdrop-filter: blur(8px);
              display: grid;
              grid-template-columns: repeat(5, minmax(0, 1fr));
              gap: 8px;
            }
            .branch-controls {
              padding: 10px 16px;
              border-bottom: 1px solid var(--border);
              background: rgba(14, 24, 34, 0.88);
              display: grid;
              grid-template-columns: 1fr 1fr auto;
              gap: 8px;
              align-items: center;
            }
            .branch-message {
              grid-column: 1 / -1;
              font-size: 12px;
              color: #cbe0f2;
              background: #122030;
              border: 1px solid var(--border);
              border-radius: 8px;
              padding: 6px 8px;
            }
            select {
              border: 1px solid var(--border);
              border-radius: 8px;
              background: #162331;
              color: var(--text);
              font-size: 12px;
              padding: 8px;
              width: 100%;
            }
            .metric {
              background: var(--surface);
              border: 1px solid var(--border);
              border-radius: 10px;
              padding: 8px 10px;
            }
            .metric .k { font-size: 11px; text-transform: uppercase; color: var(--muted); letter-spacing: 0.04em; }
            .metric .v { font-size: 13px; margin-top: 3px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
            .main {
              display: grid;
              grid-template-columns: 340px 1fr;
              gap: 0;
              min-height: 0;
              overflow: auto;
            }
            .list {
              border-right: 1px solid var(--border);
              padding: 10px;
              overflow: auto;
              background: linear-gradient(180deg, #122030 0%, #101824 100%);
            }
            .detail {
              min-width: 0;
              display: grid;
              grid-template-rows: auto auto 1fr auto;
              min-height: 0;
            }
            .section-title {
              font-size: 11px;
              letter-spacing: 0.08em;
              text-transform: uppercase;
              color: var(--muted);
              margin: 0 0 8px;
            }
            .conflict-card {
              border: 1px solid var(--border);
              background: rgba(11, 19, 28, 0.8);
              border-radius: 10px;
              padding: 10px;
              margin-bottom: 8px;
              cursor: pointer;
            }
            .conflict-card.active { border-color: var(--accent); box-shadow: inset 0 0 0 1px rgba(77,196,162,.35); }
            .row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
            .muted { color: var(--muted); font-size: 12px; }
            .snippet {
              margin-top: 8px;
              font-family: "JetBrains Mono", "SFMono-Regular", Menlo, monospace;
              font-size: 11px;
              color: #d8e8f8;
              background: #0b141d;
              border: 1px solid #1f2d3d;
              border-radius: 8px;
              padding: 8px;
              white-space: pre-wrap;
              max-height: 72px;
              overflow: hidden;
            }
            .badge {
              border: 1px solid transparent;
              border-radius: 999px;
              padding: 2px 8px;
              font-size: 11px;
              text-transform: uppercase;
            }
            .pending { background: rgba(240, 179, 91, .14); border-color: rgba(240,179,91,.45); color: var(--warn); }
            .reviewed { background: rgba(77, 196, 162, .12); border-color: rgba(77,196,162,.4); color: var(--accent); }
            .resolved { background: rgba(120, 212, 255, .12); border-color: rgba(120,212,255,.4); color: #8bd5ff; }
            .detail-head { padding: 12px 14px 0; }
            .meta-grid {
              margin-top: 8px;
              display: grid;
              grid-template-columns: repeat(4, minmax(0, 1fr));
              gap: 8px;
            }
            .meta-grid .metric { padding: 8px; }
            .compare {
              padding: 12px 14px;
              display: grid;
              grid-template-columns: 1fr 1fr;
              gap: 10px;
              min-height: 0;
            }
            .pane {
              background: var(--card);
              border: 1px solid var(--border);
              border-radius: 10px;
              overflow: hidden;
              min-height: 190px;
              display: grid;
              grid-template-rows: auto 1fr;
            }
            .pane h4 {
              margin: 0;
              padding: 8px 10px;
              font-size: 12px;
              border-bottom: 1px solid var(--border);
              background: #122030;
            }
            pre {
              margin: 0;
              padding: 10px;
              font-size: 12px;
              line-height: 1.45;
              font-family: "JetBrains Mono", "SFMono-Regular", Menlo, monospace;
              white-space: pre-wrap;
              overflow: auto;
            }
            .ai {
              border-top: 1px solid var(--border);
              background: rgba(17, 28, 40, 0.85);
              padding: 10px 14px 14px;
            }
            textarea {
              width: 100%;
              min-height: 110px;
              resize: vertical;
              border-radius: 10px;
              border: 1px solid var(--border);
              background: #0f1822;
              color: var(--text);
              padding: 10px;
              font-family: "JetBrains Mono", "SFMono-Regular", Menlo, monospace;
              font-size: 12px;
            }
            .actions { margin-top: 8px; display: flex; gap: 8px; flex-wrap: wrap; }
            button {
              border: 1px solid var(--border);
              border-radius: 8px;
              background: #162331;
              color: var(--text);
              font-size: 12px;
              padding: 7px 10px;
              cursor: pointer;
            }
            button.primary { background: #1e5f53; border-color: #267767; }
            .timeline {
              border-top: 1px solid var(--border);
              background: #0d1620;
              padding: 10px 14px;
              max-height: 150px;
              overflow: auto;
            }
            .event { font-size: 12px; color: #c6d6e8; margin: 5px 0; }
            .event .ts { color: var(--muted); margin-right: 6px; }
            .empty {
              padding: 16px;
              color: var(--muted);
              font-size: 13px;
            }
            @media (max-width: 980px) {
              .top { grid-template-columns: 1fr 1fr; }
              .branch-controls { grid-template-columns: 1fr; }
              .main { grid-template-columns: 1fr; }
              .list { max-height: 36vh; border-right: 0; border-bottom: 1px solid var(--border); }
              .meta-grid { grid-template-columns: 1fr 1fr; }
              .compare { grid-template-columns: 1fr; }
            }
          </style>
        </head>
        <body>
          <div class="app">
            <div class="top">
              <div class="metric"><div class="k">Active File</div><div class="v" id="m-file">No active editor</div></div>
              <div class="metric"><div class="k">Displayed Conflicts</div><div class="v" id="m-count">0</div></div>
              <div class="metric"><div class="k">Editor Conflicts</div><div class="v" id="m-active-count">0</div></div>
              <div class="metric"><div class="k">Export/Upload</div><div class="v" id="m-upload">Waiting</div></div>
              <div class="metric"><div class="k">Last Scan</div><div class="v" id="m-scan">Never</div></div>
            </div>
            <div class="branch-controls">
              <select id="branch-head" onchange="onBranchSelectionChanged()"></select>
              <select id="branch-incoming" onchange="onBranchSelectionChanged()"></select>
              <button class="primary" onclick="runBranchCheck()">Check Merge Conflicts</button>
              <div class="branch-message" id="branch-message">Branch comparison not run yet.</div>
            </div>
            <div class="main">
              <aside class="list">
                <p class="section-title">Conflict Blocks</p>
                <div id="conflict-list"></div>
              </aside>
              <section class="detail">
                <div class="detail-head">
                  <p class="section-title">Conflict Detail</p>
                  <div id="detail-head"></div>
                  <div class="meta-grid" id="meta-grid"></div>
                </div>
                <div class="compare">
                  <div class="pane"><h4>Current (HEAD/source)</h4><pre id="pane-head"></pre></div>
                  <div class="pane"><h4>Incoming</h4><pre id="pane-incoming"></pre></div>
                </div>
                <div class="compare" style="padding-top:0;">
                  <div class="pane"><h4>Context Above</h4><pre id="pane-above"></pre></div>
                  <div class="pane"><h4>Context Below</h4><pre id="pane-below"></pre></div>
                </div>
                <div class="ai">
                  <p class="section-title">AI Resolution (Mock)</p>
                  <textarea id="ai-draft" placeholder="Generated merge draft appears here..."></textarea>
                  <div class="actions">
                    <button class="primary" onclick="generateMock()">Generate</button>
                    <button onclick="acceptDraft()">Accept</button>
                    <button onclick="editDraft()">Edit</button>
                    <button onclick="rejectDraft()">Reject</button>
                  </div>
                </div>
              </section>
            </div>
            <div class="timeline">
              <p class="section-title">Activity</p>
              <div id="timeline"></div>
            </div>
          </div>
          <script>
            const state = {
              host: null,
              selectedId: null,
              localStatus: {},
              drafts: {},
              activity: [],
              branchHead: "",
              branchIncoming: ""
            };

            function tsNow() {
              const d = new Date();
              return d.toLocaleTimeString();
            }

            function addActivity(message) {
              state.activity.unshift({ ts: tsNow(), message });
              state.activity = state.activity.slice(0, 50);
              renderTimeline();
            }

            function statusOf(id) {
              return state.localStatus[id] || "pending";
            }

            function escapeHtml(input) {
              return String(input || "")
                .replaceAll("&", "&amp;")
                .replaceAll("<", "&lt;")
                .replaceAll(">", "&gt;");
            }

            function renderTop() {
              const host = state.host || {};
              document.getElementById("m-file").textContent = host.filePath || "No active editor";
              document.getElementById("m-count").textContent = String(host.conflictCount || 0);
              document.getElementById("m-active-count").textContent = String(host.activeConflictCount || 0);
              document.getElementById("m-upload").textContent = host.uploadStatus || "Waiting";
              document.getElementById("branch-message").textContent = host.branchCheckMessage || "Branch comparison not run yet.";
              if (host.lastScanTimeMs) {
                document.getElementById("m-scan").textContent = new Date(host.lastScanTimeMs).toLocaleTimeString();
              } else {
                document.getElementById("m-scan").textContent = "Never";
              }
            }

            function renderBranchControls() {
              const host = state.host || {};
              const branches = host.branches || [];
              const headSelect = document.getElementById("branch-head");
              const incomingSelect = document.getElementById("branch-incoming");

              const selectedHead = state.branchHead || host.selectedHead || host.currentBranch || "HEAD";
              const selectedIncoming = state.branchIncoming || host.selectedIncoming || "";
              state.branchHead = selectedHead;
              state.branchIncoming = selectedIncoming;
              const workingTreeValue = host.workingTreeOptionValue || "__WORKING_TREE__";

              const headOptions = [
                '<option value="' + workingTreeValue + '"' + (selectedHead === workingTreeValue ? ' selected' : '') + '>Uncommitted changes (working tree)</option>'
              ].concat(branches.map((b) => {
                const selected = b === selectedHead ? ' selected' : '';
                return '<option value="' + escapeHtml(b) + '"' + selected + '>' + escapeHtml(b) + '</option>';
              })).join("");

              const incomingOptions = branches.map((b) => {
                const selected = b === selectedIncoming ? ' selected' : '';
                return '<option value="' + escapeHtml(b) + '"' + selected + '>' + escapeHtml(b) + '</option>';
              }).join("");

              headSelect.innerHTML = headOptions || '<option value="">No branches found</option>';
              incomingSelect.innerHTML = incomingOptions || '<option value="">No branches found</option>';
            }

            function onBranchSelectionChanged() {
              state.branchHead = document.getElementById("branch-head").value;
              state.branchIncoming = document.getElementById("branch-incoming").value;
            }

            function runBranchCheck() {
              onBranchSelectionChanged();
              if (!state.branchHead || !state.branchIncoming) {
                addActivity("Select both branches before running check");
                return;
              }
              addActivity("Checking branches: " + state.branchHead + " vs " + state.branchIncoming);
              const request = JSON.stringify({ head: state.branchHead, incoming: state.branchIncoming });
              window.conflictCourtBranchCheck(request);
            }

            function renderList() {
              const host = state.host || {};
              const list = document.getElementById("conflict-list");
              const conflicts = host.conflicts || [];
              if (!conflicts.length) {
                list.innerHTML = '<div class="empty">No conflict data available. Open a conflicted file or run branch check.</div>';
                return;
              }
              list.innerHTML = conflicts.map((c) => {
                const active = c.id === state.selectedId ? "active" : "";
                const status = statusOf(c.id);
                const snippet = (c.currentText || c.incomingText || "").split("\n").slice(0, 4).join("\n");
                return '<div class="conflict-card ' + active + '" onclick="selectConflict(\'' + c.id + '\')">' +
                  '<div class="row">' +
                    '<strong>Lines ' + c.startLine + '-' + c.endLine + '</strong>' +
                    '<span class="badge ' + status + '">' + status + '</span>' +
                  '</div>' +
                  '<div class="muted">' + escapeHtml(c.currentLabel) + ' vs ' + escapeHtml(c.incomingLabel) + '</div>' +
                  '<div class="snippet">' + escapeHtml(snippet || "[empty block]") + '</div>' +
                '</div>';
              }).join("");
            }

            function selectedConflict() {
              const conflicts = (state.host && state.host.conflicts) || [];
              return conflicts.find((c) => c.id === state.selectedId) || null;
            }

            function renderDetail() {
              const c = selectedConflict();
              if (!c) {
                document.getElementById("detail-head").innerHTML = '<div class="empty">Select a conflict block to inspect details.</div>';
                document.getElementById("meta-grid").innerHTML = "";
                document.getElementById("pane-head").textContent = "";
                document.getElementById("pane-incoming").textContent = "";
                document.getElementById("pane-above").textContent = "";
                document.getElementById("pane-below").textContent = "";
                document.getElementById("ai-draft").value = "";
                return;
              }
              document.getElementById("detail-head").innerHTML =
                '<strong>Conflict ' + c.startLine + '-' + c.endLine + '</strong> ' +
                '<span class="muted">(' + escapeHtml(c.currentLabel) + ' vs ' + escapeHtml(c.incomingLabel) + ')</span>';
              document.getElementById("meta-grid").innerHTML =
                '<div class="metric"><div class="k">Language</div><div class="v">' + escapeHtml(c.language || "Unknown") + '</div></div>' +
                '<div class="metric"><div class="k">Parent Signature</div><div class="v">' + escapeHtml(c.parentSignature || "Unknown") + '</div></div>' +
                '<div class="metric"><div class="k">Start/End</div><div class="v">' + c.startLine + ' / ' + c.endLine + '</div></div>' +
                '<div class="metric"><div class="k">Status</div><div class="v">' + statusOf(c.id) + '</div></div>';
              document.getElementById("pane-head").textContent = c.currentText || "[empty]";
              document.getElementById("pane-incoming").textContent = c.incomingText || "[empty]";
              document.getElementById("pane-above").textContent = c.bufferAbove || "[no context]";
              document.getElementById("pane-below").textContent = c.bufferBelow || "[no context]";
              document.getElementById("ai-draft").value = state.drafts[c.id] || "";
            }

            function renderTimeline() {
              const t = document.getElementById("timeline");
              if (!state.activity.length) {
                t.innerHTML = '<div class="empty">No activity yet.</div>';
                return;
              }
              t.innerHTML = state.activity.map((e) => {
                return '<div class="event"><span class="ts">[' + e.ts + ']</span>' + escapeHtml(e.message) + '</div>';
              }).join("");
            }

            function render() {
              renderTop();
              renderBranchControls();
              renderList();
              renderDetail();
              renderTimeline();
            }

            function selectConflict(id) {
              state.selectedId = id;
              render();
            }

            function mockMergeText(conflict) {
              const head = (conflict.currentText || "").trim();
              const incoming = (conflict.incomingText || "").trim();
              if (!head) return incoming;
              if (!incoming) return head;
              if (head === incoming) return head;
              return [
                head,
                "",
                "// --- merged with incoming changes ---",
                incoming
              ].join("\n");
            }

            function generateMock() {
              const c = selectedConflict();
              if (!c) return;
              state.drafts[c.id] = mockMergeText(c);
              state.localStatus[c.id] = "reviewed";
              addActivity("Mock AI generated draft for lines " + c.startLine + "-" + c.endLine);
              render();
            }

            function acceptDraft() {
              const c = selectedConflict();
              if (!c) return;
              state.drafts[c.id] = document.getElementById("ai-draft").value;
              state.localStatus[c.id] = "resolved";
              addActivity("Accepted draft for lines " + c.startLine + "-" + c.endLine);
              render();
            }

            function editDraft() {
              const c = selectedConflict();
              if (!c) return;
              state.drafts[c.id] = document.getElementById("ai-draft").value;
              state.localStatus[c.id] = "reviewed";
              addActivity("Edited draft for lines " + c.startLine + "-" + c.endLine);
              render();
            }

            function rejectDraft() {
              const c = selectedConflict();
              if (!c) return;
              state.drafts[c.id] = "";
              state.localStatus[c.id] = "pending";
              addActivity("Rejected draft for lines " + c.startLine + "-" + c.endLine);
              render();
            }

            window.conflictCourtBranchCheck = function(request) {
              __BRANCH_CHECK_INJECT__
            };

            window.conflictCourtHostUpdate = (payload) => {
              const hadHost = !!state.host;
              const previousMessage = state.host && state.host.branchCheckMessage ? state.host.branchCheckMessage : "";
              state.host = payload || {};
              const conflicts = state.host.conflicts || [];
              if (!state.selectedId || !conflicts.some((c) => c.id === state.selectedId)) {
                state.selectedId = conflicts.length ? conflicts[0].id : null;
              }
              if (!hadHost) {
                addActivity("ConflictCourt webview initialized");
              }
              addActivity("Scanned active editor: " + (state.host.activeConflictCount || 0) + " conflict block(s)");
              if (state.host.uploadStatus && state.host.uploadStatus !== state.host.previousUploadStatus) {
                addActivity(state.host.uploadStatus);
              }
              if (state.host.branchCheckMessage && state.host.branchCheckMessage !== previousMessage) {
                addActivity(state.host.branchCheckMessage);
              }
              render();
            };

            render();
          </script>
        </body>
        </html>
        """.trimIndent().replace("__BRANCH_CHECK_INJECT__", branchCheckInjectJs)
    }

    private fun MergePreviewConflict.toUiConflict(): UiConflict {
        val lineCount = maxOf(1, codeHead.lines().size)
        return UiConflict(
            id = "branch-${filePath}-${codeHead.hashCode()}-${codeIncoming.hashCode()}",
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
            parentSignature = parentSignature ?: "Unknown"
        )
    }

    override fun removeNotify() {
        monitorService.removeListener(updateListener)
        branchCheckQuery?.let { Disposer.dispose(it) }
        browser?.let { Disposer.dispose(it) }
        super.removeNotify()
    }
}

private data class UiConflict(
    val id: String,
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
    val parentSignature: String
)
