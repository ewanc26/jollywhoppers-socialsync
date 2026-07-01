package com.jollywhoppers.atproto.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Stats(
    @SerialName("\$type") val type: String = "com.jollywhoppers.minecraft.player.stats",
    val player: JsonObject,
    val server: JsonObject? = null,
    val statistics: List<JsonObject>,
    val playtimeMinutes: Long? = null,
    val level: Long? = null,
    val gamemode: String? = null,
    val dimension: String? = null,
    val syncedAt: String,
)
