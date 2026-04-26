package com.jollywhoppers.atproto.examples

import com.jollywhoppers.atproto.server.AppViewService
import com.jollywhoppers.atproto.server.AppViewHttpServer
import kotlinx.serialization.json.*
import java.util.*

/**
 * Example demonstrating how to use the AppView service for displaying Minecraft data.
 * 
 * An AppView is a custom service that indexes published AT Protocol records
 * and provides rich display and query capabilities. This example shows:
 * 
 * 1. Creating an AppView service instance
 * 2. Indexing player data as records are published
 * 3. Querying the indexed data for display
 * 4. Starting an HTTP server to serve the data to clients
 */
class AppViewExample(
    private val appViewService: AppViewService
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Example 1: Index a player profile when published to AT Protocol
     */
    fun exampleIndexPlayerProfile() {
        // In a real AppView, this would be called by a subscription handler
        // when a record is published to AT Protocol
        
        val playerUuid = UUID.randomUUID().toString()
        val profileRecord = json.parseToJsonElement("""
            {
              "${'$'}type": "com.jollywhoppers.minecraft.player.profile",
              "player": {
                "uuid": "$playerUuid",
                "username": "AlicePlayer"
              },
              "displayName": "Alice",
              "bio": "Minecraft enthusiast and builder",
              "createdAt": "2026-04-20T10:30:00Z",
              "updatedAt": null,
              "publicStats": true,
              "publicSessions": true
            }
        """)
        
        val uri = "at://did:plc:alice123/com.jollywhoppers.minecraft.player.profile/self"
        
        appViewService.indexPlayerProfile(uri, profileRecord)
            .onSuccess {
                println("✓ Indexed player profile for AlicePlayer")
            }
            .onFailure { e ->
                println("✗ Failed to index profile: ${e.message}")
            }
    }

    /**
     * Example 2: Index player stats when synced to AT Protocol
     */
    fun exampleIndexPlayerStats() {
        val playerUuid = UUID.randomUUID().toString()
        val statsRecord = json.parseToJsonElement("""
            {
              "${'$'}type": "com.jollywhoppers.minecraft.player.stats",
              "player": {
                "uuid": "$playerUuid",
                "username": "AlicePlayer"
              },
              "server": {
                "serverId": "server-123",
                "serverName": "Main SMP"
              },
              "statistics": [
                {"key": "minecraft.mined.oak_log", "value": 1250, "category": "blocks_mined"},
                {"key": "minecraft.killed.zombie", "value": 425, "category": "mobs_killed"},
                {"key": "minecraft.custom.play_one_minute", "value": 432000, "category": "playtime"}
              ],
              "playtimeMinutes": 7200,
              "level": 34,
              "gamemode": "survival",
              "dimension": "minecraft:overworld",
              "syncedAt": "2026-04-25T14:22:00Z"
            }
        """)
        
        val uri = "at://did:plc:alice123/com.jollywhoppers.minecraft.player.stats/8l6rvp4j6d3e2c4b9"
        
        appViewService.indexPlayerStats(uri, statsRecord)
            .onSuccess {
                println("✓ Indexed player stats for AlicePlayer")
            }
            .onFailure { e ->
                println("✗ Failed to index stats: ${e.message}")
            }
    }

    /**
     * Example 3: Index achievements as they're earned
     */
    fun exampleIndexAchievement() {
        val playerUuid = UUID.randomUUID().toString()
        val achievementRecord = json.parseToJsonElement("""
            {
              "${'$'}type": "com.jollywhoppers.minecraft.achievement",
              "player": {
                "uuid": "$playerUuid",
                "username": "AlicePlayer"
              },
              "server": {
                "serverId": "server-123",
                "serverName": "Main SMP"
              },
              "achievementId": "minecraft:adventure/kill_a_mob",
              "achievementName": "Monster Hunter",
              "achievementDescription": "Kill any type of monster",
              "achievedAt": "2026-04-24T15:45:00Z",
              "category": "adventure",
              "isChallenge": false
            }
        """)
        
        val uri = "at://did:plc:alice123/com.jollywhoppers.minecraft.achievement/8l6rvp4j6d3e2c5a7"
        
        appViewService.indexAchievement(uri, achievementRecord)
            .onSuccess {
                println("✓ Indexed achievement for AlicePlayer")
            }
            .onFailure { e ->
                println("✗ Failed to index achievement: ${e.message}")
            }
    }

    /**
     * Example 4: Query player profile with stats
     */
    fun exampleQueryPlayerProfile(playerUuid: String) {
        appViewService.getPlayerProfile(playerUuid)
            .onSuccess { profileWithStats ->
                if (profileWithStats != null) {
                    println("\n━━━ Player Profile ━━━")
                    println("Username: ${profileWithStats.profile.username}")
                    println("Display Name: ${profileWithStats.profile.displayName}")
                    println("Bio: ${profileWithStats.profile.bio}")
                    println("Stats Count: ${profileWithStats.statsCount}")
                    println("Achievements: ${profileWithStats.achievementCount}")
                    
                    if (profileWithStats.latestStats != null) {
                        val stats = profileWithStats.latestStats
                        println("\nLatest Stats:")
                        println("  Level: ${stats.level}")
                        println("  Playtime: ${stats.playtimeMinutes} minutes")
                        println("  Gamemode: ${stats.gamemode}")
                    }
                } else {
                    println("Player not found")
                }
            }
    }

    /**
     * Example 5: Query leaderboards
     */
    fun exampleQueryLeaderboard() {
        println("\n━━━ Top Players by Blocks Mined ━━━")
        appViewService.getLeaderboard("minecraft.mined.oak_log", limit = 10)
            .onSuccess { leaders ->
                leaders.forEachIndexed { index, entry ->
                    println("${index + 1}. ${entry.username} - ${entry.value} blocks")
                }
            }
    }

    /**
     * Example 6: Search for players
     */
    fun exampleSearchPlayers(query: String) {
        println("\n━━━ Search Results for '$query' ━━━")
        appViewService.searchPlayers(query)
            .onSuccess { players ->
                if (players.isEmpty()) {
                    println("No players found")
                } else {
                    players.forEach { player ->
                        println("• ${player.username} (${player.displayName ?: "no display name"})")
                    }
                }
            }
    }

    /**
     * Example 7: Get trending achievements
     */
    fun exampleTrendingAchievements() {
        println("\n━━━ Trending Achievements ━━━")
        appViewService.getTrendingAchievements(limit = 5)
            .onSuccess { trending ->
                trending.forEachIndexed { index, achievement ->
                    println("${index + 1}. ${achievement.achievementName}")
                    println("   Earned by ${achievement.timesEarned} players")
                    println("   Category: ${achievement.category}")
                }
            }
    }

    /**
     * Example 8: Get player stats summary
     */
    fun examplePlayerStatsSummary(playerUuid: String) {
        println("\n━━━ Player Stats Summary ━━━")
        appViewService.getPlayerStatsSummary(playerUuid)
            .onSuccess { summary ->
                if (summary != null) {
                    println("Player: ${summary.username}")
                    println("Level: ${summary.level}")
                    println("Playtime: ${summary.playtimeMinutes} minutes")
                    println("Gamemode: ${summary.gamemode}")
                    println("\nTop Statistics:")
                    summary.topStatistics.forEachIndexed { index, stat ->
                        println("  ${index + 1}. ${stat.key}: ${stat.value}")
                    }
                }
            }
    }

    /**
     * Example 9: Start the AppView HTTP server
     */
    fun exampleStartAppViewServer() {
        val server = AppViewHttpServer(appViewService, port = 8080)
        
        println("\n━━━ Starting AppView Server ━━━")
        server.start()
        
        println("\nAvailable Endpoints:")
        println("  GET /health")
        println("    Check server health")
        println()
        println("  GET /player/{uuid}")
        println("    Get player profile with stats summary")
        println()
        println("  GET /player/{uuid}/stats?limit=10&offset=0")
        println("    Get player stats history (paginated)")
        println()
        println("  GET /player/{uuid}/achievements?limit=25&offset=0")
        println("    Get player achievement history (paginated)")
        println()
        println("  GET /leaderboard/{statistic}?limit=20")
        println("    Get leaderboard for a specific statistic")
        println()
        println("  GET /search?q={query}")
        println("    Search for players by username or display name")
        println()
        println("  GET /trending/achievements?limit=10")
        println("    Get trending achievements")
        println()
        println("  GET /stats/summary/{uuid}")
        println("    Get quick summary of player stats")
    }

    /**
     * Example 10: Complete workflow
     */
    fun exampleCompleteWorkflow() {
        println("\n┏━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┓")
        println("┃ AppView Integration Complete Workflow ┃")
        println("┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛")
        
        // 1. Set up sample data
        println("\n[1] Indexing sample player data...")
        exampleIndexPlayerProfile()
        exampleIndexPlayerStats()
        exampleIndexAchievement()
        
        // 2. Query the data
        println("\n[2] Querying indexed data...")
        val sampleUuid = "550e8400-e29b-41d4-a716-446655440000"
        exampleQueryPlayerProfile(sampleUuid)
        exampleQueryLeaderboard()
        exampleSearchPlayers("Alice")
        exampleTrendingAchievements()
        examplePlayerStatsSummary(sampleUuid)
        
        // 3. Start server
        println("\n[3] Starting HTTP server...")
        exampleStartAppViewServer()
        
        println("\n✓ AppView integration example complete!")
    }
}

/**
 * Quick start: Run this to see the AppView in action
 */
fun main() {
    // This is a demonstration - in practice, the AppView would:
    // 1. Subscribe to AT Protocol repository events
    // 2. Index records as they're published
    // 3. Serve queries via HTTP endpoints
    // 4. Maintain a database of indexed records
    
    println("""
        ╔════════════════════════════════════════════╗
        ║     AT Protocol Minecraft AppView         ║
        ║  Display and Query Synced Minecraft Data   ║
        ╚════════════════════════════════════════════╝
    """.trimIndent())
    
    // In a real implementation, you would:
    // 1. Create the AppViewService with a real database backend
    // 2. Subscribe to Firehose events or use WebSocket subscriptions
    // 3. Deploy the HTTP server to a public URL
    // 4. Register your AppView with the AT Protocol application registry
    
    println("\nTo implement a full AppView:")
    println("1. Use a framework like Ktor or Spring Boot for HTTP server")
    println("2. Subscribe to AT Protocol Firehose for real-time updates")
    println("3. Use a database (PostgreSQL, MongoDB) for indexing")
    println("4. Implement pagination, filtering, and search")
    println("5. Add caching layers (Redis) for performance")
    println("6. Deploy to a public URL accessible from AT Protocol clients")
    println("7. Register your AppView in the AT Protocol registry")
}
