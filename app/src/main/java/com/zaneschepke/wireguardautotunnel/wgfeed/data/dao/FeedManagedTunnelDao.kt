package com.zaneschepke.wireguardautotunnel.wgfeed.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.zaneschepke.wireguardautotunnel.wgfeed.data.entity.FeedManagedTunnelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FeedManagedTunnelDao {
    @Query("SELECT * FROM feed_managed_tunnel WHERE subscription_id = :subscriptionId")
    suspend fun getBySubscription(subscriptionId: Int): List<FeedManagedTunnelEntity>

    @Query(
        "SELECT * FROM feed_managed_tunnel WHERE subscription_id = :subscriptionId AND feed_tunnel_id = :feedTunnelId LIMIT 1",
    )
    suspend fun get(subscriptionId: Int, feedTunnelId: String): FeedManagedTunnelEntity?

    @Upsert
    suspend fun upsert(entity: FeedManagedTunnelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FeedManagedTunnelEntity>)

    @Query("DELETE FROM feed_managed_tunnel WHERE subscription_id = :subscriptionId AND feed_tunnel_id IN (:feedTunnelIds)")
    suspend fun deleteByFeedTunnelIds(subscriptionId: Int, feedTunnelIds: List<String>)

    @Query("DELETE FROM feed_managed_tunnel WHERE subscription_id = :subscriptionId")
    suspend fun deleteAllForSubscription(subscriptionId: Int)

    @Query(
        "SELECT * FROM feed_managed_tunnel WHERE tunnel_config_id = :tunnelConfigId LIMIT 1",
    )
    suspend fun findByTunnelConfigId(tunnelConfigId: Int): FeedManagedTunnelEntity?

    @Query("SELECT * FROM feed_managed_tunnel")
    fun getAllFlow(): Flow<List<FeedManagedTunnelEntity>>
}
