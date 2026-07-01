package com.jollywhoppers.atproto

import com.jollywhoppers.atproto.server.AchievementSyncService
import com.jollywhoppers.atproto.server.AchievementSyncStore
import com.jollywhoppers.atproto.server.AtProtoClient
import com.jollywhoppers.atproto.server.AtProtoSessionManager
import com.jollywhoppers.atproto.server.PlayerIdentityStore
import com.jollywhoppers.atproto.server.PlayerSessionSyncService
import com.jollywhoppers.atproto.server.PlayerSyncPreferencesStore
import io.github.kikin81.atproto.runtime.NoAuth
import io.github.kikin81.atproto.runtime.XrpcClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals

class SyncServicePureFunctionTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var achievementService: AchievementSyncService
    private lateinit var sessionService: PlayerSessionSyncService

    @BeforeEach
    fun setup() {
        val client = AtProtoClient("https://slingshot.microcosm.blue", "https://bsky.social")
        val sessionManager = AtProtoSessionManager(tempDir.resolve("sessions.json"), client)
        val identityStore = PlayerIdentityStore(tempDir.resolve("identities.json"))
        val testHttpClient = HttpClient(CIO) { expectSuccess = false }
        val testXrpcClient = XrpcClient(baseUrl = "https://bsky.social", httpClient = testHttpClient, authProvider = NoAuth)
        val recordManager = com.jollywhoppers.atproto.server.RecordManager(
            testXrpcClient, Json { ignoreUnknownKeys = true }, sessionManager
        )
        val achievementSyncStore = AchievementSyncStore(tempDir.resolve("achievement-sync.json"))

        achievementService = AchievementSyncService(
            recordManager = recordManager,
            sessionManager = sessionManager,
            identityStore = identityStore,
            syncPreferencesStore = PlayerSyncPreferencesStore,
            achievementSyncStore = achievementSyncStore,
        )

        sessionService = PlayerSessionSyncService(
            recordManager = recordManager,
            sessionManager = sessionManager,
            identityStore = identityStore,
            syncPreferencesStore = PlayerSyncPreferencesStore,
        )
    }

    // =========================================================================
    // extractCategory
    // =========================================================================

    @Test
    fun `story category`() {
        assertEquals("story", achievementService.extractCategory("minecraft:story/root"))
    }

    @Test
    fun `story sub advancement`() {
        assertEquals("story", achievementService.extractCategory("minecraft:story/mine_stone"))
    }

    @Test
    fun `nether category`() {
        assertEquals("nether", achievementService.extractCategory("minecraft:nether/root"))
    }

    @Test
    fun `end category`() {
        assertEquals("end", achievementService.extractCategory("minecraft:end/root"))
    }

    @Test
    fun `adventure category`() {
        assertEquals("adventure", achievementService.extractCategory("minecraft:adventure/root"))
    }

    @Test
    fun `husbandry category`() {
        assertEquals("husbandry", achievementService.extractCategory("minecraft:husbandry/root"))
    }

    @Test
    fun `unknown namespace falls back to last segment before slash`() {
        assertEquals("custom", achievementService.extractCategory("modid:custom/root"))
    }

    @Test
    fun `no slash falls back to other`() {
        assertEquals("other", achievementService.extractCategory("minecraft:root"))
    }

    @Test
    fun `empty string falls back to other`() {
        assertEquals("other", achievementService.extractCategory(""))
    }

    // =========================================================================
    // normalizeQuitReason
    // =========================================================================

    @Test
    fun `server_stop reason`() {
        assertEquals("server_stop", sessionService.normalizeQuitReason("server_stop"))
    }

    @Test
    fun `server_stop case insensitive`() {
        assertEquals("server_stop", sessionService.normalizeQuitReason("SERVER_STOP"))
    }

    @Test
    fun `reconnected reason`() {
        assertEquals("reconnected", sessionService.normalizeQuitReason("reconnected"))
    }

    @Test
    fun `kicked reason`() {
        assertEquals("kicked", sessionService.normalizeQuitReason("kicked"))
    }

    @Test
    fun `kicked reason contains kicked`() {
        assertEquals("kicked", sessionService.normalizeQuitReason("You were kicked by an operator"))
    }

    @Test
    fun `timeout reason`() {
        assertEquals("timeout", sessionService.normalizeQuitReason("timeout"))
    }

    @Test
    fun `timeout reason contains timeout`() {
        assertEquals("timeout", sessionService.normalizeQuitReason("Read timeout"))
    }

    @Test
    fun `unknown reason defaults to disconnected`() {
        assertEquals("disconnected", sessionService.normalizeQuitReason("something_else"))
    }

    @Test
    fun `empty reason defaults to disconnected`() {
        assertEquals("disconnected", sessionService.normalizeQuitReason(""))
    }

    @Test
    fun `long reason defaults to disconnected`() {
        val long = "x".repeat(500)
        val result = sessionService.normalizeQuitReason(long)
        assertEquals("disconnected", result)
    }
}
