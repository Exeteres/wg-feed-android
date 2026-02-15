package com.zaneschepke.wireguardautotunnel.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedSubscriptionEntity

@Entity(
    tableName = "tunnel_config",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["feed_subscription_id"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = FeedSubscriptionEntity::class,
            parentColumns = ["id"],
            childColumns = ["feed_subscription_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class TunnelConfig(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "wg_quick") val wgQuick: String,
    @ColumnInfo(name = "tunnel_networks", defaultValue = "")
    val tunnelNetworks: Set<String> = setOf(),
    @ColumnInfo(name = "is_mobile_data_tunnel", defaultValue = "false")
    val isMobileDataTunnel: Boolean = false,
    @ColumnInfo(name = "is_primary_tunnel", defaultValue = "false")
    val isPrimaryTunnel: Boolean = false,
    @ColumnInfo(name = "am_quick", defaultValue = "") val amQuick: String = "",
    @ColumnInfo(name = "is_Active", defaultValue = "false") val isActive: Boolean = false,
    @ColumnInfo(name = "restart_on_ping_failure", defaultValue = "false")
    val restartOnPingFailure: Boolean = false,
    @ColumnInfo(name = "ping_target", defaultValue = "null") var pingTarget: String? = null,
    @ColumnInfo(name = "is_ethernet_tunnel", defaultValue = "false")
    val isEthernetTunnel: Boolean = false,
    @ColumnInfo(name = "is_ipv4_preferred", defaultValue = "true")
    val isIpv4Preferred: Boolean = true,
    @ColumnInfo(name = "position", defaultValue = "0") val position: Int = 0,
    @ColumnInfo(name = "auto_tunnel_apps", defaultValue = "[]")
    val autoTunnelApps: Set<String> = emptySet(),
    @ColumnInfo(name = "is_metered", defaultValue = "false") val isMetered: Boolean = false,
    @ColumnInfo(name = "display_title") val displayTitle: String? = null,
    @ColumnInfo(name = "display_description") val displayDescription: String? = null,
    @ColumnInfo(name = "display_icon_url") val displayIconUrl: String? = null,
    @ColumnInfo(name = "feed_subscription_id") val feedSubscriptionId: Int? = null,
    @ColumnInfo(name = "is_readonly", defaultValue = "false") val isReadonly: Boolean = false,
    @ColumnInfo(name = "is_restart_required", defaultValue = "false") val isRestartRequired: Boolean = false,
) {
    companion object {
        const val GLOBAL_CONFIG_NAME = "4675ab06-903a-438b-8485-6ea4187a9512"
    }
}
