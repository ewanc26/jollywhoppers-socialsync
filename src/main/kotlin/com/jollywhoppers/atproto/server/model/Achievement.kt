package com.jollywhoppers.atproto.server.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Achievement(
    val player: JsonObject,
    val server: JsonObject? = null,
    val achievementId: String,
    val achievementName: String? = null,
    val achievementDescription: String? = null,
    val achievedAt: String,
    val category: String? = null,
    val isChallenge: Boolean? = null,
)
