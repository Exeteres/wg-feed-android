package com.zaneschepke.wireguardautotunnel.wgfeed.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WgFeedDisplayInfo(
    val title: String,
    val description: String? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
)

@Serializable
data class WgFeedTunnel(
    val id: String,
    val name: String,
    @SerialName("display_info") val displayInfo: WgFeedDisplayInfo,
    @SerialName("wg_quick_config") val wgQuickConfig: String,
    val enabled: Boolean = false,
    val forced: Boolean = false,
)

@Serializable
data class WgFeedDocument(
    val id: String,
    /**
     * Draft-00 required: list of HTTPS subscription URLs for this feed.
     * Items MUST NOT include URL fragments.
     */
    val endpoints: List<String>,
    @SerialName("warning_message") val warningMessage: String? = null,
    @SerialName("display_info") val displayInfo: WgFeedDisplayInfo,
    val tunnels: List<WgFeedTunnel>,
)

@Serializable
data class WgFeedSuccessResponse(
    val version: String,
    val success: Boolean,
    val revision: String,
    @SerialName("ttl_seconds") val ttlSeconds: Int,
    @SerialName("supports_sse") val supportsSse: Boolean = false,
    // encryption
    val encrypted: Boolean = false,
    @SerialName("encrypted_data") val encryptedData: String? = null,
    // plaintext
    val data: WgFeedDocument? = null,
)

@Serializable
data class WgFeedErrorResponse(
    val version: String,
    val success: Boolean,
    val message: String,
    val retriable: Boolean,
)
