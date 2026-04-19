package com.conflictcourt.toolwindow

object WarRoomUrlProvider {
    private const val WEB_URL_ENV = "CONFLICTCOURT_WEB_URL"
    private const val DEFAULT_URL = "https://example.com"

    fun currentUrl(): String {
        return System.getenv(WEB_URL_ENV)?.trim().orEmpty().ifBlank { DEFAULT_URL }
    }
}

