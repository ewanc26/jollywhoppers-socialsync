package com.jollywhoppers.atproto.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SyncPreferencesRecord(
    @SerialName("\$type") val type: String = "com.jollywhoppers.minecraft.syncpreferences",
    val player: SyncPlayerRef,
    val syncStats: Boolean = true,
    val syncSessions: Boolean = true,
    val syncAchievements: Boolean = true,
    val syncServerStatus: Boolean = false,
    val statsSyncFrequency: Int = 60,
    val sessionSyncFrequency: Int = 60,
    val achievementSyncFrequency: Int = 60,
    val updatedAt: String,
)

@Serializable
data class SyncPlayerRef(
    val uuid: String,
    val username: String,
)
