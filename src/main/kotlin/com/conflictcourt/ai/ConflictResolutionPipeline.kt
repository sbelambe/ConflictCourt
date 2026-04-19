package com.conflictcourt.ai

class ConflictResolutionPipeline(
    private val codexService: CodexService,
    private val auditLogger: SupabaseAuditLogger?
) {
    suspend fun resolve(context: ConflictContext): PipelineOutcome {
        return when (val codex = codexService.resolveConflict(context)) {
            is CodexOutcome.Failure -> PipelineOutcome.CodexFailure(codex)
            is CodexOutcome.Success -> {
                val auditOutcome = auditLogger?.logResolution(
                    context = context,
                    codexResolution = codex.resolution,
                    codexModel = codexService.modelName(),
                    codexRequestId = codex.requestId,
                    codexRawResponse = codex.rawResponse
                )
                PipelineOutcome.Success(
                    resolution = codex.resolution,
                    codexRequestId = codex.requestId,
                    auditOutcome = auditOutcome
                )
            }
        }
    }
}

sealed class PipelineOutcome {
    data class Success(
        val resolution: ConflictResolution,
        val codexRequestId: String?,
        val auditOutcome: AuditLogOutcome?
    ) : PipelineOutcome()

    data class CodexFailure(val failure: CodexOutcome.Failure) : PipelineOutcome()
}
