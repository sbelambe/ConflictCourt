package com.conflictcourt.toolwindow

import com.intellij.openapi.project.Project
import java.io.File

object WarRoomUrlProvider {
    private const val WEB_URL_ENV = "CONFLICTCOURT_WEB_URL"
    private const val DEFAULT_URL = "https://example.com"

    fun currentUrl(project: Project? = null): String {
        val fromProcess = System.getenv(WEB_URL_ENV)?.trim().orEmpty()
        if (fromProcess.isNotBlank()) {
            return fromProcess
        }

        val fromDotEnv = readDotEnv(project?.basePath)[WEB_URL_ENV]?.trim().orEmpty()
        if (fromDotEnv.isNotBlank()) {
            return fromDotEnv
        }

        return DEFAULT_URL
    }

    private fun readDotEnv(projectBasePath: String?): Map<String, String> {
        if (projectBasePath.isNullOrBlank()) return emptyMap()
        val envFile = File(projectBasePath, ".env")
        if (!envFile.exists() || !envFile.isFile) return emptyMap()

        return buildMap {
            envFile.forEachLine { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val idx = line.indexOf('=')
                if (idx <= 0) return@forEachLine
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().trim('"', '\'')
                if (key.isNotEmpty()) put(key, value)
            }
        }
    }
}
