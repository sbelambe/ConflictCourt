"use client";

export type ConflictItem = {
  id: string;
  filePath: string;
  startLine: number;
  separatorLine: number;
  endLine: number;
  currentLabel: string;
  incomingLabel: string;
  currentText: string;
  incomingText: string;
  bufferAbove: string;
  bufferBelow: string;
  language: string;
  parentSignature: string;
  source: "active_editor" | "branch_preview" | string;
};

export type HostState = {
  filePath: string;
  uploadStatus: string;
  previousUploadStatus: string;
  hasConflicts: boolean;
  activeConflictCount: number;
  conflictCount: number;
  conflictSource: "active_editor" | "branch_preview" | string;
  lastScanTimeMs: number;
  branchCheckMessage: string;
  currentBranch: string;
  selectedHead: string;
  selectedIncoming: string;
  workingTreeOptionValue: string;
  webUrl: string;
  branches: string[];
  conflicts: ConflictItem[];
};

export type BranchCheckResult = {
  ok: boolean;
  message: string;
  conflictCount: number;
};

export type ApplyResult = {
  ok: boolean;
  message: string;
};

export type ConflictCourtBridge = {
  getCurrentConflictState: () => Promise<HostState>;
  runBranchCheck: (head: string, incoming: string) => Promise<BranchCheckResult>;
  applyResolvedCode: (conflictId: string, mergedCode: string) => Promise<ApplyResult>;
  subscribe: (handler: (state: HostState) => void) => () => void;
};

declare global {
  interface Window {
    conflictCourtBridge?: ConflictCourtBridge;
    __conflictCourtState?: HostState;
  }
}

export function getBridge(): ConflictCourtBridge | null {
  if (typeof window === "undefined") return null;
  return window.conflictCourtBridge ?? null;
}
