package com.jollywhoppers.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Client-side preferences for Social Sync mod.
 * Stores user preferences locally in JSON format.
 * Preferences are NOT sent to the server — they're purely client-side.
 *
 * This separation ensures privacy: sync settings are user preferences,
 * not tied to authentication, and the server respects them.
 */
@Serializable
data class ClientPreferences(
    // Sync consent settings
    val syncStatsEnabled: Boolean = true,
    val syncSessionsEnabled: Boolean = true,
    val syncAchievementsEnabled: Boolean = true,
    val syncServerStatusEnabled: Boolean = false,

    // Sync frequency (in minutes)
    val statsSyncFrequency: Int = 60,
    val sessionSyncFrequency: Int = 5,
    val achievementSyncFrequency: Int = 30,

    // UI preferences
    val showSyncNotifications: Boolean = true,
    val showStatusInF3: Boolean = true,
    val compactModMenuLayout: Boolean = false,

    // Privacy settings
    val encryptedLocalStorage: Boolean = true,
    val clearLocalCacheOnLogout: Boolean = true,
) {
    companion object {
        private val logger = LoggerFactory.getLogger("atproto-connect-config")
        private val json = Json { prettyPrint = true }
        private val configDir: Path by lazy {
            FabricLoader.getInstance().configDir.resolve("atproto-connect")
        }
        private val configFile: Path by lazy {
            configDir.resolve("client-preferences.json")
        }

        /**
         * Load preferences from disk, or return defaults if not found.
         */
        fun load(): ClientPreferences {
            return try {
                if (configFile.exists()) {
                    val content = configFile.readText()
                    json.decodeFromString<ClientPreferences>(content).also {
                        logger.info("Loaded client preferences from ${configFile.fileName}")
                    }
                } else {
                    logger.debug("No preferences file found, using defaults")
                    ClientPreferences()
                }
            } catch (e: Exception) {
                logger.error("Failed to load client preferences: ${e.message}", e)
                logger.info("Using default preferences")
                ClientPreferences()
            }
        }

        /**
         * Save preferences to disk.
         */
        fun save(prefs: ClientPreferences) {
            try {
                Files.createDirectories(configDir)
                val json = json.encodeToString(serializer(), prefs)
                configFile.writeText(json)
                logger.debug("Saved client preferences to ${configFile.fileName}")
            } catch (e: Exception) {
                logger.error("Failed to save client preferences: ${e.message}", e)
            }
        }
    }
}

/**
 * Global preferences instance.
 * Loaded on mod init, updated when user changes settings in ModMenu.
 */
object PreferencesManager {
    private val logger = LoggerFactory.getLogger("atproto-connect-config")
    
    private var instance: ClientPreferences = ClientPreferences.load()

    fun get(): ClientPreferences = instance

    fun update(preferences: ClientPreferences) {
        instance = preferences
        ClientPreferences.save(preferences)
        logger.info("Client preferences updated and persisted")
    }

    /**
     * Update a single preference field without reloading from disk.
     */
    fun updateSyncConsent(
        stats: Boolean? = null,
        sessions: Boolean? = null,
        achievements: Boolean? = null,
        serverStatus: Boolean? = null,
    ) {
        instance = instance.copy(
            syncStatsEnabled = stats ?: instance.syncStatsEnabled,
            syncSessionsEnabled = sessions ?: instance.syncSessionsEnabled,
            syncAchievementsEnabled = achievements ?: instance.syncAchievementsEnabled,
            syncServerStatusEnabled = serverStatus ?: instance.syncServerStatusEnabled,
        )
        ClientPreferences.save(instance)
        logger.info("Sync consent preferences updated")
    }

    /**
     * Reset preferences to defaults.
     */
    fun reset() {
        instance = ClientPreferences()
        ClientPreferences.save(instance)
        logger.info("Client preferences reset to defaults")
    }
}
