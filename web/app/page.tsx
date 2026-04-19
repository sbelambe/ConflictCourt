"use client";

import { useEffect, useRef, useState } from "react";
import {
  type ConflictItem,
  type HostState,
  getBridge
} from "../lib/conflictcourt-bridge";

type StatusTag = "pending" | "reviewed" | "resolved";
type TimelineEvent = { ts: string; message: string };

const fallbackState: HostState = {
  filePath: "No active editor",
  uploadStatus: "Waiting for plugin state",
  previousUploadStatus: "",
  hasConflicts: false,
  activeConflictCount: 0,
  conflictCount: 0,
  conflictSource: "active_editor",
  lastScanTimeMs: Date.now(),
  branchCheckMessage: "Run inside ConflictCourt plugin to connect real state.",
  currentBranch: "",
  selectedHead: "",
  selectedIncoming: "",
  workingTreeOptionValue: "__WORKING_TREE__",
  webUrl: "",
  branches: [],
  conflicts: []
};

function nowTime() {
  return new Date().toLocaleTimeString();
}

export default function Page() {
  const [state, setState] = useState<HostState>(fallbackState);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [branchHead, setBranchHead] = useState("");
  const [branchIncoming, setBranchIncoming] = useState("");
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [statuses, setStatuses] = useState<Record<string, StatusTag>>({});
  const [timeline, setTimeline] = useState<TimelineEvent[]>([]);
  const unsubscribeRef = useRef<(() => void) | null>(null);

  const selected = state.conflicts.find((c) => c.id === selectedId) ?? null;

  useEffect(() => {
    const addEvent = (message: string) => {
      setTimeline((prev) => [{ ts: nowTime(), message }, ...prev].slice(0, 60));
    };

    const syncState = (next: HostState) => {
      setState(next);
      setBranchHead((prev) => prev || next.selectedHead || next.currentBranch || "");
      setBranchIncoming((prev) => prev || next.selectedIncoming || "");
      setSelectedId((prev) => {
        if (prev && next.conflicts.some((c) => c.id === prev)) return prev;
        return next.conflicts[0]?.id ?? null;
      });
      addEvent(`Scanned active editor: ${next.activeConflictCount} conflict block(s)`);
      if (next.uploadStatus && next.uploadStatus !== next.previousUploadStatus) {
        addEvent(next.uploadStatus);
      }
      if (next.branchCheckMessage) addEvent(next.branchCheckMessage);
    };

    const initBridge = async () => {
      const bridge = getBridge();
      if (!bridge) {
        addEvent("Bridge not detected. Showing fallback view.");
        return;
      }

      addEvent("ConflictCourt bridge connected");
      const current = await bridge.getCurrentConflictState();
      syncState(current);
      unsubscribeRef.current = bridge.subscribe(syncState);
    };

    const onBridgeReady = () => void initBridge();
    window.addEventListener("conflictcourt:bridge-ready", onBridgeReady);
    void initBridge();

    return () => {
      window.removeEventListener("conflictcourt:bridge-ready", onBridgeReady);
      unsubscribeRef.current?.();
      unsubscribeRef.current = null;
    };
  }, []);

  const workingTreeOption = state.workingTreeOptionValue || "__WORKING_TREE__";
  const headOptions = [
    { value: workingTreeOption, label: "Uncommitted changes (working tree)" },
    ...state.branches.map((branch) => ({ value: branch, label: branch }))
  ];
  const incomingOptions = state.branches.map((branch) => ({ value: branch, label: branch }));

  const selectedStatus: StatusTag = selected ? statuses[selected.id] ?? "pending" : "pending";
  const selectedDraft = selected ? drafts[selected.id] ?? "" : "";

  const setEvent = (message: string) => {
    setTimeline((prev) => [{ ts: nowTime(), message }, ...prev].slice(0, 60));
  };

  const runBranchCheck = async () => {
    const bridge = getBridge();
    if (!bridge) {
      setEvent("Bridge unavailable: cannot run branch check.");
      return;
    }
    if (!branchHead || !branchIncoming) {
      setEvent("Select both source and incoming branches.");
      return;
    }
    setEvent(`Checking branches: ${branchHead} vs ${branchIncoming}`);
    const result = await bridge.runBranchCheck(branchHead, branchIncoming);
    setEvent(result.message);
  };

  const generateMock = () => {
    if (!selected) return;
    const head = selected.currentText.trim();
    const incoming = selected.incomingText.trim();
    const merged = !head ? incoming : !incoming ? head : head === incoming ? head : `${head}\n\n// --- merged with incoming changes ---\n${incoming}`;
    setDrafts((prev) => ({ ...prev, [selected.id]: merged }));
    setStatuses((prev) => ({ ...prev, [selected.id]: "reviewed" }));
    setEvent(`Mock AI generated draft for lines ${selected.startLine}-${selected.endLine}`);
  };

  const editDraft = (value: string) => {
    if (!selected) return;
    setDrafts((prev) => ({ ...prev, [selected.id]: value }));
    setStatuses((prev) => ({ ...prev, [selected.id]: "reviewed" }));
  };

  const acceptDraft = async () => {
    if (!selected) return;
    const bridge = getBridge();
    const draft = drafts[selected.id] ?? "";
    setStatuses((prev) => ({ ...prev, [selected.id]: "resolved" }));
    if (!bridge) {
      setEvent("Accepted locally (bridge unavailable).");
      return;
    }
    if (selected.source !== "active_editor") {
      setEvent("Branch-preview conflicts are read-only. Open the conflicted file to apply.");
      return;
    }
    const result = await bridge.applyResolvedCode(selected.id, draft);
    setEvent(result.message);
  };

  const rejectDraft = () => {
    if (!selected) return;
    setDrafts((prev) => ({ ...prev, [selected.id]: "" }));
    setStatuses((prev) => ({ ...prev, [selected.id]: "pending" }));
    setEvent(`Rejected draft for lines ${selected.startLine}-${selected.endLine}`);
  };

  return (
    <div className="app">
      <div className="top">
        <div className="metric"><div className="k">Active File</div><div className="v">{state.filePath || "No active editor"}</div></div>
        <div className="metric"><div className="k">Displayed Conflicts</div><div className="v">{state.conflictCount}</div></div>
        <div className="metric"><div className="k">Editor Conflicts</div><div className="v">{state.activeConflictCount}</div></div>
        <div className="metric"><div className="k">Export/Upload</div><div className="v">{state.uploadStatus || "Waiting"}</div></div>
        <div className="metric"><div className="k">Last Scan</div><div className="v">{new Date(state.lastScanTimeMs || Date.now()).toLocaleTimeString()}</div></div>
      </div>

      <div className="branch-controls">
        <select value={branchHead} onChange={(e) => setBranchHead(e.target.value)}>
          <option value="">Select source branch</option>
          {headOptions.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
        <select value={branchIncoming} onChange={(e) => setBranchIncoming(e.target.value)}>
          <option value="">Select incoming branch</option>
          {incomingOptions.map((option) => (
            <option key={option.value} value={option.value}>{option.label}</option>
          ))}
        </select>
        <button className="primary" onClick={runBranchCheck}>Check Merge Conflicts</button>
        <div className="branch-message">{state.branchCheckMessage || "Branch comparison not run yet."}</div>
      </div>

      <div className="main">
        <aside className="list">
          <p className="section-title">Conflict Blocks</p>
          {state.conflicts.length === 0 ? (
            <div className="empty">No conflict data available. Open a conflicted file or run branch check.</div>
          ) : (
            state.conflicts.map((conflict) => {
              const active = conflict.id === selectedId;
              const status = statuses[conflict.id] ?? "pending";
              const snippet = (conflict.currentText || conflict.incomingText || "").split("\n").slice(0, 4).join("\n");
              return (
                <div
                  key={conflict.id}
                  className={`conflict-card${active ? " active" : ""}`}
                  onClick={() => setSelectedId(conflict.id)}
                >
                  <div className="row">
                    <strong>Lines {conflict.startLine}-{conflict.endLine}</strong>
                    <span className={`badge ${status}`}>{status}</span>
                  </div>
                  <div className="muted">{conflict.currentLabel} vs {conflict.incomingLabel}</div>
                  <div className="snippet">{snippet || "[empty block]"}</div>
                </div>
              );
            })
          )}
        </aside>

        <section className="detail">
          <div className="detail-head">
            <p className="section-title">Conflict Detail</p>
            {!selected ? (
              <div className="empty">Select a conflict block to inspect details.</div>
            ) : (
              <>
                <div>
                  <strong>Conflict {selected.startLine}-{selected.endLine}</strong>
                  <span className="muted"> ({selected.currentLabel} vs {selected.incomingLabel})</span>
                </div>
                <div className="meta-grid">
                  <div className="metric"><div className="k">Language</div><div className="v">{selected.language || "Unknown"}</div></div>
                  <div className="metric"><div className="k">Parent Signature</div><div className="v">{selected.parentSignature || "Unknown"}</div></div>
                  <div className="metric"><div className="k">Source</div><div className="v">{selected.source}</div></div>
                  <div className="metric"><div className="k">Status</div><div className="v">{selectedStatus}</div></div>
                </div>
              </>
            )}
          </div>

          <div className="compare">
            <div className="pane"><h4>Current (HEAD/source)</h4><pre>{selected?.currentText || ""}</pre></div>
            <div className="pane"><h4>Incoming</h4><pre>{selected?.incomingText || ""}</pre></div>
          </div>
          <div className="compare" style={{ paddingTop: 0 }}>
            <div className="pane"><h4>Context Above</h4><pre>{selected?.bufferAbove || ""}</pre></div>
            <div className="pane"><h4>Context Below</h4><pre>{selected?.bufferBelow || ""}</pre></div>
          </div>

          <div className="ai">
            <p className="section-title">AI Resolution (Mock)</p>
            <textarea
              value={selectedDraft}
              onChange={(e) => editDraft(e.target.value)}
              placeholder="Generated merge draft appears here..."
            />
            <div className="actions">
              <button className="primary" onClick={generateMock}>Generate</button>
              <button onClick={acceptDraft}>Accept</button>
              <button onClick={() => selected && setEvent(`Edited draft for lines ${selected.startLine}-${selected.endLine}`)}>Edit</button>
              <button onClick={rejectDraft}>Reject</button>
            </div>
          </div>
        </section>
      </div>

      <div className="timeline">
        <p className="section-title">Activity</p>
        {timeline.length === 0 ? (
          <div className="empty">No activity yet.</div>
        ) : (
          timeline.map((event, index) => (
            <div key={`${event.ts}-${index}`} className="event">
              <span className="ts">[{event.ts}]</span>
              {event.message}
            </div>
          ))
        )}
      </div>
    </div>
  );
}
