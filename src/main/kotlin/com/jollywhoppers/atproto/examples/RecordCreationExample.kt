package com.jollywhoppers.atproto.examples

import com.jollywhoppers.atproto.AtProtoSessionManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import java.util.*

/**
 * Example showing how to create records in a player's AT Protocol repository.
 * This demonstrates the foundation for syncing Minecraft data to AT Protocol.
 */
class RecordCreationExample(
    private val sessionManager: AtProtoSessionManager
) {
    private val json = Json { prettyPrint = false }

    /**
     * Example: Create a player stats record
     */
    suspend fun createPlayerStatsRecord(
        playerUuid: UUID,
        statistics: List<Statistic>,
        playtimeMinutes: Int,
        level: Int
    ): Result<String> = runCatching {
        // Get the player's session (will auto-refresh if needed)
        val session = sessionManager.getSession(playerUuid).getOrThrow()

        // Build the record according to our lexicon
        val record = PlayerStatsRecord(
            `$type` = "com.jollywhoppers.minecraft.player.stats",
            player = PlayerReference(
                uuid = playerUuid.toString(),
                username = "ExamplePlayer" // Would come from Minecraft player object
            ),
            server = ServerReference(
                serverId = "example-server-id",
                serverName = "Example Server"
            ),
            statistics = statistics,
            playtimeMinutes = playtimeMinutes,
            level = level,
            gamemode = "survival",
            syncedAt = java.time.Instant.now().toString()
        )

        // Create the record via XRPC
        val requestBody = CreateRecordRequest(
            repo = session.did,
            collection = "com.jollywhoppers.minecraft.player.stats",
            record = json.encodeToJsonElement(record)
        )

        val response = sessionManager.makeAuthenticatedRequest(
            uuid = playerUuid,
            method = "POST",
            endpoint = "com.atproto.repo.createRecord",
            body = json.encodeToString(requestBody)
        ).getOrThrow()

        response
    }

    /**
     * Example: Create a player profile record
     */
    suspend fun createPlayerProfileRecord(
        playerUuid: UUID,
        displayName: String?,
        bio: String?,
        publicStats: Boolean = true
    ): Result<String> = runCatching {
        val session = sessionManager.getSession(playerUuid).getOrThrow()

        val record = PlayerProfileRecord(
            `$type` = "com.jollywhoppers.minecraft.player.profile",
            player = PlayerReference(
                uuid = playerUuid.toString(),
                username = "ExamplePlayer"
            ),
            displayName = displayName,
            bio = bio,
            createdAt = java.time.Instant.now().toString(),
            publicStats = publicStats,
            publicSessions = true
        )

        // For profile, we use rkey "self" since there's only one per account
        val requestBody = CreateRecordRequestWithRkey(
            repo = session.did,
            collection = "com.jollywhoppers.minecraft.player.profile",
            rkey = "self",
            record = json.encodeToJsonElement(record)
        )

        sessionManager.makeAuthenticatedRequest(
            uuid = playerUuid,
            method = "POST",
            endpoint = "com.atproto.repo.putRecord",
            body = json.encodeToString(requestBody)
        ).getOrThrow()
    }

    /**
     * Example: Create an achievement record
     */
    suspend fun createAchievementRecord(
        playerUuid: UUID,
        achievementId: String,
        achievementName: String,
        achievementDescription: String,
        category: String,
        isChallenge: Boolean = false
    ): Result<String> = runCatching {
        val session = sessionManager.getSession(playerUuid).getOrThrow()

        val record = AchievementRecord(
            `$type` = "com.jollywhoppers.minecraft.achievement",
            player = PlayerReference(
                uuid = playerUuid.toString(),
                username = "ExamplePlayer"
            ),
            server = ServerReference(
                serverId = "example-server-id",
                serverName = "Example Server"
            ),
            achievementId = achievementId,
            achievementName = achievementName,
            achievementDescription = achievementDescription,
            achievedAt = java.time.Instant.now().toString(),
            category = category,
            isChallenge = isChallenge
        )

        val requestBody = CreateRecordRequest(
            repo = session.did,
            collection = "com.jollywhoppers.minecraft.achievement",
            record = json.encodeToJsonElement(record)
        )

        sessionManager.makeAuthenticatedRequest(
            uuid = playerUuid,
            method = "POST",
            endpoint = "com.atproto.repo.createRecord",
            body = json.encodeToString(requestBody)
        ).getOrThrow()
    }

    // Data classes matching our lexicon schemas

    @Serializable
    data class PlayerReference(
        val uuid: String,
        val username: String
    )

    @Serializable
    data class ServerReference(
        val serverId: String,
        val serverName: String
    )

    @Serializable
    data class Statistic(
        val key: String,
        val value: Int,
        val category: String? = null
    )

    @Serializable
    data class PlayerStatsRecord(
        val `$type`: String,
        val player: PlayerReference,
        val server: ServerReference,
        val statistics: List<Statistic>,
        val playtimeMinutes: Int,
        val level: Int,
        val gamemode: String,
        val dimension: String? = null,
        val syncedAt: String
    )

    @Serializable
    data class PlayerProfileRecord(
        val `$type`: String,
        val player: PlayerReference,
        val displayName: String?,
        val bio: String?,
        val createdAt: String,
        val updatedAt: String? = null,
        val publicStats: Boolean,
        val publicSessions: Boolean
    )

    @Serializable
    data class AchievementRecord(
        val `$type`: String,
        val player: PlayerReference,
        val server: ServerReference,
        val achievementId: String,
        val achievementName: String,
        val achievementDescription: String,
        val achievedAt: String,
        val category: String,
        val isChallenge: Boolean
    )

    @Serializable
    data class CreateRecordRequest(
        val repo: String,
        val collection: String,
        val record: JsonElement
    )

    @Serializable
    data class CreateRecordRequestWithRkey(
        val repo: String,
        val collection: String,
        val rkey: String,
        val record: JsonElement
    )
}

/**
 * Usage example:
 *
 * ```kotlin
 * val example = RecordCreationExample(sessionManager)
 *
 * // Create a stats snapshot
 * val stats = listOf(
 *     Statistic("minecraft:killed.minecraft.zombie", 42, "killed"),
 *     Statistic("minecraft:mined.minecraft.diamond_ore", 15, "mined")
 * )
 *
 * example.createPlayerStatsRecord(
 *     playerUuid = player.uuid,
 *     statistics = stats,
 *     playtimeMinutes = 180,
 *     level = 25
 * ).onSuccess { response ->
 *     logger.info("Stats synced successfully!")
 * }.onFailure { error ->
 *     logger.error("Failed to sync stats", error)
 * }
 * ```
 */
