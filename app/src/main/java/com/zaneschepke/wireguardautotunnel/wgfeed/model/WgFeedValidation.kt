package com.zaneschepke.wireguardautotunnel.wgfeed.model

import androidx.core.net.toUri

object WgFeedValidation {
    const val VERSION = "wg-feed-00"

    // Schema: UUID with RFC4122 variant [89ab]
    private val uuidRegex =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

    private val tunnelNameRegex = Regex("^[A-Za-z][A-Za-z0-9-]*$")

    // Schema: data: URL with media type image/svg+xml (case-insensitive)
    private val svgDataUrlRegex =
        Regex("^data:[iI][mM][aA][gG][eE]/[sS][vV][gG]\\+[xX][mM][lL](?:;[^,]*)?,.*$")

    fun requireHttpsUrl(url: String) {
        val uri = runCatching { url.toUri() }.getOrNull() ?: error("Invalid URL")
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https") { "HTTPS required" }
        require(!uri.host.isNullOrBlank()) { "Invalid URL host" }
    }

    fun requireHttpOrHttpsUrl(url: String) {
        val uri = runCatching { url.toUri() }.getOrNull() ?: error("Invalid URL")
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "HTTP(S) required" }
        require(!uri.host.isNullOrBlank()) { "Invalid URL host" }
    }

    private fun requireNoFragment(url: String) {
        val uri = runCatching { url.toUri() }.getOrNull() ?: error("Invalid URL")
        require(uri.fragment.isNullOrBlank()) { "URL must not include a fragment (#...)" }
    }

    fun requireSupportsVersion(version: String) {
        require(version == VERSION) { "Unsupported wg-feed version: $version" }
    }

    fun validateSuccessResponse(resp: WgFeedSuccessResponse) {
        requireSupportsVersion(resp.version)
        require(resp.success) { "Expected success response" }
        require(resp.revision.isNotBlank()) { "Missing revision" }
        require(resp.ttlSeconds >= 0) { "ttl_seconds must be >= 0" }

        if (resp.encrypted) {
            require(!resp.encryptedData.isNullOrBlank()) { "Missing encrypted_data" }
            require(resp.data == null) { "encrypted response must not include data" }
        } else {
            // Unencrypted branch must not include encrypted_data
            require(resp.encryptedData.isNullOrBlank()) { "unencrypted response must not include encrypted_data" }
            val doc = resp.data ?: throw IllegalArgumentException("Missing data")
            validateDocument(doc)
        }
    }

    fun validateErrorResponse(resp: WgFeedErrorResponse) {
        requireSupportsVersion(resp.version)
        require(!resp.success) { "Expected error response" }
        require(resp.message.isNotBlank()) { "Missing message" }
    }

    fun validateDocument(doc: WgFeedDocument) {
        require(uuidRegex.matches(doc.id)) { "Invalid feed id" }
        require(doc.displayInfo.title.isNotBlank()) { "Missing display_info.title" }
        doc.warningMessage?.let { require(it.isNotBlank()) { "warning_message must be non-empty" } }

        require(doc.endpoints.isNotEmpty()) { "endpoints must contain at least one item" }
        val endpointSet = LinkedHashSet<String>()
        doc.endpoints.forEach { ep ->
            require(ep.isNotBlank()) { "endpoint must be non-empty" }
            requireHttpsUrl(ep)
            requireNoFragment(ep)
            require(endpointSet.add(ep)) { "Duplicate endpoint: $ep" }
        }

        doc.displayInfo.iconUrl?.let { icon ->
            require(svgDataUrlRegex.matches(icon)) { "icon_url must be a data: URL with media type image/svg+xml" }
        }

        val ids = HashSet<String>()
        doc.tunnels.forEach { t ->
            require(t.id.isNotBlank()) { "Tunnel id is required" }
            require(ids.add(t.id)) { "Duplicate tunnel id: ${t.id}" }
            require(t.name.isNotBlank()) { "Tunnel name is required" }
            require(tunnelNameRegex.matches(t.name)) { "Invalid tunnel name: ${t.name}" }
            require(t.displayInfo.title.isNotBlank()) { "Tunnel display_info.title is required" }
            require(t.wgQuickConfig.isNotBlank()) { "wg_quick_config is required" }
            t.displayInfo.iconUrl?.let { icon ->
                require(svgDataUrlRegex.matches(icon)) {
                    "icon_url must be a data: URL with media type image/svg+xml"
                }
            }
        }
    }
}
