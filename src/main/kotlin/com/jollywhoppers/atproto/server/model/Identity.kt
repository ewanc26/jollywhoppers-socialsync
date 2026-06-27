package com.jollywhoppers.atproto.server.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class IdentityRecord(
    @SerialName("\$type") val type: String = "com.jollywhoppers.minecraft.identity",
    val playerUuid: String,
    val did: String,
    val handle: String? = null,
    val displayName: String? = null,
    val pdsUrl: String? = null,
    val linkedAt: String,
    val updatedAt: String? = null,
)
