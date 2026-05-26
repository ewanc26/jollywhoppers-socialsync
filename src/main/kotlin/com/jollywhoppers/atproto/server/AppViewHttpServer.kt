package com.jollywhoppers.atproto.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * HTTP server providing AppView endpoints for querying Minecraft data.
 * 
 * This server hosts a simple REST API that allows clients to:
 * - Query player profiles and stats
 * - Browse leaderboards
 * - Search achievements
 * - Discover trending players and achievements
 * 
 * In production, this would be a full microservice with database backing,
 * subscription to AT Protocol firehose, and proper caching.
 */
class AppViewHttpServer(
    private val appViewService: AppViewService,
    private val port: Int = 8080
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:AppViewServer")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Start the HTTP server.
     * This is a simplified example - in production use a full framework like Ktor.
     */
    fun start() {
        logger.info("Starting AppView HTTP server on port $port")
        
        // This is a placeholder for the actual implementation
        // In a real scenario, you'd use:
        // - Ktor: https://ktor.io/
        // - Spring Boot: https://spring.io/projects/spring-boot
        // - Or any other web framework
        
        logger.info("AppView server ready. Example endpoints:")
        logger.info("  GET /player/{uuid}")
        logger.info("  GET /player/{uuid}/stats")
        logger.info("  GET /player/{uuid}/achievements")
        logger.info("  GET /leaderboard/{stat}")
        logger.info("  GET /search?q={query}")
        logger.info("  GET /trending/achievements")
        logger.info("  GET /stats/summary/{uuid}")
    }

    // ============================================================================
    // ENDPOINT HANDLERS (For use with a web framework)
    // ============================================================================

    /**
     * GET /player/{uuid}
     * Get a player's profile with stats summary.
     */
    fun handleGetPlayerProfile(playerUuid: String): ApiResponse<Any> {
        return try {
            val result = appViewService.getPlayerProfile(playerUuid)
            val data = result.getOrNull()
            
            if (data == null) {
                ApiResponse(
                    success = false,
                    error = "Player not found",
                    data = null
                )
            } else {
                ApiResponse(
                    success = true,
                    data = data
                )
            }
        } catch (e: Exception) {
            logger.error("Error fetching player profile", e)
            ApiResponse(
                success = false,
                error = e.message ?: "Internal server error",
                data = null
            )
        }
    }

    /**
     * GET /player/{uuid}/stats
     * Get a player's stats history.
     */
    fun handleGetPlayerStats(
        playerUuid: String,
        limit: String = "10",
        offset: String = "0"
    ): ApiResponse<List<*>> {
        return try {
            val limitInt = limit.toIntOrNull() ?: 10
            val offsetInt = offset.toIntOrNull() ?: 0
            
            val result = appViewService.getPlayerStatsHistory(playerUuid, limitInt, offsetInt)
            val data = result.getOrNull() ?: emptyList<Any>()
            
            ApiResponse(
                success = true,
                data = data,
                pagination = PaginationInfo(
                    limit = limitInt,
                    offset = offsetInt,
                    count = data.size
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching player stats", e)
            ApiResponse(
                success = false,
                error = e.message ?: "Internal server error",
                data = null
            )
        }
    }

    /**
     * GET /player/{uuid}/achievements
     * Get a player's achievement history.
     */
    fun handleGetPlayerAchievements(
        playerUuid: String,
        limit: String = "25",
        offset: String = "0"
    ): ApiResponse<List<*>> {
        return try {
            val limitInt = limit.toIntOrNull() ?: 25
            val offsetInt = offset.toIntOrNull() ?: 0
            
            val result = appViewService.getPlayerAchievements(playerUuid, limitInt, offsetInt)
            val data = result.getOrNull() ?: emptyList<Any>()
            
            ApiResponse(
                success = true,
                data = data,
                pagination = PaginationInfo(
                    limit = limitInt,
                    offset = offsetInt,
                    count = data.size
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching achievements", e)
            ApiResponse(
                success = false,
                error = e.message ?: "Internal server error",
                data = null
            )
        }
    }

    /**
     * GET /leaderboard/{statistic}
     * Get top players for a specific statistic.
     */
    fun handleGetLeaderboard(
        statistic: String,
        limit: String = "20"
    ): ApiResponse<List<*>> {
        return try {
            val limitInt = limit.toIntOrNull() ?: 20
            val result = appViewService.getLeaderboard(statistic, limitInt)
            val data = result.getOrNull() ?: emptyList<Any>()
            
            ApiResponse(
                success = true,
                data = data,
                pagination = PaginationInfo(
                    limit = limitInt,
                    offset = 0,
                    count = data.size
                )
            )
        } catch (e: Exception) {
            logger.error("Error fetching leaderboard", e)
            ApiResponse(
                success = false,
                error = e.message ?: "Internal server error",
                data = null
            )
        }
    }

    /**
     * GET /search?q={query}
     * Search for players by username or display name.
     */
    fun handleSearch(query: String): ApiResponse<List<*>> {
        return try {
            val decodedQuery = URLDecoder.decode(query, StandardCharsets.UTF_8)
            
            if (decodedQuery.length < 2) {
                return ApiResponse(
                    success = false,
                    error = "Query must be at least 2 characters",
                    data = null
                )
            }
            
            val result = appViewService.searchPlayers(decodedQuery)
            val data = result.getOrNull() ?: emptyList<Any>()
            
            ApiResponse(
                success = true,
                data = data
            )
        } catch (e: Exception) {
            logger.error("Error searching players", e)
            ApiResponse(
                success = false,
                error = e.message ?: "Internal server error",
                data = null
            )
        }
    }

    /**
     * GET /trending/achievements
     * Get trending achievements.
     */
    fun handleGetTrendingAchievements(limit: String = "10"): ApiResponse<List<*>> {
        return try {
            val limitInt = limit.toIntOrNull() ?: 10
            val result = appViewService.getTrendingAchievements(limitInt)
            val data = result.getOrNull() ?: emptyList<Any>()
            
            ApiResponse(
                success = true,
                data = data
            )
        } catch (e: Exception) {
            logger.error("Error fetching trending achievements", e)
            ApiResponse(
                success = false,
                error = e.message ?: "Internal server error",
                data = null
            )
        }
    }

    /**
     * GET /stats/summary/{uuid}
     * Get a quick summary of a player's statistics.
     */
    fun handleGetStatsSummary(playerUuid: String): ApiResponse<Any> {
        return try {
            val result = appViewService.getPlayerStatsSummary(playerUuid)
            val data = result.getOrNull()
            
            if (data == null) {
                ApiResponse(
                    success = false,
                    error = "Player or stats not found",
                    data = null
                )
            } else {
                ApiResponse(
                    success = true,
                    data = data
                )
            }
        } catch (e: Exception) {
            logger.error("Error fetching stats summary", e)
            ApiResponse(
                success = false,
                error = e.message ?: "Internal server error",
                data = null
            )
        }
    }

    /**
     * GET /health
     * Health check endpoint.
     */
    fun handleHealthCheck(): ApiResponse<HealthInfo> {
        return ApiResponse(
            success = true,
            data = HealthInfo(
                status = "healthy",
                version = "1.0.0",
                uptime = System.nanoTime() / 1_000_000_000
            )
        )
    }

    // ============================================================================
    // RESPONSE MODELS
    // ============================================================================

    @Serializable
    data class ApiResponse<T>(
        val success: Boolean,
        val data: T? = null,
        val error: String? = null,
        val pagination: PaginationInfo? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Serializable
    data class PaginationInfo(
        val limit: Int,
        val offset: Int,
        val count: Int
    )

    @Serializable
    data class HealthInfo(
        val status: String,
        val version: String,
        val uptime: Long
    )
}
