package com.jollywhoppers.atproto.server

import io.ktor.http.ContentType
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class AppViewHttpServer(
    private val appViewService: AppViewService,
    private val port: Int = 8080
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:AppViewServer")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        logger.info("Starting AppView HTTP server on port $port")

        val engine = embeddedServer(CIO, port = port) {
            install(StatusPages) {
                exception<Throwable> { call, cause ->
                    logger.error("Unhandled exception", cause)
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = json.encodeToString(
                            ApiResponse<Nothing>(
                                success = false,
                                error = cause.message ?: "Internal server error"
                            )
                        )
                    )
                }
            }

            routing {
                get("/") {
                    val htmlStream = this::class.java.classLoader.getResourceAsStream("assets/index.html")
                    if (htmlStream != null) {
                        call.respondText(
                            contentType = ContentType.Text.Html,
                            text = htmlStream.bufferedReader().readText()
                        )
                    } else {
                        call.respondText(
                            status = io.ktor.http.HttpStatusCode.NotFound,
                            text = "Dashboard file not found"
                        )
                    }
                }
                get("/dashboard") {
                    val htmlStream = this::class.java.classLoader.getResourceAsStream("assets/index.html")
                    if (htmlStream != null) {
                        call.respondText(
                            contentType = ContentType.Text.Html,
                            text = htmlStream.bufferedReader().readText()
                        )
                    } else {
                        call.respondText(
                            status = io.ktor.http.HttpStatusCode.NotFound,
                            text = "Dashboard file not found"
                        )
                    }
                }
                get("/health") {
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = json.encodeToString(handleHealthCheck())
                    )
                }
                get("/profile/{serverId}/{did}") {
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = json.encodeToString(
                            handleGetPlayerProfile(call.parameters["did"] ?: "")
                        )
                    )
                }
                get("/leaderboard/{serverId}") {
                    val statType = call.request.queryParameters["statType"] ?: ""
                    val limit = call.request.queryParameters["limit"] ?: "20"
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = json.encodeToString(handleGetLeaderboard(statType, limit))
                    )
                }
                get("/achievements/{serverId}/{did}") {
                    val did = call.parameters["did"] ?: ""
                    val limit = call.request.queryParameters["limit"] ?: "25"
                    val offset = call.request.queryParameters["offset"] ?: "0"
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = json.encodeToString(
                            handleGetPlayerAchievements(did, limit, offset)
                        )
                    )
                }
                get("/stats/{serverId}/{did}") {
                    val did = call.parameters["did"] ?: ""
                    val limit = call.request.queryParameters["limit"] ?: "10"
                    val offset = call.request.queryParameters["offset"] ?: "0"
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = json.encodeToString(
                            handleGetPlayerStats(did, limit, offset)
                        )
                    )
                }
                get("/search") {
                    val username = call.request.queryParameters["username"] ?: ""
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = json.encodeToString(handleSearch(username))
                    )
                }
                get("/stats-summary/{serverId}") {
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = json.encodeToString(
                            handleGetStatsSummary(call.parameters["serverId"] ?: "")
                        )
                    )
                }
                get("/trending-achievements/{serverId}") {
                    val limit = call.request.queryParameters["limit"] ?: "10"
                    call.respondText(
                        contentType = ContentType.Application.Json,
                        text = json.encodeToString(handleGetTrendingAchievements(limit))
                    )
                }
            }
        }
        engine.start(wait = false)
        server = engine

        logger.info("AppView server ready on port $port")
    }

    fun stop() {
        server?.stop(1000, 3000)
        logger.info("AppView server stopped")
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
