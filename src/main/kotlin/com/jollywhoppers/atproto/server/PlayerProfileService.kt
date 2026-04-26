package com.jollywhoppers.atproto.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Manages the player.profile record for linked players.
 *
 * When a player links their identity, this service creates or updates their
 * `com.jollywhoppers.minecraft.player.profile` record with the `literal:self` rkey.
 *
 * Note: AT Protocol data is always public. Sync consent (syncStats, syncSessions)
 * is stored locally in PlayerIdentityStore and controls whether data is written
 * at all — it is NOT included in the profile record since it would give a false
 * sense of privacy control.
 */
class PlayerProfileService(
    private val recordManager: RecordManager,
    private val sessionManager: AtProtoSessionManager,
    private val identityStore: PlayerIdentityStore,
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:profile")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val COLLECTION_ID = "com.jollywhoppers.minecraft.player.profile"
        private const val RKEY = "self"
    }

    /**
     * Writes or updates the player.profile record for a linked player.
     * Called when a player links their identity or changes privacy settings.
     */
    fun syncProfile(
        playerUuid: UUID,
        serverId: String,
        serverName: String,
        serverAddress: String? = null,
    ) {
        coroutineScope.launch {
            try {
                val identity = identityStore.getIdentity(playerUuid)
                if (identity == null) {
                    logger.warn("Cannot sync profile: player $playerUuid is not linked")
                    return@launch
                }

                if (!sessionManager.hasSession(playerUuid)) {
                    logger.warn("Cannot sync profile: player $playerUuid has no active session")
                    return@launch
                }

                val profile = MinecraftPlayerProfileRecord(
                    player = PlayerReference(
                        uuid = identity.uuid,
                        username = identity.handle,
                    ),
                    primaryServer = ServerReference(
                        serverId = serverId,
                        serverName = serverName,
                        serverAddress = serverAddress,
                    ),
                    createdAt = Instant.ofEpochMilli(identity.linkedAt).toString(),
                    updatedAt = Instant.now().toString(),
                )

                recordManager.putTypedRecord(
                    playerUuid = playerUuid,
                    collection = COLLECTION_ID,
                    rkey = RKEY,
                    record = profile,
                ).getOrThrow()

                logger.info("Synced player.profile for ${identity.handle} ($playerUuid)")
            } catch (e: Exception) {
                logger.error("Failed to sync player.profile for $playerUuid", e)
            }
        }
    }

    fun shutdown() {
        coroutineScope.cancel()
    }

    @Serializable
    data class PlayerReference(
        val uuid: String,
        val username: String,
    )

    @Serializable
    data class ServerReference(
        val serverId: String,
        val serverName: String,
        val serverAddress: String? = null,
    )

    @Serializable
    data class MinecraftPlayerProfileRecord(
        @SerialName("\$type") val type: String = COLLECTION_ID,
        val player: PlayerReference,
        val primaryServer: ServerReference? = null,
        val createdAt: String,
        val updatedAt: String,
    )
}
