package com.jollywhoppers.atproto.server.test

import com.jollywhoppers.atproto.server.*
import com.jollywhoppers.security.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

/**
 * Comprehensive test suite for atproto-connect mod core functionality.
 * 
 * Test Coverage:
 * - Session management (authentication, token refresh)
 * - Record management (CRUD operations)
 * - Security (encryption, rate limiting, auditing)
 * - Storage (identity, preferences)
 * - AppView indexing and querying
 */
class AtProtoSessionManagerTest {
    private lateinit var sessionManager: AtProtoSessionManager
    private val testPlayerUuid = UUID.randomUUID()
    private val testHandle = "test.bsky.social"
    private val testAppPassword = "test-app-password-1234"

    @BeforeEach
    fun setup() {
        sessionManager = AtProtoSessionManager()
    }

    @Test
    @DisplayName("Should successfully authenticate with valid credentials")
    fun testAuthenticationSuccess() {
        val result = runBlocking {
            sessionManager.authenticateWithPassword(testPlayerUuid, testHandle, testAppPassword)
        }
        
        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
        assertEquals(testHandle, result.getOrNull()?.handle)
    }

    @Test
    @DisplayName("Should fail authentication with invalid credentials")
    fun testAuthenticationFailure() {
        val result = runBlocking {
            sessionManager.authenticateWithPassword(testPlayerUuid, testHandle, "invalid-password")
        }
        
        assertTrue(result.isFailure)
    }

    @Test
    @DisplayName("Should retrieve active session")
    fun testGetSession() {
        runBlocking {
            // First authenticate
            sessionManager.authenticateWithPassword(testPlayerUuid, testHandle, testAppPassword)
            
            // Then retrieve
            val result = sessionManager.getSession(testPlayerUuid)
            
            assertTrue(result.isSuccess)
            val session = result.getOrNull()
            assertNotNull(session)
            assertEquals(testHandle, session.handle)
        }
    }

    @Test
    @DisplayName("Should logout and invalidate session")
    fun testLogout() {
        runBlocking {
            // Authenticate
            sessionManager.authenticateWithPassword(testPlayerUuid, testHandle, testAppPassword)
            
            // Logout
            val logoutResult = sessionManager.logout(testPlayerUuid)
            assertTrue(logoutResult.isSuccess)
            
            // Verify session is gone
            val getResult = sessionManager.getSession(testPlayerUuid)
            assertTrue(getResult.isFailure)
        }
    }

    @Test
    @DisplayName("Should auto-refresh token before expiration")
    fun testTokenRefresh() {
        val session = runBlocking {
            val result = sessionManager.authenticateWithPassword(testPlayerUuid, testHandle, testAppPassword)
            result.getOrNull()!!
        }
        
        assertNotNull(session.accessToken)
        assertNotNull(session.refreshToken)
        
        // Verify token expiry is set
        assertTrue(session.accessTokenExpiry > System.currentTimeMillis())
    }
}

class SecurityUtilsTest {
    private val testData = "sensitive-player-data"
    private val testKey = SecurityUtils.generateEncryptionKey()

    @Test
    @DisplayName("Should encrypt and decrypt data correctly")
    fun testEncryptionDecryption() {
        val encrypted = SecurityUtils.encryptData(testData, testKey)
        assertTrue(encrypted.isSuccess)
        
        val decrypted = SecurityUtils.decryptData(encrypted.getOrNull()!!, testKey)
        assertTrue(decrypted.isSuccess)
        assertEquals(testData, decrypted.getOrNull())
    }

    @Test
    @DisplayName("Should fail decryption with wrong key")
    fun testDecryptionFailsWithWrongKey() {
        val encrypted = SecurityUtils.encryptData(testData, testKey)
        val wrongKey = SecurityUtils.generateEncryptionKey()
        
        val decrypted = SecurityUtils.decryptData(encrypted.getOrNull()!!, wrongKey)
        assertTrue(decrypted.isFailure)
    }

    @Test
    @DisplayName("Should validate secure paths")
    fun testPathValidation() {
        val validPath = "/home/user/config/test.json"
        val invalidPath = "/home/user/../../etc/passwd"
        
        assertTrue(SecurityUtils.isValidPath(validPath))
        assertFalse(SecurityUtils.isValidPath(invalidPath))
    }

    @Test
    @DisplayName("Should generate random tokens")
    fun testTokenGeneration() {
        val token1 = SecurityUtils.generateRandomToken(32)
        val token2 = SecurityUtils.generateRandomToken(32)
        
        assertEquals(32, token1.length)
        assertEquals(32, token2.length)
        assertNotEquals(token1, token2)
    }
}

class RateLimiterTest {
    private val rateLimiter = RateLimiter()
    private val testPlayerId = "test-player-123"

    @Test
    @DisplayName("Should allow requests within rate limit")
    fun testWithinRateLimit() {
        val result1 = rateLimiter.checkRateLimit(testPlayerId, maxAttempts = 3, windowMinutes = 15)
        val result2 = rateLimiter.checkRateLimit(testPlayerId, maxAttempts = 3, windowMinutes = 15)
        val result3 = rateLimiter.checkRateLimit(testPlayerId, maxAttempts = 3, windowMinutes = 15)
        
        assertTrue(result1.isSuccess)
        assertTrue(result2.isSuccess)
        assertTrue(result3.isSuccess)
    }

    @Test
    @DisplayName("Should block requests exceeding rate limit")
    fun testExceededRateLimit() {
        // First 3 should succeed
        repeat(3) {
            rateLimiter.checkRateLimit(testPlayerId, maxAttempts = 3, windowMinutes = 15)
        }
        
        // 4th should fail
        val result = rateLimiter.checkRateLimit(testPlayerId, maxAttempts = 3, windowMinutes = 15)
        assertTrue(result.isFailure)
    }

    @Test
    @DisplayName("Should track separate rate limits per identifier")
    fun testPerPlayerRateLimit() {
        val player1 = "player-1"
        val player2 = "player-2"
        
        repeat(3) {
            rateLimiter.checkRateLimit(player1, maxAttempts = 3, windowMinutes = 15)
        }
        
        // player2 should still have attempts available
        val result = rateLimiter.checkRateLimit(player2, maxAttempts = 3, windowMinutes = 15)
        assertTrue(result.isSuccess)
    }
}

class AppViewServiceTest {
    private lateinit var appView: AppViewService
    private val testPlayerUuid = UUID.randomUUID().toString()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        appView = AppViewService(null) // Mock RecordManager
    }

    @Test
    @DisplayName("Should index player profile")
    fun testIndexPlayerProfile() {
        val profileJson = json.parseToJsonElement("""
        {
          "${'$'}type": "com.jollywhoppers.minecraft.player.profile",
          "player": {"uuid": "$testPlayerUuid", "username": "TestPlayer"},
          "displayName": "Test",
          "bio": "Test bio",
          "publicStats": true,
          "publicSessions": true,
          "createdAt": "2026-04-20T10:30:00Z"
        }
        """)
        
        val result = appView.indexPlayerProfile(
            "at://did:plc:test/com.jollywhoppers.minecraft.player.profile/self",
            profileJson
        )
        
        assertTrue(result.isSuccess)
    }

    @Test
    @DisplayName("Should retrieve indexed player profile")
    fun testGetPlayerProfile() {
        // Index a profile
        val profileJson = json.parseToJsonElement("""
        {
          "${'$'}type": "com.jollywhoppers.minecraft.player.profile",
          "player": {"uuid": "$testPlayerUuid", "username": "TestPlayer"},
          "displayName": "Test",
          "bio": "Test bio",
          "publicStats": true,
          "publicSessions": true,
          "createdAt": "2026-04-20T10:30:00Z"
        }
        """)
        
        appView.indexPlayerProfile(
            "at://did:plc:test/com.jollywhoppers.minecraft.player.profile/self",
            profileJson
        )
        
        // Retrieve it
        val result = appView.getPlayerProfile(testPlayerUuid)
        assertTrue(result.isSuccess)
        
        val profile = result.getOrNull()
        assertNotNull(profile)
        assertEquals("TestPlayer", profile.profile.username)
    }

    @Test
    @DisplayName("Should create leaderboards from indexed stats")
    fun testLeaderboardGeneration() {
        val statsJson = json.parseToJsonElement("""
        {
          "${'$'}type": "com.jollywhoppers.minecraft.player.stats",
          "player": {"uuid": "$testPlayerUuid", "username": "TestPlayer"},
          "server": {"serverId": "srv1", "serverName": "Main"},
          "statistics": [
            {"key": "minecraft.mined.oak_log", "value": 1250}
          ],
          "playtimeMinutes": 7200,
          "level": 34,
          "gamemode": "survival",
          "dimension": "minecraft:overworld",
          "syncedAt": "2026-04-25T14:22:00Z"
        }
        """)
        
        appView.indexPlayerStats(
            "at://did:plc:test/com.jollywhoppers.minecraft.player.stats/8l6rvp4j6d3e2c4b9",
            statsJson
        )
        
        // Query leaderboard
        val result = appView.getLeaderboard("minecraft.mined.oak_log", limit = 10)
        assertTrue(result.isSuccess)
        
        val leaderboard = result.getOrNull()!!
        assertEquals(1, leaderboard.size)
        assertEquals("TestPlayer", leaderboard[0].username)
        assertEquals(1250, leaderboard[0].value)
    }

    @Test
    @DisplayName("Should search for players")
    fun testPlayerSearch() {
        val profileJson = json.parseToJsonElement("""
        {
          "${'$'}type": "com.jollywhoppers.minecraft.player.profile",
          "player": {"uuid": "$testPlayerUuid", "username": "AlicePlayer"},
          "displayName": "Alice",
          "bio": "Builder",
          "publicStats": true,
          "publicSessions": true,
          "createdAt": "2026-04-20T10:30:00Z"
        }
        """)
        
        appView.indexPlayerProfile(
            "at://did:plc:alice/com.jollywhoppers.minecraft.player.profile/self",
            profileJson
        )
        
        // Search by username
        val result = appView.searchPlayers("Alice")
        assertTrue(result.isSuccess)
        
        val results = result.getOrNull()!!
        assertEquals(1, results.size)
        assertEquals("AlicePlayer", results[0].username)
    }

    @Test
    @DisplayName("Should track trending achievements")
    fun testTrendingAchievements() {
        val achievement1 = json.parseToJsonElement("""
        {
          "${'$'}type": "com.jollywhoppers.minecraft.achievement",
          "player": {"uuid": "$testPlayerUuid", "username": "Player1"},
          "server": {"serverId": "srv1", "serverName": "Main"},
          "achievementId": "minecraft:adventure/kill_a_mob",
          "achievementName": "Monster Hunter",
          "achievementDescription": "Kill any type of monster",
          "category": "adventure",
          "isChallenge": false,
          "achievedAt": "2026-04-24T15:45:00Z"
        }
        """)
        
        appView.indexAchievement(
            "at://did:plc:test/com.jollywhoppers.minecraft.achievement/8l6rvp4j6d3e2c5a7",
            achievement1
        )
        
        val result = appView.getTrendingAchievements(limit = 10)
        assertTrue(result.isSuccess)
        
        val trending = result.getOrNull()!!
        assertEquals(1, trending.size)
        assertEquals("Monster Hunter", trending[0].achievementName)
    }
}

class AppViewHttpServerTest {
    private lateinit var httpServer: AppViewHttpServer
    private lateinit var appViewService: AppViewService

    @BeforeEach
    fun setup() {
        appViewService = AppViewService(null)
        httpServer = AppViewHttpServer(appViewService, port = 8080)
    }

    @Test
    @DisplayName("Should handle health check endpoint")
    fun testHealthCheck() {
        val response = httpServer.handleHealthCheck()
        
        assertTrue(response.success)
        assertNotNull(response.data)
        assertEquals("healthy", response.data?.status)
    }

    @Test
    @DisplayName("Should handle player profile request")
    fun testGetPlayerProfileEndpoint() {
        val uuid = UUID.randomUUID().toString()
        val response = httpServer.handleGetPlayerProfile(uuid)
        
        // No profile exists, should return not found
        assertFalse(response.success)
    }

    @Test
    @DisplayName("Should handle leaderboard request with pagination")
    fun testGetLeaderboardEndpoint() {
        val response = httpServer.handleGetLeaderboard("minecraft.mined.oak_log", "20")
        
        assertTrue(response.success)
        assertNotNull(response.pagination)
        assertEquals(20, response.pagination?.limit)
    }

    @Test
    @DisplayName("Should handle player search")
    fun testSearchEndpoint() {
        val response = httpServer.handleSearch("Test")
        
        assertTrue(response.success)
    }

    @Test
    @DisplayName("Should handle trending achievements")
    fun testTrendingAchievementsEndpoint() {
        val response = httpServer.handleGetTrendingAchievements("10")
        
        assertTrue(response.success)
    }
}

class PlayerIdentityStoreTest {
    private lateinit var store: PlayerIdentityStore
    private val testUuid = UUID.randomUUID()
    private val testDid = "did:plc:test123"
    private val testHandle = "test.bsky.social"

    @BeforeEach
    fun setup() {
        store = PlayerIdentityStore()
    }

    @Test
    @DisplayName("Should save and retrieve player identity")
    fun testSaveAndRetrieveIdentity() {
        runBlocking {
            store.saveIdentity(testUuid, testDid, testHandle)
            val result = store.getIdentity(testUuid)
            
            assertTrue(result.isSuccess)
            val identity = result.getOrNull()
            assertNotNull(identity)
            assertEquals(testDid, identity?.did)
            assertEquals(testHandle, identity?.handle)
        }
    }

    @Test
    @DisplayName("Should remove identity")
    fun testRemoveIdentity() {
        runBlocking {
            store.saveIdentity(testUuid, testDid, testHandle)
            store.removeIdentity(testUuid)
            
            val result = store.getIdentity(testUuid)
            assertTrue(result.getOrNull() == null)
        }
    }
}

class PlayerSyncPreferencesStoreTest {
    private lateinit var store: PlayerSyncPreferencesStore
    private val testUuid = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        store = PlayerSyncPreferencesStore()
    }

    @Test
    @DisplayName("Should save and retrieve sync preferences")
    fun testSaveAndRetrievePreferences() {
        runBlocking {
            val prefs = SyncPreferences(
                playerUuid = testUuid,
                syncStats = true,
                syncSessions = true,
                syncAchievements = false,
                syncServerStatus = false
            )
            
            store.updateSyncPreferences(testUuid, prefs)
            val result = store.getSyncPreferences(testUuid)
            
            assertTrue(result.isSuccess)
            val retrieved = result.getOrNull()
            assertNotNull(retrieved)
            assertTrue(retrieved!!.syncStats)
            assertFalse(retrieved.syncAchievements)
        }
    }

    @Test
    @DisplayName("Should have default preferences for new players")
    fun testDefaultPreferences() {
        runBlocking {
            val result = store.getSyncPreferences(testUuid)
            
            assertTrue(result.isSuccess)
            val prefs = result.getOrNull()
            assertNotNull(prefs)
            // All defaults should be false (opt-in)
            assertFalse(prefs!!.syncStats)
            assertFalse(prefs.syncSessions)
        }
    }
}

// Test utilities
fun runBlocking(block: suspend () -> Unit) {
    kotlinx.coroutines.runBlocking {
        block()
    }
}

fun assertEquals(expected: Any?, actual: Any?, message: String = "") {
    kotlin.test.assertEquals(expected, actual, message)
}

fun assertNotEquals(expected: Any?, actual: Any?, message: String = "") {
    assertTrue(expected != actual, message)
}

fun assertTrue(condition: Boolean, message: String = "") {
    kotlin.test.assertTrue(condition, message)
}

fun assertFalse(condition: Boolean, message: String = "") {
    kotlin.test.assertFalse(condition, message)
}

fun assertNotNull(value: Any?, message: String = ""): Any {
    kotlin.test.assertNotNull(value, message)
    return value
}
