package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import io.ktor.http.Url

/**
 * Logging helpers for wg-feed.
 */
internal object WgFeedLog {

    /**
     * Returns only `scheme://host:port` for safe long-term logging.
     */
    fun endpointOrigin(endpoint: String): String {
        val trimmed = endpoint.trim()
        val url = Url(trimmed)
        val portPart = if (url.port > 0) ":${url.port}" else ""

        return "${url.protocol.name}://${url.host}$portPart"
    }
}
