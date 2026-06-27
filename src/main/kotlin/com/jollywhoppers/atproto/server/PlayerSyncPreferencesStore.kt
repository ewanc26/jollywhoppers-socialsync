package com.jollywhoppers.atproto.server

import com.jollywhoppers.atproto.server.model.SyncPlayerRef
import com.jollywhoppers.atproto.server.model.SyncPreferencesRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import java.time.Instant
import java.util.UUID

/**
 * Server-side sync preferences for players.
 * Stores what data each player has consented to sync.
 * 
 * These are the server's record of player consent, persisted to disk.
 * The client has its own local copy; the server respects the player's choices.
 * 
 * Also published to AT Protocol for data storage and roaming.
 */
@Serializable
data class PlayerSyncPreferences(
    val playerId: String, // Store UUID as String for serialization
    val syncStatsEnabled: Boolean = true,
    val syncSessionsEnabled: Boolean = true,
    val syncAchievementsEnabled: Boolean = true,
    val syncServerStatusEnabled: Boolean = false,
    val statsSyncFrequency: Int = 60,      // minutes
    val sessionSyncFrequency: Int = 5,     // minutes
    val achievementSyncFrequency: Int = 30, // minutes
    val lastUpdated: Long = System.currentTimeMillis(),
) {
    /**
     * Check if any sync is enabled
     */
    fun isAnySyncEnabled(): Boolean {
        return syncStatsEnabled || syncSessionsEnabled || syncAchievementsEnabled || syncServerStatusEnabled
    }

    /**
     * Check if a specific data type should be synced
     */
    fun shouldSync(dataType: String): Boolean = when (dataType) {
        "stats" -> syncStatsEnabled
        "sessions" -> syncSessionsEnabled
        "achievements" -> syncAchievementsEnabled
        "server_status" -> syncServerStatusEnabled
        else -> false
    }

    /**
     * Get sync frequency for a data type (in minutes)
     */
    fun getSyncFrequency(dataType: String): Int = when (dataType) {
        "stats" -> statsSyncFrequency
        "sessions" -> sessionSyncFrequency
        "achievements" -> achievementSyncFrequency
        else -> 60
    }
}

/**
 * Server-side persistent storage for player sync preferences.
 * Each player's preferences are stored in a JSON file and also published to AT Protocol.
 */
object PlayerSyncPreferencesStore {
    private val logger = LoggerFactory.getLogger("atproto-connect-server")
    private val json = Json { prettyPrint = true }
    private val preferencesDir: java.nio.file.Path by lazy {
        FabricLoader.getInstance().configDir.resolve("atproto-connect").resolve("player-preferences")
    }

    private var recordManager: RecordManager? = null
    private var sessionManager: AtProtoSessionManager? = null
    private val atProtoScope = CoroutineScope(Dispatchers.IO)

    fun setAtProtoDependencies(recordManager: RecordManager, sessionManager: AtProtoSessionManager) {
        this.recordManager = recordManager
        this.sessionManager = sessionManager
    }

    init {
        try {
            Files.createDirectories(preferencesDir)
            logger.info("Initialized player sync preferences directory")
        } catch (e: Exception) {
            logger.error("Failed to create preferences directory: ${e.message}", e)
        }
    }

    /**
     * Get preferences file path for a player
     */
    private fun getPreferencesFile(playerId: UUID) =
        preferencesDir.resolve("${playerId}.json")

    /**
     * Load or create default preferences for a player
     */
    fun getOrDefault(playerId: UUID): PlayerSyncPreferences {
        return try {
            val file = getPreferencesFile(playerId)
            if (file.exists()) {
                json.decodeFromString<PlayerSyncPreferences>(file.readText()).also {
                    logger.debug("Loaded sync preferences for player $playerId")
                }
            } else {
                PlayerSyncPreferences(playerId = playerId.toString()).also {
                    logger.debug("Created default sync preferences for new player $playerId")
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to load sync preferences for $playerId: ${e.message}", e)
            PlayerSyncPreferences(playerId = playerId.toString())
        }
    }

    /**
     * Save player sync preferences to disk
     */
    fun save(preferences: PlayerSyncPreferences) {
        try {
            Files.createDirectories(preferencesDir)
            val file = preferencesDir.resolve("${preferences.playerId}.json")
            val content = json.encodeToString(PlayerSyncPreferences.serializer(), preferences)
            file.writeText(content)
            logger.debug("Saved sync preferences for player ${preferences.playerId}")
        } catch (e: Exception) {
            logger.error("Failed to save sync preferences for ${preferences.playerId}: ${e.message}", e)
        }
    }

    /**
     * Publish sync preferences to AT Protocol as a record in the player's repo.
     * Non-critical; local file is authoritative. On failure, only a warning is logged.
     */
    fun publishToAtProtocol(uuid: UUID, prefs: PlayerSyncPreferences, username: String) {
        val rm = recordManager ?: return
        val sm = sessionManager ?: return
        atProtoScope.launch {
            try {
                val record = SyncPreferencesRecord(
                    player = SyncPlayerRef(uuid = uuid.toString(), username = username),
                    syncStats = prefs.syncStatsEnabled,
                    syncSessions = prefs.syncSessionsEnabled,
                    syncAchievements = prefs.syncAchievementsEnabled,
                    syncServerStatus = prefs.syncServerStatusEnabled,
                    statsSyncFrequency = prefs.statsSyncFrequency,
                    sessionSyncFrequency = prefs.sessionSyncFrequency,
                    achievementSyncFrequency = prefs.achievementSyncFrequency,
                    updatedAt = Instant.now().toString(),
                )
                rm.putTypedRecord(uuid, "com.jollywhoppers.minecraft.syncpreferences", "self", record)
                logger.debug("Published sync preferences to AT Protocol for player $uuid")
            } catch (e: Exception) {
                logger.warn("Failed to publish sync preferences to AT Protocol for player $uuid: ${e.message}")
            }
        }
    }

    /**
     * Fetch sync preferences from AT Protocol for a player.
     * Returns the deserialized preferences, or null if no record exists or on failure.
     */
    suspend fun fetchFromAtProtocol(uuid: UUID): Result<PlayerSyncPreferences?> {
        val rm = recordManager ?: return Result.success(null)
        return try {
            val result = rm.getTypedRecord<SyncPreferencesRecord>(
                uuid, "com.jollywhoppers.minecraft.syncpreferences", "self"
            )
            result.map { recordData ->
                val rec = recordData.value
                PlayerSyncPreferences(
                    playerId = rec.player.uuid,
                    syncStatsEnabled = rec.syncStats,
                    syncSessionsEnabled = rec.syncSessions,
                    syncAchievementsEnabled = rec.syncAchievements,
                    syncServerStatusEnabled = rec.syncServerStatus,
                    statsSyncFrequency = rec.statsSyncFrequency,
                    sessionSyncFrequency = rec.sessionSyncFrequency,
                    achievementSyncFrequency = rec.achievementSyncFrequency,
                )
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch sync preferences from AT Protocol for player $uuid: ${e.message}")
            Result.success(null)
        }
    }

    /**
     * Update sync preferences for a player
     */
    fun update(
        playerId: UUID,
        username: String,
        stats: Boolean? = null,
        sessions: Boolean? = null,
        achievements: Boolean? = null,
        serverStatus: Boolean? = null,
        statsFrequency: Int? = null,
        sessionsFrequency: Int? = null,
        achievementsFrequency: Int? = null,
    ) {
        val current = getOrDefault(playerId)
        val updated = current.copy(
            syncStatsEnabled = stats ?: current.syncStatsEnabled,
            syncSessionsEnabled = sessions ?: current.syncSessionsEnabled,
            syncAchievementsEnabled = achievements ?: current.syncAchievementsEnabled,
            syncServerStatusEnabled = serverStatus ?: current.syncServerStatusEnabled,
            statsSyncFrequency = statsFrequency ?: current.statsSyncFrequency,
            sessionSyncFrequency = sessionsFrequency ?: current.sessionSyncFrequency,
            achievementSyncFrequency = achievementsFrequency ?: current.achievementSyncFrequency,
            lastUpdated = System.currentTimeMillis(),
        )
        save(updated)
        publishToAtProtocol(playerId, updated, username)
    }

    /**
     * Delete preferences for a player (on unlink/account deletion)
     */
    fun delete(playerId: UUID) {
        try {
            val file = getPreferencesFile(playerId)
            if (file.exists()) {
                Files.delete(file)
                logger.info("Deleted sync preferences for player $playerId")
            }
        } catch (e: Exception) {
            logger.error("Failed to delete sync preferences for $playerId: ${e.message}", e)
        }
    }

    /**
     * Migrate legacy sync consent from PlayerIdentityStore.
     * Called once during mod initialization. Reads the old syncStats/syncSessions
     * values and writes them into the preferences store for any player that
     * doesn't already have a preferences file.
     */
    fun migrateFromIdentityStore(identityStore: PlayerIdentityStore) {
        val legacyConsent = identityStore.extractLegacySyncConsent()
        var migrated = 0

        legacyConsent.forEach { (uuid, consent) ->
            val (syncStats, syncSessions) = consent
            val existing = getOrDefault(uuid)

            // Only migrate if the player has default preferences (never customised)
            // and the legacy values differ from defaults
            val isDefault = existing.syncStatsEnabled && existing.syncSessionsEnabled
                && existing.syncAchievementsEnabled && !existing.syncServerStatusEnabled
            val hasNonDefaultLegacy = !syncStats || !syncSessions

            if (isDefault && hasNonDefaultLegacy) {
                save(existing.copy(
                    syncStatsEnabled = syncStats,
                    syncSessionsEnabled = syncSessions,
                ))
                migrated++
                logger.info("Migrated sync consent for player $uuid: stats=$syncStats, sessions=$syncSessions")
            }
        }

        // Clear the legacy fields from the identity store
        identityStore.clearLegacySyncConsent()

        if (migrated > 0) {
            logger.info("Migrated sync consent for $migrated players from identity store")
        }
    }

    /**
     * Get all players with preferences (for admin operations)
     */
    fun getAllPlayerIds(): List<UUID> {
        return try {
            Files.list(preferencesDir).use { stream ->
                stream
                    .filter { it.fileName.toString().endsWith(".json") }
                    .toList()
                    .mapNotNull { file ->
                        try {
                            UUID.fromString(file.fileName.toString().removeSuffix(".json"))
                        } catch (e: Exception) {
                            logger.warn("Invalid preferences file: ${file.fileName}")
                            null
                        }
                    }
            }
        } catch (e: Exception) {
            logger.error("Failed to list player preferences: ${e.message}", e)
            emptyList()
        }
    }
}
