package com.conflictcourt.ai

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

object Config {
    private const val LOCAL_PROPERTIES_FILE = "local.properties"
    private const val OPENAI_KEY_NAME = "OPENAI_API_KEY"

    private data class LoadedConfig(
        val properties: Properties,
        val error: String?
    )

    private val loaded: LoadedConfig by lazy { loadLocalProperties() }

    /**
     * Returns OPENAI_API_KEY from local.properties.
     * Empty string when file/key is missing or unreadable.
     */
    val openAiKey: String
        get() = loaded.properties.getProperty(OPENAI_KEY_NAME).orEmpty().trim()

    /**
     * Non-fatal configuration issue details for logging/UI.
     */
    val loadError: String?
        get() = when {
            loaded.error != null -> loaded.error
            openAiKey.isBlank() -> "$OPENAI_KEY_NAME is missing in $LOCAL_PROPERTIES_FILE."
            else -> null
        }

    private fun loadLocalProperties(): LoadedConfig {
        val file = findLocalProperties()
            ?: return LoadedConfig(
                properties = Properties(),
                error = "$LOCAL_PROPERTIES_FILE not found from working directory ${System.getProperty("user.dir")}."
            )

        return runCatching {
            val properties = Properties()
            Files.newInputStream(file).use { properties.load(it) }
            LoadedConfig(properties = properties, error = null)
        }.getOrElse { error ->
            LoadedConfig(
                properties = Properties(),
                error = "Failed to read ${file.toAbsolutePath()}: ${error.message}"
            )
        }
    }

    private fun findLocalProperties(): Path? {
        var current: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        while (current != null) {
            val candidate = current.resolve(LOCAL_PROPERTIES_FILE)
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate
            }
            current = current.parent
        }
        return null
    }
}
