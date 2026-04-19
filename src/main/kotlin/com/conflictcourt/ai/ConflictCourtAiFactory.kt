package com.conflictcourt.ai

object ConflictCourtAiFactory {
    fun createPipelineFromEnvironment(): ConflictResolutionPipeline? {
        val codexConfig = CodexConfig.fromEnvironment() ?: return null
        val codexService = CodexService(codexConfig)
        val auditConfig = SupabaseAuditConfig.fromEnvironment()
        val auditLogger = auditConfig?.let { SupabaseAuditLogger(it) }
        return ConflictResolutionPipeline(codexService, auditLogger)
    }
}
