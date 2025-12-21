package com.jollywhoppers.atproto.examples

import com.jollywhoppers.atproto.RecordManager
import com.jollywhoppers.atproto.AtProtoSessionManager
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.*

/**
 * Comprehensive examples demonstrating the RecordManager API.
 * Shows all CRUD operations and best practices.
 */
class RecordManagerExamples(
    private val sessionManager: AtProtoSessionManager
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:Examples")
    private val recordManager = RecordManager(sessionManager)

    // ============================================================================
    // EXAMPLE 1: Creating Records with Auto-Generated TIDs
    // ============================================================================

    /**
     * Example: Create a player stats snapshot
     * Uses createRecord for automatic TID generation (timestamp-based key)
     */
    suspend fun createPlayerStats(playerUuid: UUID): Result<String> = runCatching {
        val stats = PlayerStats(
            `$type` = "com.jollywhoppers.minecraft.player.stats",
            player = PlayerRef(
                uuid = playerUuid.toString(),
                username = "ExamplePlayer"
            ),
            statistics = listOf(
                Statistic("minecraft:killed.minecraft.zombie", 42),
                Statistic("minecraft:mined.minecraft.diamond_ore", 15)
            ),
            playtimeMinutes = 180,
            level = 25,
            gamemode = "survival",
            syncedAt = java.time.Instant.now().toString()
        )

        val ref = recordManager.createTypedRecord(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.player.stats",
            record = stats,
            validate = true
        ).getOrThrow()

        logger.info("Created stats record: ${ref.uri}")
        ref.uri
    }

    /**
     * Example: Create an achievement record
     */
    suspend fun createAchievement(
        playerUuid: UUID,
        achievementId: String,
        achievementName: String
    ): Result<String> = runCatching {
        val achievement = Achievement(
            `$type` = "com.jollywhoppers.minecraft.achievement",
            player = PlayerRef(playerUuid.toString(), "ExamplePlayer"),
            achievementId = achievementId,
            achievementName = achievementName,
            achievedAt = java.time.Instant.now().toString(),
            category = "adventure"
        )

        val ref = recordManager.createTypedRecord(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.achievement",
            record = achievement
        ).getOrThrow()

        logger.info("Achievement unlocked: ${ref.uri}")
        ref.uri
    }

    // ============================================================================
    // EXAMPLE 2: Creating/Updating Singleton Records (Profiles)
    // ============================================================================

    /**
     * Example: Create or update player profile (singleton with rkey "self")
     * Uses putRecord for upsert behavior
     */
    suspend fun updatePlayerProfile(
        playerUuid: UUID,
        displayName: String?,
        bio: String?
    ): Result<String> = runCatching {
        val profile = PlayerProfile(
            `$type` = "com.jollywhoppers.minecraft.player.profile",
            player = PlayerRef(playerUuid.toString(), "ExamplePlayer"),
            displayName = displayName,
            bio = bio,
            publicStats = true,
            publicSessions = true,
            updatedAt = java.time.Instant.now().toString()
        )

        val ref = recordManager.putTypedRecord(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.player.profile",
            rkey = "self", // Singleton record uses literal "self"
            record = profile
        ).getOrThrow()

        logger.info("Profile updated: ${ref.uri}")
        ref.uri
    }

    // ============================================================================
    // EXAMPLE 3: Reading Records
    // ============================================================================

    /**
     * Example: Get a specific record by URI
     */
    suspend fun getPlayerProfile(playerUuid: UUID): Result<PlayerProfile> = runCatching {
        val data = recordManager.getTypedRecord<PlayerProfile>(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.player.profile",
            rkey = "self"
        ).getOrThrow()

        logger.info("Retrieved profile for ${data.value.player.username}")
        data.value
    }

    /**
     * Example: Get a specific stats record by its TID
     */
    suspend fun getStatsRecord(playerUuid: UUID, tid: String): Result<PlayerStats> = runCatching {
        val data = recordManager.getTypedRecord<PlayerStats>(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.player.stats",
            rkey = tid
        ).getOrThrow()

        logger.info("Retrieved stats: Level ${data.value.level}, ${data.value.playtimeMinutes}min playtime")
        data.value
    }

    // ============================================================================
    // EXAMPLE 4: Listing Records with Pagination
    // ============================================================================

    /**
     * Example: List recent achievements (paginated)
     */
    suspend fun listRecentAchievements(
        playerUuid: UUID,
        limit: Int = 10
    ): Result<List<Achievement>> = runCatching {
        val result = recordManager.listRecords(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.achievement",
            limit = limit,
            reverse = true // Most recent first
        ).getOrThrow()

        val achievements = result.records.mapNotNull { recordData ->
            try {
                kotlinx.serialization.json.Json.decodeFromJsonElement<Achievement>(recordData.value)
            } catch (e: Exception) {
                logger.warn("Failed to parse achievement record", e)
                null
            }
        }

        logger.info("Found ${achievements.size} recent achievements")
        achievements
    }

    /**
     * Example: List all stats with automatic pagination handling
     */
    suspend fun getAllPlayerStats(playerUuid: UUID): Result<List<PlayerStats>> = runCatching {
        val records = recordManager.listAllRecords(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.player.stats",
            batchSize = 50,
            maxRecords = 200 // Optional limit to prevent too many requests
        ).getOrThrow()

        val stats = records.mapNotNull { recordData ->
            try {
                kotlinx.serialization.json.Json.decodeFromJsonElement<PlayerStats>(recordData.value)
            } catch (e: Exception) {
                logger.warn("Failed to parse stats record", e)
                null
            }
        }

        logger.info("Retrieved ${stats.size} stats records")
        stats
    }

    /**
     * Example: Manual pagination with cursor
     */
    suspend fun paginateThroughAchievements(playerUuid: UUID): Result<Unit> = runCatching {
        var cursor: String? = null
        var page = 1

        do {
            val result = recordManager.listRecords(
                playerUuid = playerUuid,
                collection = "com.jollywhoppers.minecraft.achievement",
                limit = 20,
                cursor = cursor
            ).getOrThrow()

            logger.info("Page $page: ${result.records.size} records")

            // Process this batch
            result.records.forEach { recordData ->
                // Do something with each record
                logger.debug("Processing record: ${recordData.uri}")
            }

            cursor = result.cursor
            page++
        } while (cursor != null)

        logger.info("Processed $page pages total")
    }

    // ============================================================================
    // EXAMPLE 5: Updating Records
    // ============================================================================

    /**
     * Example: Update existing profile with race condition protection
     */
    suspend fun safelyUpdateProfile(
        playerUuid: UUID,
        newDisplayName: String
    ): Result<String> = runCatching {
        // First, get the current record to get its CID
        val current = recordManager.getTypedRecord<PlayerProfile>(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.player.profile",
            rkey = "self"
        ).getOrThrow()

        // Create updated profile
        val updated = current.value.copy(
            displayName = newDisplayName,
            updatedAt = java.time.Instant.now().toString()
        )

        // Use swapRecord to ensure we're updating the version we read
        val ref = recordManager.putTypedRecord(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.player.profile",
            rkey = "self",
            record = updated,
            swapRecord = current.cid // Prevents race conditions
        ).getOrThrow()

        logger.info("Profile safely updated: ${ref.uri}")
        ref.uri
    }

    // ============================================================================
    // EXAMPLE 6: Deleting Records
    // ============================================================================

    /**
     * Example: Delete a specific achievement
     */
    suspend fun deleteAchievement(
        playerUuid: UUID,
        achievementTid: String
    ): Result<Unit> = runCatching {
        recordManager.deleteRecord(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.achievement",
            rkey = achievementTid
        ).getOrThrow()

        logger.info("Achievement deleted: $achievementTid")
    }

    /**
     * Example: Safe delete with CID verification
     */
    suspend fun safelyDeleteRecord(
        playerUuid: UUID,
        collection: String,
        rkey: String
    ): Result<Unit> = runCatching {
        // Get current record to verify it exists
        val current = recordManager.getRecord(
            playerUuid = playerUuid,
            collection = collection,
            rkey = rkey
        ).getOrThrow()

        // Delete with CID verification
        recordManager.deleteRecord(
            playerUuid = playerUuid,
            collection = collection,
            rkey = rkey,
            swapRecord = current.cid // Ensures we delete the exact version we saw
        ).getOrThrow()

        logger.info("Record safely deleted: $rkey")
    }

    // ============================================================================
    // EXAMPLE 7: Batch Operations (Atomic Transactions)
    // ============================================================================

    /**
     * Example: Atomically create multiple achievements
     * All succeed or all fail together
     */
    suspend fun createMultipleAchievements(
        playerUuid: UUID,
        achievements: List<Pair<String, String>> // (id, name) pairs
    ): Result<Unit> = runCatching {
        val writes = achievements.map { (id, name) ->
            val achievement = Achievement(
                `$type` = "com.jollywhoppers.minecraft.achievement",
                player = PlayerRef(playerUuid.toString(), "ExamplePlayer"),
                achievementId = id,
                achievementName = name,
                achievedAt = java.time.Instant.now().toString(),
                category = "batch_unlock"
            )

            RecordManager.WriteOperation.Create(
                collection = "com.jollywhoppers.minecraft.achievement",
                value = kotlinx.serialization.json.Json.encodeToJsonElement(achievement)
            )
        }

        recordManager.applyWrites(
            playerUuid = playerUuid,
            writes = writes
        ).getOrThrow()

        logger.info("Created ${achievements.size} achievements atomically")
    }

    /**
     * Example: Atomic batch update - create stats, update profile
     */
    suspend fun syncPlayerDataAtomically(
        playerUuid: UUID,
        stats: PlayerStats,
        profileUpdate: PlayerProfile
    ): Result<Unit> = runCatching {
        val writes = listOf(
            // Create new stats record
            RecordManager.WriteOperation.Create(
                collection = "com.jollywhoppers.minecraft.player.stats",
                value = kotlinx.serialization.json.Json.encodeToJsonElement(stats)
            ),
            // Update profile
            RecordManager.WriteOperation.Update(
                collection = "com.jollywhoppers.minecraft.player.profile",
                rkey = "self",
                value = kotlinx.serialization.json.Json.encodeToJsonElement(profileUpdate)
            )
        )

        recordManager.applyWrites(
            playerUuid = playerUuid,
            writes = writes
        ).getOrThrow()

        logger.info("Player data synced atomically")
    }

    // ============================================================================
    // EXAMPLE 8: Real-World Use Case - Stats Syncing
    // ============================================================================

    /**
     * Complete example: Sync player statistics on logout
     */
    suspend fun onPlayerLogout(playerUuid: UUID, username: String): Result<Unit> = runCatching {
        logger.info("Syncing stats for player logout: $username")

        // Gather current statistics
        val stats = PlayerStats(
            `$type` = "com.jollywhoppers.minecraft.player.stats",
            player = PlayerRef(playerUuid.toString(), username),
            statistics = gatherMinecraftStats(playerUuid),
            playtimeMinutes = calculatePlaytime(playerUuid),
            level = getPlayerLevel(playerUuid),
            gamemode = getCurrentGamemode(playerUuid),
            syncedAt = java.time.Instant.now().toString()
        )

        // Create the record
        val ref = recordManager.createTypedRecord(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.player.stats",
            record = stats
        ).getOrThrow()

        logger.info("Stats synced successfully: ${ref.uri}")
    }

    /**
     * Complete example: Achievement unlocked workflow
     */
    suspend fun onAchievementUnlocked(
        playerUuid: UUID,
        achievementKey: String
    ): Result<Unit> = runCatching {
        // Check if already unlocked by listing existing achievements
        val existing = recordManager.listAllRecords(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.achievement"
        ).getOrThrow()

        val alreadyUnlocked = existing.any { recordData ->
            try {
                val achievement = kotlinx.serialization.json.Json.decodeFromJsonElement<Achievement>(recordData.value)
                achievement.achievementId == achievementKey
            } catch (e: Exception) {
                false
            }
        }

        if (alreadyUnlocked) {
            logger.info("Achievement $achievementKey already unlocked")
            return@runCatching
        }

        // Create new achievement record
        val achievement = Achievement(
            `$type` = "com.jollywhoppers.minecraft.achievement",
            player = PlayerRef(playerUuid.toString(), "Player"),
            achievementId = achievementKey,
            achievementName = getAchievementName(achievementKey),
            achievedAt = java.time.Instant.now().toString(),
            category = getAchievementCategory(achievementKey)
        )

        recordManager.createTypedRecord(
            playerUuid = playerUuid,
            collection = "com.jollywhoppers.minecraft.achievement",
            record = achievement
        ).getOrThrow()

        logger.info("New achievement unlocked: $achievementKey")
    }

    // ============================================================================
    // Helper Methods (Mock implementations)
    // ============================================================================

    private fun gatherMinecraftStats(uuid: UUID): List<Statistic> {
        // In real implementation, query Minecraft's StatisticsManager
        return listOf(
            Statistic("minecraft:killed.minecraft.zombie", 42),
            Statistic("minecraft:mined.minecraft.diamond_ore", 15)
        )
    }

    private fun calculatePlaytime(uuid: UUID): Int {
        // In real implementation, calculate from play time stat
        return 180
    }

    private fun getPlayerLevel(uuid: UUID): Int {
        // In real implementation, get from player.experienceLevel
        return 25
    }

    private fun getCurrentGamemode(uuid: UUID): String {
        // In real implementation, get from player.gameMode
        return "survival"
    }

    private fun getAchievementName(key: String): String {
        // In real implementation, look up from Minecraft's advancement system
        return "Example Achievement"
    }

    private fun getAchievementCategory(key: String): String {
        // In real implementation, categorize by advancement tree
        return "adventure"
    }

    // ============================================================================
    // Data Classes
    // ============================================================================

    @Serializable
    data class PlayerRef(
        val uuid: String,
        val username: String
    )

    @Serializable
    data class Statistic(
        val key: String,
        val value: Int
    )

    @Serializable
    data class PlayerStats(
        val `$type`: String,
        val player: PlayerRef,
        val statistics: List<Statistic>,
        val playtimeMinutes: Int,
        val level: Int,
        val gamemode: String,
        val syncedAt: String
    )

    @Serializable
    data class PlayerProfile(
        val `$type`: String,
        val player: PlayerRef,
        val displayName: String?,
        val bio: String?,
        val publicStats: Boolean,
        val publicSessions: Boolean,
        val updatedAt: String
    )

    @Serializable
    data class Achievement(
        val `$type`: String,
        val player: PlayerRef,
        val achievementId: String,
        val achievementName: String,
        val achievedAt: String,
        val category: String
    )
}

/**
 * Quick Usage Examples:
 *
 * ```kotlin
 * val examples = RecordManagerExamples(sessionManager)
 *
 * // Create a stats snapshot
 * examples.createPlayerStats(playerUuid)
 *     .onSuccess { uri -> logger.info("Created: $uri") }
 *     .onFailure { error -> logger.error("Failed", error) }
 *
 * // Get player profile
 * examples.getPlayerProfile(playerUuid)
 *     .onSuccess { profile -> displayProfile(profile) }
 *     .onFailure { error -> showError(error) }
 *
 * // List recent achievements
 * examples.listRecentAchievements(playerUuid, limit = 5)
 *     .onSuccess { achievements ->
 *         achievements.forEach { logger.info(it.achievementName) }
 *     }
 *
 * // Update profile safely
 * examples.safelyUpdateProfile(playerUuid, "New Display Name")
 *     .onSuccess { logger.info("Profile updated") }
 *
 * // Sync on logout
 * examples.onPlayerLogout(playerUuid, player.name)
 * ```
 */
