package com.zaneschepke.wireguardautotunnel.wgfeed.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "feed_managed_tunnel",
    primaryKeys = ["subscription_id", "feed_tunnel_id"],
    foreignKeys =
        [
            ForeignKey(
                entity = FeedSubscriptionEntity::class,
                parentColumns = ["id"],
                childColumns = ["subscription_id"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index(value = ["subscription_id"]), Index(value = ["tunnel_config_id"])],
)
data class FeedManagedTunnelEntity(
    @ColumnInfo(name = "subscription_id") val subscriptionId: Int,
    @ColumnInfo(name = "feed_tunnel_id") val feedTunnelId: String,
    @ColumnInfo(name = "tunnel_config_id") val tunnelConfigId: Int,
    @ColumnInfo(name = "is_forced") val isForced: Boolean,
)
