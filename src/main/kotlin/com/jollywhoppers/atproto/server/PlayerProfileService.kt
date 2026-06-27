package com.jollywhoppers.atproto.server

import kotlinx.coroutines.*
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
 * Note: AT Protocol data is always public. Sync consent is stored locally
 * in PlayerSyncPreferencesStore and controls whether data is written at all —
 * it is NOT included in the profile record since it would give a false sense
 * of privacy control.
 */
import com.jollywhoppers.atproto.server.model.Profile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Manages the player.profile record for linked players.
 *
 * When a player links their identity, this service creates or updates their
 * `com.jollywhoppers.minecraft.player.profile` record with the `literal:self` rkey.
 *
 * Note: AT Protocol data is always public. Sync consent is stored locally
 * in PlayerSyncPreferencesStore and controls whether data is written at all —
 * it is NOT included in the profile record since it would give a false sense
 * of privacy control.
 */
class PlayerProfileService(
    private val recordManager: RecordManager,
    private val sessionManager: AtProtoSessionManager,
    private val identityStore: PlayerIdentityStore,
    private val syncPreferencesStore: PlayerSyncPreferencesStore,
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

                if (!sessionManager.getSession(playerUuid).isSuccess) {
                    logger.warn("Cannot sync profile: player $playerUuid has no active session")
                    return@launch
                }

                // Profile records are identity declarations, not data sync.
                // Only skip if the player has explicitly disabled ALL sync categories.
                val prefs = syncPreferencesStore.getOrDefault(playerUuid)
                if (!prefs.syncStatsEnabled && !prefs.syncSessionsEnabled
                    && !prefs.syncAchievementsEnabled && !prefs.syncServerStatusEnabled
                ) {
                    logger.debug("Skipping profile sync for $playerUuid: all sync categories disabled")
                    return@launch
                }

                val profile = Profile(
                    player = buildJsonObject { put("uuid", identity.uuid); put("username", identity.handle) },
                    displayName = identity.handle,
                    bio = "Minecraft player",
                    primaryServer = buildJsonObject { put("serverId", serverId); put("serverName", serverName) },
                    favoriteGameMode = "survival",
                    createdAt = Instant.ofEpochMilli(identity.linkedAt).toString(),
                    updatedAt = Instant.now().toString(),
                )

                recordManager.createRecord(
                    playerUuid = playerUuid,
                    collection = COLLECTION_ID,
                    record = profile.let { json.encodeToJsonElement(Profile.serializer(), it) },
                    validate = true
                ).getOrThrow()

                logger.info("Synced player.profile for ${identity.handle} ($playerUuid)")
            } catch (e: Exception) {
                logger.error("Failed to sync player.profile for $playerUuid", e)
            }
        }
    }

    fun shutdown() {
        try {
            runBlocking {
                withTimeout(5000) {
                    coroutineScope.coroutineContext[Job]?.children?.forEach { it.join() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("Timeout while shutting down ${this::class.simpleName}")
        }
    }
}
