package com.zaneschepke.wireguardautotunnel.wgfeed.domain

import androidx.core.net.toUri

/**
 * Helpers for Draft-00 endpoint selection.
 *
 * - Setup URL may contain an age key fragment; fragments are never sent in HTTP requests.
 * - Feed document endpoints MUST NOT contain fragments.
 */
object WgFeedEndpoints {

    /** Strip URL fragment (#...) if present. */
    fun stripFragment(url: String): String {
        val uri = runCatching { url.toUri() }.getOrNull() ?: return url
        return uri.buildUpon().fragment(null).build().toString()
    }

    /**
     * Returns endpoints in stable order.
     *
     * Clients try endpoints one-by-one and should prefer endpoints that worked previously.
     */
    fun ordered(endpoints: List<String>, preferred: String? = null): List<String> {
        if (endpoints.isEmpty()) return emptyList()
        if (preferred.isNullOrBlank()) return endpoints

        // If the preferred endpoint exists in the list, move it to the front while preserving order.
        var usedPreferred = false
        val out = ArrayList<String>(endpoints.size)

        endpoints.forEach { ep ->
            if (!usedPreferred && ep == preferred) {
                out.add(ep)
                usedPreferred = true
            }
        }

        if (!usedPreferred) {
            // preferred isn't part of endpoints; keep original order.
            return endpoints
        }

        endpoints.forEach { ep ->
            if (ep != preferred) out.add(ep)
        }

        return out
    }
}