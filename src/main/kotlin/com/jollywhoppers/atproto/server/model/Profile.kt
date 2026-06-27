package com.jollywhoppers.atproto.server.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class Profile(
    val player: JsonObject,
    val displayName: String? = null,
    val bio: String? = null,
    val primaryServer: JsonObject? = null,
    val favoriteGameMode: String? = null,
    val createdAt: String,
    val updatedAt: String? = null,
)
