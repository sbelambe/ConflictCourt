package com.conflictcourt.supabase

data class SupabaseConfig(
    val url: String,
    val apiKey: String
) {
    companion object {
        private const val URL_ENV = "CONFLICTCOURT_SUPABASE_URL"
        private const val KEY_ENV = "CONFLICTCOURT_SUPABASE_KEY"

        fun fromEnvironment(): SupabaseConfig? {
            val url = System.getenv(URL_ENV)?.trim().orEmpty()
            val apiKey = System.getenv(KEY_ENV)?.trim().orEmpty()

            return if (url.isBlank() || apiKey.isBlank()) {
                null
            } else {
                SupabaseConfig(url = url.removeSuffix("/"), apiKey = apiKey)
            }
        }
    }
}

