package com.conflictcourt.ai

object ConflictCourtAiFactory {
    fun createPipelineFromEnvironment(projectBasePath: String? = null): ConflictResolutionPipeline? {
        val codexConfig = CodexConfig.fromEnvironment(projectBasePath) ?: return null
        val codexService = CodexService(codexConfig)
        val auditConfig = SupabaseAuditConfig.fromEnvironment()
        val auditLogger = auditConfig?.let { SupabaseAuditLogger(it) }
        return ConflictResolutionPipeline(codexService, auditLogger)
    }
}
