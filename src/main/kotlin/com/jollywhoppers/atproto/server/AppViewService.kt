package com.jollywhoppers.atproto.server

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * AppView service for displaying Minecraft data from AT Protocol records.
 * 
 * An AppView is a custom service that indexes AT Protocol data and provides
 * rich display and query capabilities. This implementation provides:
 * - Player stats displays and leaderboards
 * - Achievement galleries
 * - Play session tracking
 * - Server status monitoring
 * 
 * Usage:
 * - Subscribe to AT Protocol repository events
 * - Index records as they're published by players
 * - Provide query endpoints for clients to retrieve formatted data
 */
class AppViewService(
    private val recordManager: RecordManager
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:AppViewService")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    // Thread-safe storage for indexed data (in production, use a database)
    private val playerProfiles = ConcurrentHashMap<String, PlayerProfileView>()
    private val playerStats = ConcurrentHashMap<String, CopyOnWriteArrayList<PlayerStatsView>>()
    private val achievements = ConcurrentHashMap<String, CopyOnWriteArrayList<AchievementView>>()
    private val leaderboards = ConcurrentHashMap<String, CopyOnWriteArrayList<LeaderboardEntryView>>()

    // ============================================================================
    // INDEXING OPERATIONS
    // ============================================================================

    /**
     * Index a player profile record.
     * Called when a new profile record is created or updated.
     */
    fun indexPlayerProfile(uri: String, record: JsonElement) = runCatching {
        logger.debug("Indexing player profile from $uri")
        
        val profile = json.decodeFromJsonElement<PlayerProfileRecord>(record)
        val playerUuid = profile.player.uuid
        
        playerProfiles[playerUuid] = PlayerProfileView(
            did = uri.split("/")[2], // Extract DID from URI format: at://did/collection/rkey
            playerUuid = playerUuid,
            username = profile.player.username,
            displayName = profile.displayName,
            bio = profile.bio,
            createdAt = profile.createdAt,
            updatedAt = profile.updatedAt,
            publicStats = profile.publicStats,
            publicSessions = profile.publicSessions
        )
        
        logger.info("Indexed player profile for $playerUuid")
    }

    /**
     * Index a player stats record.
     * Called when new stats are synced.
     */
    fun indexPlayerStats(uri: String, record: JsonElement) = runCatching {
        logger.debug("Indexing player stats from $uri")
        
        val stats = json.decodeFromJsonElement<PlayerStatsRecord>(record)
        val playerUuid = stats.player.uuid
        
        val statsView = PlayerStatsView(
            uri = uri,
            playerUuid = playerUuid,
            username = stats.player.username,
            server = stats.server.serverName,
            statistics = stats.statistics,
            playtimeMinutes = stats.playtimeMinutes,
            level = stats.level,
            gamemode = stats.gamemode,
            syncedAt = stats.syncedAt
        )
        
        playerStats.getOrPut(playerUuid) { CopyOnWriteArrayList() }.add(statsView)
        
        // Update leaderboards
        updateLeaderboards(statsView)
        
        logger.info("Indexed stats for player $playerUuid")
    }

    /**
     * Index an achievement record.
     * Called when a player earns an achievement.
     */
    fun indexAchievement(uri: String, record: JsonElement) = runCatching {
        logger.debug("Indexing achievement from $uri")
        
        val achievement = json.decodeFromJsonElement<AchievementRecord>(record)
        val playerUuid = achievement.player.uuid
        
        val achievementView = AchievementView(
            uri = uri,
            playerUuid = playerUuid,
            username = achievement.player.username,
            server = achievement.server.serverName,
            achievementId = achievement.achievementId,
            achievementName = achievement.achievementName,
            achievementDescription = achievement.achievementDescription,
            achievedAt = achievement.achievedAt,
            category = achievement.category,
            isChallenge = achievement.isChallenge
        )
        
        achievements.getOrPut(playerUuid) { CopyOnWriteArrayList() }.add(achievementView)
        
        logger.info("Indexed achievement for player $playerUuid: ${achievement.achievementName}")
    }

    // ============================================================================
    // QUERY OPERATIONS
    // ============================================================================

    /**
     * Get a player's profile with stats summary.
     */
    fun getPlayerProfile(playerUuid: String): Result<PlayerProfileWithStats?> = runCatching {
        val profile = playerProfiles[playerUuid] ?: return Result.success(null)
        val stats = playerStats[playerUuid] ?: emptyList()
        val playerAchievements = achievements[playerUuid] ?: emptyList()
        
        PlayerProfileWithStats(
            profile = profile,
            latestStats = stats.lastOrNull(),
            statsCount = stats.size,
            achievementCount = playerAchievements.size
        )
    }

    /**
     * Get a player's stats history (paginated).
     */
    fun getPlayerStatsHistory(
        playerUuid: String,
        limit: Int = 10,
        offset: Int = 0
    ): Result<List<PlayerStatsView>> = runCatching {
        playerStats[playerUuid]
            ?.sortedByDescending { it.syncedAt }
            ?.drop(offset)
            ?.take(limit)
            ?: emptyList()
    }

    /**
     * Get a player's achievement history (paginated).
     */
    fun getPlayerAchievements(
        playerUuid: String,
        limit: Int = 25,
        offset: Int = 0
    ): Result<List<AchievementView>> = runCatching {
        achievements[playerUuid]
            ?.sortedByDescending { it.achievedAt }
            ?.drop(offset)
            ?.take(limit)
            ?: emptyList()
    }

    /**
     * Get top players by a specific statistic.
     */
    fun getLeaderboard(
        statisticKey: String,
        limit: Int = 20
    ): Result<List<LeaderboardEntryView>> = runCatching {
        leaderboards[statisticKey]
            ?.sortedByDescending { it.value }
            ?.take(limit)
            ?: emptyList()
    }

    /**
     * Search for players by username.
     */
    fun searchPlayers(query: String): Result<List<PlayerProfileView>> = runCatching {
        playerProfiles.values
            .filter { profile ->
                profile.username.contains(query, ignoreCase = true) ||
                profile.displayName?.contains(query, ignoreCase = true) == true
            }
            .take(20)
    }

    /**
     * Get trending achievements (most earned recently).
     */
    fun getTrendingAchievements(limit: Int = 10): Result<List<TrendingAchievement>> = runCatching {
        achievements.values.flatten()
            .sortedByDescending { it.achievedAt }
            .groupBy { it.achievementId }
            .map { (id, records) ->
                TrendingAchievement(
                    achievementId = id,
                    achievementName = records.first().achievementName,
                    category = records.first().category,
                    timesEarned = records.size,
                    recentlyEarnedBy = records.take(5).map { it.username }
                )
            }
            .sortedByDescending { it.timesEarned }
            .take(limit)
    }

    /**
     * Get player statistics summary (most recent stats).
     */
    fun getPlayerStatsSummary(playerUuid: String): Result<PlayerStatsSummary?> = runCatching {
        val stats = playerStats[playerUuid]?.lastOrNull() ?: return Result.success(null)
        
        // Calculate key metrics
        val topStats = stats.statistics
            .sortedByDescending { it.value }
            .take(5)
        
        PlayerStatsSummary(
            playerUuid = playerUuid,
            username = stats.username,
            playtimeMinutes = stats.playtimeMinutes,
            level = stats.level,
            gamemode = stats.gamemode,
            server = stats.server,
            topStatistics = topStats,
            lastSyncedAt = stats.syncedAt
        )
    }

    // ============================================================================
    // PRIVATE HELPER FUNCTIONS
    // ============================================================================

    /**
     * Update leaderboard entries with new stats.
     */
    private fun updateLeaderboards(stats: PlayerStatsView) {
        stats.statistics.forEach { stat ->
            val leaderboardKey = stat.key
            val entry = LeaderboardEntryView(
                playerUuid = stats.playerUuid,
                username = stats.username,
                server = stats.server,
                statistic = stat.key,
                value = stat.value,
                recordedAt = stats.syncedAt
            )
            
            val leaderboard = leaderboards.getOrPut(leaderboardKey) { CopyOnWriteArrayList() }
            
            // Remove old entry if exists and add new one
            leaderboard.removeAll { it.playerUuid == stats.playerUuid }
            leaderboard.add(entry)
        }
    }

    // ============================================================================
    // DATA CLASSES
    // ============================================================================

    @Serializable
    data class PlayerProfileRecord(
        @SerialName("\$type") val type: String,
        val player: PlayerRef,
        val displayName: String?,
        val bio: String?,
        val createdAt: String,
        val updatedAt: String?,
        val publicStats: Boolean,
        val publicSessions: Boolean
    )

    @Serializable
    data class PlayerRef(
        val uuid: String,
        val username: String
    )

    @Serializable
    data class Statistic(
        val key: String,
        val value: Int,
        val category: String? = null
    )

    @Serializable
    data class PlayerStatsRecord(
        @SerialName("\$type") val type: String,
        val player: PlayerRef,
        val server: ServerRef,
        val statistics: List<Statistic>,
        val playtimeMinutes: Int,
        val level: Int,
        val gamemode: String,
        val dimension: String?,
        val syncedAt: String
    )

    @Serializable
    data class ServerRef(
        val serverId: String,
        val serverName: String
    )

    @Serializable
    data class AchievementRecord(
        @SerialName("\$type") val type: String,
        val player: PlayerRef,
        val server: ServerRef,
        val achievementId: String,
        val achievementName: String,
        val achievementDescription: String,
        val achievedAt: String,
        val category: String,
        val isChallenge: Boolean = false
    )

    // ============================================================================
    // VIEW MODELS (For API responses)
    // ============================================================================

    @Serializable
    data class PlayerProfileView(
        val did: String,
        val playerUuid: String,
        val username: String,
        val displayName: String?,
        val bio: String?,
        val createdAt: String,
        val updatedAt: String?,
        val publicStats: Boolean,
        val publicSessions: Boolean
    )

    @Serializable
    data class PlayerStatsView(
        val uri: String,
        val playerUuid: String,
        val username: String,
        val server: String,
        val statistics: List<Statistic>,
        val playtimeMinutes: Int,
        val level: Int,
        val gamemode: String,
        val syncedAt: String
    )

    @Serializable
    data class AchievementView(
        val uri: String,
        val playerUuid: String,
        val username: String,
        val server: String,
        val achievementId: String,
        val achievementName: String,
        val achievementDescription: String,
        val achievedAt: String,
        val category: String,
        val isChallenge: Boolean = false
    )

    @Serializable
    data class LeaderboardEntryView(
        val playerUuid: String,
        val username: String,
        val server: String,
        val statistic: String,
        val value: Int,
        val recordedAt: String
    )

    @Serializable
    data class PlayerProfileWithStats(
        val profile: PlayerProfileView,
        val latestStats: PlayerStatsView?,
        val statsCount: Int,
        val achievementCount: Int
    )

    @Serializable
    data class PlayerStatsSummary(
        val playerUuid: String,
        val username: String,
        val playtimeMinutes: Int,
        val level: Int,
        val gamemode: String,
        val server: String,
        val topStatistics: List<Statistic>,
        val lastSyncedAt: String
    )

    @Serializable
    data class TrendingAchievement(
        val achievementId: String,
        val achievementName: String,
        val category: String,
        val timesEarned: Int,
        val recentlyEarnedBy: List<String>
    )
}
