package com.conflictcourt.ai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

object Config {
    private const val DOT_ENV_FILE = ".env"
    private const val LOCAL_PROPERTIES_FILE = "local.properties"
    private const val OPENAI_KEY_NAME = "OPENAI_API_KEY"

    /**
     * Returns OPENAI_API_KEY with this precedence:
     * 1) process environment variable
     * 2) .env file (project base first when provided)
     * 3) local.properties (project base first when provided)
     */
    val openAiKey: String
        get() = openAiKeyFor()

    fun openAiKeyFor(projectBasePath: String? = null): String {
        val env = System.getenv(OPENAI_KEY_NAME)?.trim().orEmpty()
        if (env.isNotBlank()) return env

        findDotEnv(projectBasePath)?.let { file ->
            val fromDotEnv = loadDotEnv(file)[OPENAI_KEY_NAME].orEmpty().trim()
            if (fromDotEnv.isNotBlank()) return fromDotEnv
        }

        findLocalProperties(projectBasePath)?.let { file ->
            val properties = loadPropertiesFile(file)
            val fromLocal = properties.getProperty(OPENAI_KEY_NAME).orEmpty().trim()
            if (fromLocal.isNotBlank()) return fromLocal
        }

        return ""
    }

    /**
     * Non-fatal configuration issue details for logging/UI.
     */
    val loadError: String?
        get() = when {
            openAiKey.isBlank() -> "$OPENAI_KEY_NAME is missing in $LOCAL_PROPERTIES_FILE."
            else -> null
        }

    private fun loadPropertiesFile(file: Path): Properties {
        return runCatching {
            val properties = Properties()
            Files.newInputStream(file).use { properties.load(it) }
            properties
        }.getOrElse { Properties() }
    }

    private fun loadDotEnv(file: Path): Map<String, String> {
        return runCatching {
            Files.readAllLines(file).mapNotNull { raw ->
                val line = raw.trim()
                if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
                val eq = line.indexOf('=')
                if (eq <= 0) return@mapNotNull null
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim().trim('"', '\'')
                key to value
            }.toMap()
        }.getOrElse { emptyMap() }
    }

    private fun findDotEnv(projectBasePath: String? = null): Path? {
        return findNamedFile(DOT_ENV_FILE, projectBasePath)
    }

    private fun findLocalProperties(projectBasePath: String? = null): Path? {
        return findNamedFile(LOCAL_PROPERTIES_FILE, projectBasePath)
    }

    private fun findNamedFile(fileName: String, projectBasePath: String? = null): Path? {
        projectBasePath
            ?.takeIf { it.isNotBlank() }
            ?.let { base ->
                val candidate = Paths.get(base).toAbsolutePath().normalize().resolve(fileName)
                if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                    return candidate
                }
            }

        var current: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (current != null) {
            val candidate = current.resolve(fileName)
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate
            }
            current = current.parent
        }
        return null
    }
}
