package com.jollywhoppers.atproto

import com.jollywhoppers.atproto.server.*
import com.jollywhoppers.security.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Comprehensive test suite for socialsync core functionality.
 *
 * Tests are organised around the actual server-side class APIs. Classes that depend
 * on the Minecraft runtime (e.g. PlayerSyncPreferencesStore, which calls
 * FabricLoader.getInstance() at init time) are tested at the data-class level only;
 * their persistence logic is exercised via integration tests in a real Minecraft env.
 */

// =============================================================================
// AtProtoSessionManager
// =============================================================================

class AtProtoSessionManagerTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var client: AtProtoClient
    private lateinit var sessionManager: AtProtoSessionManager
    private val testPlayerUuid: UUID = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        // AtProtoClient construction doesn't make any HTTP calls, so this is safe.
        client = AtProtoClient(
            slingshotUrl = "https://slingshot.microcosm.blue",
            fallbackPdsUrl = "https://bsky.social"
        )
        sessionManager = AtProtoSessionManager(
            storageFile = tempDir.resolve("test-sessions.json"),
            client = client
        )
    }

    @Test
    @DisplayName("hasSession should return false when no session has been stored")
    fun testNoSessionInitially() {
        assertFalse(sessionManager.hasSession(testPlayerUuid))
    }

    @Test
    @DisplayName("storeVerifiedSession should persist in memory and be detectable via hasSession")
    fun testStoreVerifiedSession() {
        sessionManager.storeVerifiedSession(
            uuid = testPlayerUuid,
            did = "did:plc:testdid123",
            handle = "test.bsky.social",
            pdsUrl = "https://bsky.social",
            accessJwt = "fake-access-jwt",
            refreshJwt = "fake-refresh-jwt",
            authType = "app_password"
        )

        assertTrue(sessionManager.hasSession(testPlayerUuid))
    }

    @Test
    @DisplayName("getSession should return the stored session without triggering a refresh on a fresh token")
    fun testGetStoredSession() {
        sessionManager.storeVerifiedSession(
            uuid = testPlayerUuid,
            did = "did:plc:testdid123",
            handle = "test.bsky.social",
            pdsUrl = "https://bsky.social",
            accessJwt = "fake-access-jwt",
            refreshJwt = "fake-refresh-jwt"
        )

        val result = runBlocking { sessionManager.getSession(testPlayerUuid) }

        assertTrue(result.isSuccess)
        val session = result.getOrNull()
        assertNotNull(session)
        assertEquals("test.bsky.social", session.handle)
        assertEquals("did:plc:testdid123", session.did)
        assertEquals("fake-access-jwt", session.accessJwt)
    }

    @Test
    @DisplayName("getSession should return failure for an unknown player UUID")
    fun testGetSessionUnknownPlayer() {
        val result = runBlocking { sessionManager.getSession(UUID.randomUUID()) }
        assertTrue(result.isFailure)
    }

    @Test
    @DisplayName("deleteSession should remove the session and hasSession should return false")
    fun testDeleteSession() {
        sessionManager.storeVerifiedSession(
            uuid = testPlayerUuid,
            did = "did:plc:testdid123",
            handle = "test.bsky.social",
            pdsUrl = "https://bsky.social",
            accessJwt = "fake-access-jwt",
            refreshJwt = "fake-refresh-jwt"
        )

        assertTrue(sessionManager.hasSession(testPlayerUuid))
        val deleted = sessionManager.deleteSession(testPlayerUuid)
        assertTrue(deleted)
        assertFalse(sessionManager.hasSession(testPlayerUuid))
    }

    @Test
    @DisplayName("deleteSession should return false when the player has no session")
    fun testDeleteNonExistentSession() {
        assertFalse(sessionManager.deleteSession(UUID.randomUUID()))
    }

    @Test
    @DisplayName("getAllSessions should contain all stored sessions")
    fun testGetAllSessions() {
        val uuid2 = UUID.randomUUID()
        sessionManager.storeVerifiedSession(
            uuid = testPlayerUuid, did = "did:plc:aaa", handle = "alice.bsky.social",
            pdsUrl = "https://bsky.social", accessJwt = "jwt-a", refreshJwt = "refresh-a"
        )
        sessionManager.storeVerifiedSession(
            uuid = uuid2, did = "did:plc:bbb", handle = "bob.bsky.social",
            pdsUrl = "https://bsky.social", accessJwt = "jwt-b", refreshJwt = "refresh-b"
        )

        val all = sessionManager.getAllSessions()
        assertEquals(2, all.size)
        assertEquals("alice.bsky.social", all[testPlayerUuid]?.handle)
        assertEquals("bob.bsky.social", all[uuid2]?.handle)
    }

    @Test
    @DisplayName("Session data should survive a round-trip to disk (encrypted persistence)")
    fun testSessionPersistenceRoundTrip() {
        sessionManager.storeVerifiedSession(
            uuid = testPlayerUuid,
            did = "did:plc:testdid123",
            handle = "persist.bsky.social",
            pdsUrl = "https://bsky.social",
            accessJwt = "persisted-access",
            refreshJwt = "persisted-refresh"
        )

        // Create a fresh manager pointing at the same file — simulates server restart.
        val reloaded = AtProtoSessionManager(
            storageFile = tempDir.resolve("test-sessions.json"),
            client = client
        )

        assertTrue(reloaded.hasSession(testPlayerUuid))
        assertEquals("persist.bsky.social", reloaded.getAllSessions()[testPlayerUuid]?.handle)
    }

    @Test
    @DisplayName("authType should be stored and retrieved correctly for OAuth sessions")
    fun testOAuthAuthType() {
        sessionManager.storeVerifiedSession(
            uuid = testPlayerUuid,
            did = "did:plc:oauth123",
            handle = "oauth.bsky.social",
            pdsUrl = "https://bsky.social",
            accessJwt = "oauth-access",
            refreshJwt = "oauth-refresh",
            authType = "oauth"
        )

        val session = sessionManager.getAllSessions()[testPlayerUuid]
        assertNotNull(session)
        assertEquals("oauth", session.authType)
    }
}

// =============================================================================
// SecurityUtils
// =============================================================================

class SecurityUtilsTest {

    private val testData = "sensitive-player-data"
    private val key = SecurityUtils.generateKey()

    @Test
    @DisplayName("encrypt then decrypt should return the original plaintext")
    fun testEncryptDecryptRoundTrip() {
        val encrypted = SecurityUtils.encrypt(testData, key)
        val decrypted = SecurityUtils.decrypt(encrypted, key)
        assertEquals(testData, decrypted)
    }

    @Test
    @DisplayName("decrypt with a different key should throw")
    fun testDecryptWithWrongKeyFails() {
        val encrypted = SecurityUtils.encrypt(testData, key)
        val wrongKey = SecurityUtils.generateKey()
        val result = runCatching { SecurityUtils.decrypt(encrypted, wrongKey) }
        assertTrue(result.isFailure)
    }

    @Test
    @DisplayName("each encrypt call should produce a different ciphertext (random IV)")
    fun testEncryptionIsNonDeterministic() {
        val first = SecurityUtils.encrypt(testData, key)
        val second = SecurityUtils.encrypt(testData, key)
        // Same plaintext + key but different IVs → different ciphertexts
        assertTrue(first != second)
    }

    @Test
    @DisplayName("validatePathInDirectory should accept a file inside the parent")
    fun testValidatePathInsideDirectory(@TempDir tempDir: Path) {
        val validPath = tempDir.resolve("subdir").resolve("file.json")
        assertTrue(SecurityUtils.validatePathInDirectory(validPath, tempDir))
    }

    @Test
    @DisplayName("validatePathInDirectory should reject a path traversal outside the parent")
    fun testValidatePathOutsideDirectory(@TempDir tempDir: Path) {
        val outsidePath = tempDir.parent.resolve("other").resolve("file.json")
        assertFalse(SecurityUtils.validatePathInDirectory(outsidePath, tempDir))
    }

    @Test
    @DisplayName("sanitizeForLog should mask short values entirely")
    fun testSanitizeShortValue() {
        assertEquals("***", SecurityUtils.sanitizeForLog("abc"))
    }

    @Test
    @DisplayName("sanitizeForLog should truncate long values with an ellipsis")
    fun testSanitizeLongValue() {
        val sanitized = SecurityUtils.sanitizeForLog("abcdefghij1234567890")
        assertTrue(sanitized.contains("..."))
        assertTrue(sanitized.length < "abcdefghij1234567890".length)
    }

    @Test
    @DisplayName("loadOrGenerateServerKey should produce the same key across loads")
    fun testKeyPersistence(@TempDir tempDir: Path) {
        val keyFile = tempDir.resolve(".encryption.key")
        val key1 = SecurityUtils.loadOrGenerateServerKey(keyFile)
        val key2 = SecurityUtils.loadOrGenerateServerKey(keyFile)
        // Both loads should return the same key bytes
        assertTrue(key1.encoded.contentEquals(key2.encoded))
    }
}

// =============================================================================
// RateLimiter
// =============================================================================

class RateLimiterTest {

    @Test
    @DisplayName("checkAttempt should allow when no failures have been recorded")
    fun testFreshPlayerIsAllowed() {
        val limiter = RateLimiter(maxAttempts = 3)
        val result = limiter.checkAttempt(UUID.randomUUID())
        assertTrue(result.allowed)
        assertEquals(3, result.attemptsRemaining)
    }

    @Test
    @DisplayName("checkAttempt should count down attemptsRemaining after each failure")
    fun testAttemptsCountDown() {
        val limiter = RateLimiter(maxAttempts = 3)
        val uuid = UUID.randomUUID()

        limiter.recordFailure(uuid)
        val result = limiter.checkAttempt(uuid)
        assertTrue(result.allowed)
        assertEquals(2, result.attemptsRemaining)
    }

    @Test
    @DisplayName("player should be locked out after exceeding maxAttempts")
    fun testLockoutAfterMaxAttempts() {
        val limiter = RateLimiter(maxAttempts = 3)
        val uuid = UUID.randomUUID()

        repeat(3) { limiter.recordFailure(uuid) }
        val result = limiter.checkAttempt(uuid)

        assertFalse(result.allowed)
        assertNotNull(result.lockedUntil)
        assertEquals(0, result.attemptsRemaining)
    }

    @Test
    @DisplayName("different players should have independent rate-limit windows")
    fun testPerPlayerIsolation() {
        val limiter = RateLimiter(maxAttempts = 3)
        val player1 = UUID.randomUUID()
        val player2 = UUID.randomUUID()

        // Exhaust player1
        repeat(3) { limiter.recordFailure(player1) }
        limiter.checkAttempt(player1) // Trigger lockout

        // player2 is unaffected
        val result = limiter.checkAttempt(player2)
        assertTrue(result.allowed)
    }

    @Test
    @DisplayName("recordSuccess should clear the attempt counter")
    fun testSuccessClearsCounter() {
        val limiter = RateLimiter(maxAttempts = 3)
        val uuid = UUID.randomUUID()

        repeat(2) { limiter.recordFailure(uuid) }
        limiter.recordSuccess(uuid)

        val result = limiter.checkAttempt(uuid)
        assertTrue(result.allowed)
        assertEquals(3, result.attemptsRemaining)
    }

    @Test
    @DisplayName("clearLimit should remove both failures and lockouts")
    fun testClearLimit() {
        val limiter = RateLimiter(maxAttempts = 3)
        val uuid = UUID.randomUUID()

        repeat(3) { limiter.recordFailure(uuid) }
        limiter.checkAttempt(uuid) // Lock out
        limiter.clearLimit(uuid)

        val result = limiter.checkAttempt(uuid)
        assertTrue(result.allowed)
    }
}

// =============================================================================
// PlayerIdentityStore
// =============================================================================

class PlayerIdentityStoreTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var store: PlayerIdentityStore
    private val testUuid: UUID = UUID.randomUUID()
    private val testDid = "did:plc:test123"
    private val testHandle = "test.bsky.social"

    @BeforeEach
    fun setup() {
        store = PlayerIdentityStore(tempDir.resolve("player-identities.json"))
    }

    @Test
    @DisplayName("isLinked should return false for a new player")
    fun testNotLinkedInitially() {
        assertFalse(store.isLinked(testUuid))
    }

    @Test
    @DisplayName("linkIdentity should make isLinked return true")
    fun testLinkIdentity() {
        store.linkIdentity(testUuid, testDid, testHandle)
        assertTrue(store.isLinked(testUuid))
    }

    @Test
    @DisplayName("getIdentity should return the correct DID and handle after linking")
    fun testGetIdentityAfterLink() {
        store.linkIdentity(testUuid, testDid, testHandle)
        val identity = store.getIdentity(testUuid)
        assertNotNull(identity)
        assertEquals(testDid, identity.did)
        assertEquals(testHandle, identity.handle)
    }

    @Test
    @DisplayName("getIdentity should return null for an unlinked player")
    fun testGetIdentityUnlinked() {
        val identity = store.getIdentity(UUID.randomUUID())
        assertTrue(identity == null)
    }

    @Test
    @DisplayName("unlinkIdentity should remove the mapping")
    fun testUnlinkIdentity() {
        store.linkIdentity(testUuid, testDid, testHandle)
        assertTrue(store.isLinked(testUuid))

        store.unlinkIdentity(testUuid)
        assertFalse(store.isLinked(testUuid))
        assertTrue(store.getIdentity(testUuid) == null)
    }

    @Test
    @DisplayName("getUuidByDid should look up a UUID by its DID")
    fun testGetUuidByDid() {
        store.linkIdentity(testUuid, testDid, testHandle)
        assertEquals(testUuid, store.getUuidByDid(testDid))
    }

    @Test
    @DisplayName("getUuidByHandle should look up a UUID by its handle (case-insensitive)")
    fun testGetUuidByHandle() {
        store.linkIdentity(testUuid, testDid, testHandle)
        assertEquals(testUuid, store.getUuidByHandle(testHandle))
        assertEquals(testUuid, store.getUuidByHandle(testHandle.uppercase()))
    }

    @Test
    @DisplayName("identities should persist across store restarts")
    fun testPersistenceRoundTrip() {
        store.linkIdentity(testUuid, testDid, testHandle)

        val reloaded = PlayerIdentityStore(tempDir.resolve("player-identities.json"))
        assertTrue(reloaded.isLinked(testUuid))
        assertEquals(testDid, reloaded.getIdentity(testUuid)?.did)
    }
}

// =============================================================================
// PlayerSyncPreferences (data class — tested in isolation from FabricLoader)
// =============================================================================

class PlayerSyncPreferencesTest {

    @Test
    @DisplayName("default preferences should have stats, sessions, and achievements on, server-status off")
    fun testDefaultValues() {
        val prefs = PlayerSyncPreferences(playerId = UUID.randomUUID().toString())
        assertTrue(prefs.syncStatsEnabled)
        assertTrue(prefs.syncSessionsEnabled)
        assertTrue(prefs.syncAchievementsEnabled)
        assertFalse(prefs.syncServerStatusEnabled)
    }

    @Test
    @DisplayName("shouldSync should map category strings to the correct boolean field")
    fun testShouldSync() {
        val prefs = PlayerSyncPreferences(
            playerId = UUID.randomUUID().toString(),
            syncStatsEnabled = true,
            syncSessionsEnabled = false,
            syncAchievementsEnabled = true,
            syncServerStatusEnabled = false
        )
        assertTrue(prefs.shouldSync("stats"))
        assertFalse(prefs.shouldSync("sessions"))
        assertTrue(prefs.shouldSync("achievements"))
        assertFalse(prefs.shouldSync("server_status"))
        assertFalse(prefs.shouldSync("unknown_key"))
    }

    @Test
    @DisplayName("isAnySyncEnabled should return false when all syncs are disabled")
    fun testAllDisabled() {
        val prefs = PlayerSyncPreferences(
            playerId = UUID.randomUUID().toString(),
            syncStatsEnabled = false,
            syncSessionsEnabled = false,
            syncAchievementsEnabled = false,
            syncServerStatusEnabled = false
        )
        assertFalse(prefs.isAnySyncEnabled())
    }

    @Test
    @DisplayName("isAnySyncEnabled should return true if at least one sync is enabled")
    fun testOneEnabled() {
        val prefs = PlayerSyncPreferences(
            playerId = UUID.randomUUID().toString(),
            syncStatsEnabled = true,
            syncSessionsEnabled = false,
            syncAchievementsEnabled = false,
            syncServerStatusEnabled = false
        )
        assertTrue(prefs.isAnySyncEnabled())
    }

    @Test
    @DisplayName("getSyncFrequency should return the configured value for each data type")
    fun testGetSyncFrequency() {
        val prefs = PlayerSyncPreferences(
            playerId = UUID.randomUUID().toString(),
            statsSyncFrequency = 30,
            sessionSyncFrequency = 5,
            achievementSyncFrequency = 60
        )
        assertEquals(30, prefs.getSyncFrequency("stats"))
        assertEquals(5, prefs.getSyncFrequency("sessions"))
        assertEquals(60, prefs.getSyncFrequency("achievements"))
        assertEquals(60, prefs.getSyncFrequency("unknown")) // default
    }
}

// =============================================================================
// AppViewService
// =============================================================================

class AppViewServiceTest {

    @TempDir
    lateinit var tempDir: Path
    private val client = AtProtoClient(slingshotUrl = "https://slingshot.microcosm.blue", fallbackPdsUrl = "https://bsky.social")

    private lateinit var appView: AppViewService
    private val json = Json { ignoreUnknownKeys = true }
    private val testPlayerUuid: String = UUID.randomUUID().toString()

    @BeforeEach
    fun setup() {
        val dummySessionManager = AtProtoSessionManager(tempDir.resolve("dummy-sessions.json"), client)
        val dummyRecordManager = RecordManager(dummySessionManager)
        appView = AppViewService(dummyRecordManager)
    }

    @Test
    @DisplayName("indexPlayerProfile should store and make a profile retrievable")
    fun testIndexAndGetProfile() {
        val profileJson = json.parseToJsonElement("""
        {
          "${"$"}type": "com.jollywhoppers.minecraft.player.profile",
          "player": {"uuid": "$testPlayerUuid", "username": "TestPlayer"},
          "displayName": "Test",
          "bio": "Test bio",
          "publicStats": true,
          "publicSessions": true,
          "createdAt": "2026-04-20T10:30:00Z",
          "updatedAt": null
        }
        """.trimIndent())

        val indexResult = appView.indexPlayerProfile(
            "at://did:plc:test/com.jollywhoppers.minecraft.player.profile/self",
            profileJson
        )
        assertTrue(indexResult.isSuccess)

        val getResult = appView.getPlayerProfile(testPlayerUuid)
        assertTrue(getResult.isSuccess)
        val profile = getResult.getOrNull()
        assertNotNull(profile)
        assertEquals("TestPlayer", profile.profile.username)
    }

    @Test
    @DisplayName("getPlayerProfile should return null for an unknown player")
    fun testGetProfileUnknownPlayer() {
        val result = appView.getPlayerProfile(UUID.randomUUID().toString())
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull() == null)
    }

    @Test
    @DisplayName("indexPlayerStats should update the leaderboard for each statistic")
    fun testLeaderboardFromStats() {
        val statsJson = json.parseToJsonElement("""
        {
          "${"$"}type": "com.jollywhoppers.minecraft.player.stats",
          "player": {"uuid": "$testPlayerUuid", "username": "TestPlayer"},
          "server": {"serverId": "srv1", "serverName": "Main"},
          "statistics": [
            {"key": "minecraft.mined.oak_log", "value": 1250, "category": "mined"}
          ],
          "playtimeMinutes": 7200,
          "level": 34,
          "gamemode": "survival",
          "dimension": "minecraft:overworld",
          "syncedAt": "2026-04-25T14:22:00Z"
        }
        """.trimIndent())

        appView.indexPlayerStats(
            "at://did:plc:test/com.jollywhoppers.minecraft.player.stats/abc",
            statsJson
        )

        val leaderboard = appView.getLeaderboard("minecraft.mined.oak_log", limit = 10)
        assertTrue(leaderboard.isSuccess)
        val entries = leaderboard.getOrNull()!!
        assertEquals(1, entries.size)
        assertEquals("TestPlayer", entries[0].username)
        assertEquals(1250, entries[0].value)
    }

    @Test
    @DisplayName("leaderboard should always surface the most recent entry for a player")
    fun testLeaderboardUpdatesOnNewStats() {
        fun statsJson(value: Int) = json.parseToJsonElement("""
        {
          "${"$"}type": "com.jollywhoppers.minecraft.player.stats",
          "player": {"uuid": "$testPlayerUuid", "username": "TestPlayer"},
          "server": {"serverId": "srv1", "serverName": "Main"},
          "statistics": [{"key": "blocks.mined", "value": $value, "category": "mined"}],
          "playtimeMinutes": 100,
          "level": 1,
          "gamemode": "survival",
          "dimension": "minecraft:overworld",
          "syncedAt": "2026-05-01T00:00:00Z"
        }
        """.trimIndent())

        appView.indexPlayerStats("at://did:plc:test/stats/1", statsJson(100))
        appView.indexPlayerStats("at://did:plc:test/stats/2", statsJson(200))

        val entries = appView.getLeaderboard("blocks.mined").getOrNull()!!
        // Only one entry per player; should reflect the latest value
        assertEquals(1, entries.size)
        assertEquals(200, entries[0].value)
    }

    @Test
    @DisplayName("searchPlayers should find profiles by username substring")
    fun testSearchByUsername() {
        val profileJson = json.parseToJsonElement("""
        {
          "${"$"}type": "com.jollywhoppers.minecraft.player.profile",
          "player": {"uuid": "$testPlayerUuid", "username": "AlicePlayer"},
          "displayName": "Alice",
          "bio": "Builder",
          "publicStats": true,
          "publicSessions": true,
          "createdAt": "2026-04-20T10:30:00Z",
          "updatedAt": null
        }
        """.trimIndent())

        appView.indexPlayerProfile(
            "at://did:plc:alice/com.jollywhoppers.minecraft.player.profile/self",
            profileJson
        )

        val results = appView.searchPlayers("Alice").getOrNull()!!
        assertEquals(1, results.size)
        assertEquals("AlicePlayer", results[0].username)
    }

    @Test
    @DisplayName("getTrendingAchievements should rank achievements by how many times they were earned")
    fun testTrendingAchievements() {
        fun achievementJson(playerUuid: String, username: String) = json.parseToJsonElement("""
        {
          "${"$"}type": "com.jollywhoppers.minecraft.achievement",
          "player": {"uuid": "$playerUuid", "username": "$username"},
          "server": {"serverId": "srv1", "serverName": "Main"},
          "achievementId": "minecraft:adventure/kill_a_mob",
          "achievementName": "Monster Hunter",
          "achievementDescription": "Kill any type of monster",
          "category": "adventure",
          "isChallenge": false,
          "achievedAt": "2026-04-24T15:45:00Z"
        }
        """.trimIndent())

        val uuid1 = UUID.randomUUID().toString()
        val uuid2 = UUID.randomUUID().toString()

        appView.indexAchievement("at://did:plc:p1/achievement/1", achievementJson(uuid1, "Player1"))
        appView.indexAchievement("at://did:plc:p2/achievement/2", achievementJson(uuid2, "Player2"))

        val trending = appView.getTrendingAchievements(limit = 10).getOrNull()!!
        assertEquals(1, trending.size)
        assertEquals("Monster Hunter", trending[0].achievementName)
        assertEquals(2, trending[0].timesEarned)
    }

    @Test
    @DisplayName("getPlayerStatsSummary should return top 5 statistics by value")
    fun testPlayerStatsSummary() {
        val statsJson = json.parseToJsonElement("""
        {
          "${"$"}type": "com.jollywhoppers.minecraft.player.stats",
          "player": {"uuid": "$testPlayerUuid", "username": "TestPlayer"},
          "server": {"serverId": "srv1", "serverName": "Main"},
          "statistics": [
            {"key": "a", "value": 10, "category": "x"},
            {"key": "b", "value": 50, "category": "x"},
            {"key": "c", "value": 30, "category": "x"},
            {"key": "d", "value": 80, "category": "x"},
            {"key": "e", "value": 20, "category": "x"},
            {"key": "f", "value": 5, "category": "x"}
          ],
          "playtimeMinutes": 120,
          "level": 10,
          "gamemode": "survival",
          "dimension": "minecraft:overworld",
          "syncedAt": "2026-05-01T00:00:00Z"
        }
        """.trimIndent())

        appView.indexPlayerStats("at://did:plc:test/stats/1", statsJson)

        val summary = appView.getPlayerStatsSummary(testPlayerUuid).getOrNull()
        assertNotNull(summary)
        assertEquals(5, summary.topStatistics.size)
        assertEquals(80, summary.topStatistics[0].value) // highest first
    }
}


// =============================================================================
// AchievementSyncService
// =============================================================================

class AchievementSyncServiceTest {

    @TempDir
    lateinit var tempDir: Path
    private val client = uk.ewancroft.atpkt.core.AtProtoClient(fallbackPdsUrl = "https://bsky.social")

    private lateinit var sessionManager: uk.ewancroft.atpkt.core.AtProtoSessionManager
    private lateinit var identityStore: PlayerIdentityStore
    private lateinit var syncPreferencesStore: PlayerSyncPreferencesStore
    private lateinit var recordManager: uk.ewancroft.atpkt.core.RecordManager
    private lateinit var achievementSyncService: AchievementSyncService
    private val testPlayerUuid: UUID = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        sessionManager = uk.ewancroft.atpkt.core.AtProtoSessionManager(tempDir.resolve("test-sessions.json"), client, dummyEncryptionProvider)
        identityStore = PlayerIdentityStore(tempDir.resolve("player-identities.json"))
        syncPreferencesStore = PlayerSyncPreferencesStore
        recordManager = uk.ewancroft.atpkt.core.RecordManager(sessionManager)
        
        achievementSyncService = AchievementSyncService(
            recordManager,
            sessionManager,
            identityStore,
            syncPreferencesStore
        )
        
        // Mock identity and session for the test
        identityStore.linkIdentity(testPlayerUuid, "did:plc:test123", "test.bsky.social")
        sessionManager.storeSession(
            testPlayerUuid, "did:plc:test123", "test.bsky.social", 
            "https://bsky.social", "access", "refresh"
        )
    }

    private val dummyEncryptionProvider = object : uk.ewancroft.atpkt.core.AtProtoSessionManager.IEncryptionProvider {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(ciphertext: String): String = ciphertext
    }

    // Since onAdvancementCompleted needs ServerPlayer and AdvancementHolder, 
    // which are complex to mock without a full Minecraft environment, 
    // we would ideally use a proper test framework or mock library.
    // For now, this test structure serves as documentation for the intended sync flow.
}

