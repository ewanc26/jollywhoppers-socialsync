package com.jollywhoppers.atproto

import com.jollywhoppers.atproto.server.*
import io.github.kikin81.atproto.runtime.NoAuth
import io.github.kikin81.atproto.runtime.XrpcClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.json.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*

class AppViewServiceExtendedTest {

    @TempDir
    lateinit var tempDir: Path
    private val client = AtProtoClient(
        slingshotUrl = "https://slingshot.microcosm.blue",
        fallbackPdsUrl = "https://bsky.social"
    )
    private lateinit var appView: AppViewService
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        val testHttpClient = HttpClient(CIO) { expectSuccess = false }
        val testXrpcClient = XrpcClient(
            baseUrl = "https://bsky.social",
            httpClient = testHttpClient,
            authProvider = NoAuth
        )
        val dummySessionManager = AtProtoSessionManager(
            tempDir.resolve("dummy-sessions.json"),
            client
        )
        val dummyRecordManager = RecordManager(testXrpcClient, json, dummySessionManager)
        appView = AppViewService(dummyRecordManager)
    }

    @Test
    fun `index multiple stat snapshots for same player`() {
        val playerUuid = UUID.randomUUID().toString()

        for (i in 1..3) {
            val element = json.parseToJsonElement("""
            {
              "${"$"}type": "com.jollywhoppers.minecraft.player.stats",
              "player": {"uuid": "$playerUuid", "username": "Builder"},
              "server": {"serverId": "srv1", "serverName": "Main"},
              "statistics": [{"key": "blocks.placed", "value": ${i * 100}, "category": "building"}],
              "playtimeMinutes": 100,
              "level": 5,
              "gamemode": "creative",
              "dimension": "minecraft:overworld",
              "syncedAt": "2026-06-0${i}T00:00:00Z"
            }
            """.trimIndent())
            appView.indexPlayerStats("at://did:plc:test/stats/$i", element)
        }

        val history = appView.getPlayerStatsHistory(playerUuid, limit = 10)
        assertTrue(history.isSuccess)
        assertEquals(3, history.getOrNull()!!.size)
    }

    @Test
    fun `leaderboard shows entries from different players for same stat sorted descending`() {
        val player1 = UUID.randomUUID().toString()
        val player2 = UUID.randomUUID().toString()
        val player3 = UUID.randomUUID().toString()

        fun statsJson(uuid: String, name: String, value: Int) = json.parseToJsonElement("""
        {
          "${"$"}type": "com.jollywhoppers.minecraft.player.stats",
          "player": {"uuid": "$uuid", "username": "$name"},
          "server": {"serverId": "srv1", "serverName": "Main"},
          "statistics": [{"key": "distance.walked", "value": $value, "category": "distance"}],
          "playtimeMinutes": 60,
          "level": 1,
          "gamemode": "survival",
          "dimension": "minecraft:overworld",
          "syncedAt": "2026-06-10T00:00:00Z"
        }
        """.trimIndent())

        appView.indexPlayerStats("at://did:plc:a/stats/1", statsJson(player1, "Alice", 5000))
        appView.indexPlayerStats("at://did:plc:b/stats/1", statsJson(player2, "Bob", 8000))
        appView.indexPlayerStats("at://did:plc:c/stats/1", statsJson(player3, "Charlie", 3000))

        val leaderboard = appView.getLeaderboard("distance.walked")
        assertTrue(leaderboard.isSuccess)
        val entries = leaderboard.getOrNull()!!
        assertEquals(3, entries.size)
        assertEquals(8000, entries[0].value)
        assertEquals(5000, entries[1].value)
        assertEquals(3000, entries[2].value)
    }

    @Test
    fun `searchPlayers finds players by display name`() {
        val uuid1 = UUID.randomUUID().toString()
        val uuid2 = UUID.randomUUID().toString()

        fun profileJson(uuid: String, username: String, displayName: String) = json.parseToJsonElement("""
        {
          "${"$"}type": "com.jollywhoppers.minecraft.player.profile",
          "player": {"uuid": "$uuid", "username": "$username"},
          "displayName": "$displayName",
          "bio": "Player",
          "publicStats": true,
          "publicSessions": true,
          "createdAt": "2026-04-20T10:30:00Z",
          "updatedAt": null
        }
        """.trimIndent())

        appView.indexPlayerProfile(
            "at://did:plc:a/profile/self",
            profileJson(uuid1, "AliceP", "Dragon Slayer")
        )
        appView.indexPlayerProfile(
            "at://did:plc:b/profile/self",
            profileJson(uuid2, "BobB", "Castle Builder")
        )

        val dragonResults = appView.searchPlayers("Dragon")
        assertTrue(dragonResults.isSuccess)
        assertEquals(1, dragonResults.getOrNull()!!.size)
        assertEquals("AliceP", dragonResults.getOrNull()!![0].username)

        val builderResults = appView.searchPlayers("Builder")
        assertTrue(builderResults.isSuccess)
        assertEquals(1, builderResults.getOrNull()!!.size)
        assertEquals("BobB", builderResults.getOrNull()!![0].username)
    }

    @Test
    fun `trending achievements ranks by frequency across multiple players`() {
        val uuid1 = UUID.randomUUID().toString()
        val uuid2 = UUID.randomUUID().toString()
        val uuid3 = UUID.randomUUID().toString()

        fun achievementJson(uuid: String, name: String, achievementId: String) = json.parseToJsonElement("""
        {
          "${"$"}type": "com.jollywhoppers.minecraft.achievement",
          "player": {"uuid": "$uuid", "username": "$name"},
          "server": {"serverId": "srv1", "serverName": "Main"},
          "achievementId": "$achievementId",
          "achievementName": "Stone Pick",
          "achievementDescription": "Get a stone pickaxe",
          "category": "adventure",
          "isChallenge": false,
          "achievedAt": "2026-04-24T15:45:00Z"
        }
        """.trimIndent())

        appView.indexAchievement(
            "at://did:plc:a/ach/1",
            achievementJson(uuid1, "Alice", "minecraft:stone_pick")
        )
        appView.indexAchievement(
            "at://did:plc:b/ach/1",
            achievementJson(uuid2, "Bob", "minecraft:stone_pick")
        )
        appView.indexAchievement(
            "at://did:plc:c/ach/1",
            achievementJson(uuid3, "Charlie", "minecraft:wood_pick")
        )

        val trending = appView.getTrendingAchievements(limit = 10).getOrNull()!!
        assertEquals(2, trending.size)
        assertEquals("minecraft:stone_pick", trending[0].achievementId)
        assertEquals(2, trending[0].timesEarned)
        assertEquals("minecraft:wood_pick", trending[1].achievementId)
        assertEquals(1, trending[1].timesEarned)
    }

    @Test
    fun `getPlayerStatsHistory respects pagination limit and offset`() {
        val playerUuid = UUID.randomUUID().toString()

        for (i in 1..10) {
            val element = json.parseToJsonElement("""
            {
              "${"$"}type": "com.jollywhoppers.minecraft.player.stats",
              "player": {"uuid": "$playerUuid", "username": "PaginationTest"},
              "server": {"serverId": "srv1", "serverName": "Main"},
              "statistics": [{"key": "stat.x", "value": $i, "category": "test"}],
              "playtimeMinutes": ${i * 10},
              "level": $i,
              "gamemode": "survival",
              "dimension": "minecraft:overworld",
              "syncedAt": "2026-06-${i.toString().padStart(2, '0')}T00:00:00Z"
            }
            """.trimIndent())
            appView.indexPlayerStats("at://did:plc:test/stats/$i", element)
        }

        val all = appView.getPlayerStatsHistory(playerUuid, limit = 100)
        assertEquals(10, all.getOrNull()!!.size)

        val first3 = appView.getPlayerStatsHistory(playerUuid, limit = 3, offset = 0)
        assertEquals(3, first3.getOrNull()!!.size)

        val mid3 = appView.getPlayerStatsHistory(playerUuid, limit = 3, offset = 5)
        assertEquals(3, mid3.getOrNull()!!.size)

        val pastEnd = appView.getPlayerStatsHistory(playerUuid, limit = 10, offset = 100)
        assertTrue(pastEnd.isSuccess)
        assertTrue(pastEnd.getOrNull()!!.isEmpty())
    }

    @Test
    fun `getPlayerAchievements respects pagination limit and offset`() {
        val playerUuid = UUID.randomUUID().toString()

        for (i in 1..8) {
            val element = json.parseToJsonElement("""
            {
              "${"$"}type": "com.jollywhoppers.minecraft.achievement",
              "player": {"uuid": "$playerUuid", "username": "AchTest"},
              "server": {"serverId": "srv1", "serverName": "Main"},
              "achievementId": "minecraft:ach_$i",
              "achievementName": "Achievement $i",
              "achievementDescription": "Test $i",
              "category": "adventure",
              "isChallenge": false,
              "achievedAt": "2026-05-${i.toString().padStart(2, '0')}T00:00:00Z"
            }
            """.trimIndent())
            appView.indexAchievement("at://did:plc:test/ach/$i", element)
        }

        val all = appView.getPlayerAchievements(playerUuid, limit = 100)
        assertEquals(8, all.getOrNull()!!.size)

        val first5 = appView.getPlayerAchievements(playerUuid, limit = 5)
        assertEquals(5, first5.getOrNull()!!.size)

        val afterOffset = appView.getPlayerAchievements(playerUuid, limit = 10, offset = 6)
        assertEquals(2, afterOffset.getOrNull()!!.size)

        val unknown = appView.getPlayerAchievements(UUID.randomUUID().toString(), limit = 10)
        assertTrue(unknown.getOrNull()!!.isEmpty())
    }

    @Test
    fun `getPlayerStatsSummary returns null for unknown player`() {
        val result = appView.getPlayerStatsSummary(UUID.randomUUID().toString())
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == null)
    }

    @Test
    fun `getPlayerStatsHistory returns empty list for unknown player`() {
        val result = appView.getPlayerStatsHistory(UUID.randomUUID().toString())
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.isEmpty())
    }
}
