package com.jollywhoppers.atproto

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * Tests for the ModMenu-as-sole-config-interface refactor.
 *
 * Verifies that:
 * - ClientPreferences defaults, copy, and serialisation round-trip correctly
 * - fabric.mod.json has correct metadata (license, ModMenu recommends, no fake links)
 * - Server-side AtProtoCommands no longer register logout or sync toggle subcommands
 * - Client-side ClientAtProtoCommands only exposes help (no login/logout/sync/status)
 * - AtProtoConfigScreen has isPauseScreen returning false
 */
class ModMenuConfigTest {

    // ── ClientPreferences data class ─────────────────────────────

    @Test
    @DisplayName("ClientPreferences defaults should match expected values")
    fun testClientPreferencesDefaults() {
        val prefs = com.jollywhoppers.config.ClientPreferences()
        assertTrue(prefs.syncStatsEnabled)
        assertTrue(prefs.syncSessionsEnabled)
        assertTrue(prefs.syncAchievementsEnabled)
        assertFalse(prefs.syncServerStatusEnabled)
        assertEquals(60, prefs.statsSyncFrequency)
        assertEquals(5, prefs.sessionSyncFrequency)
        assertEquals(30, prefs.achievementSyncFrequency)
        assertTrue(prefs.showSyncNotifications)
        assertTrue(prefs.showStatusInF3)
        assertFalse(prefs.compactModMenuLayout)
        assertTrue(prefs.encryptedLocalStorage)
        assertTrue(prefs.clearLocalCacheOnLogout)
    }

    @Test
    @DisplayName("ClientPreferences copy should only change the specified field")
    fun testClientPreferencesCopy() {
        val prefs = com.jollywhoppers.config.ClientPreferences()
        val updated = prefs.copy(syncStatsEnabled = false, compactModMenuLayout = true)
        assertFalse(updated.syncStatsEnabled)
        assertTrue(updated.syncSessionsEnabled) // unchanged
        assertTrue(updated.compactModMenuLayout)
        assertEquals(prefs.statsSyncFrequency, updated.statsSyncFrequency) // unchanged
    }

    @Test
    @DisplayName("ClientPreferences should survive a JSON serialisation round-trip")
    fun testClientPreferencesSerialisation() {
        val json = Json { prettyPrint = true }
        val prefs = com.jollywhoppers.config.ClientPreferences(
            syncStatsEnabled = false,
            syncSessionsEnabled = true,
            syncAchievementsEnabled = false,
            syncServerStatusEnabled = true,
            statsSyncFrequency = 15,
            sessionSyncFrequency = 10,
            achievementSyncFrequency = 120,
            showSyncNotifications = false,
            showStatusInF3 = false,
            compactModMenuLayout = true,
            encryptedLocalStorage = false,
            clearLocalCacheOnLogout = false,
        )

        val encoded = json.encodeToString(com.jollywhoppers.config.ClientPreferences.serializer(), prefs)
        val decoded = json.decodeFromString(com.jollywhoppers.config.ClientPreferences.serializer(), encoded)

        assertEquals(prefs, decoded)
    }

    @Test
    @DisplayName("ClientPreferences should tolerate unknown keys in JSON (forward compatibility)")
    fun testClientPreferencesUnknownKeys() {
        val json = Json { ignoreUnknownKeys = true }
        val jsonStr = """{"syncStatsEnabled":false,"futureSetting":42}"""
        val decoded = json.decodeFromString(com.jollywhoppers.config.ClientPreferences.serializer(), jsonStr)

        assertFalse(decoded.syncStatsEnabled)
        // All other fields should be defaults
        assertTrue(decoded.syncSessionsEnabled)
    }

    // ── fabric.mod.json metadata ─────────────────────────────────

    @Test
    @DisplayName("fabric.mod.json should use AGPL-3.0-only license")
    fun testFabricModJsonLicense() {
        val content = readFabricModJson()
        assertTrue(content.contains("\"AGPL-3.0-only\""),
            "License should be AGPL-3.0-only, was: ${extractLicense(content)}")
    }

    @Test
    @DisplayName("fabric.mod.json should recommend modmenu >=16.0.0")
    fun testFabricModJsonRecommendsModMenu() {
        val content = readFabricModJson()
        assertTrue(content.contains("\"recommends\""),
            "fabric.mod.json should have a recommends block")
        assertTrue(content.contains("\"modmenu\""),
            "fabric.mod.json should recommend modmenu")
    }

    @Test
    @DisplayName("fabric.mod.json should not contain the fake Discord link")
    fun testFabricModJsonNoFakeDiscord() {
        val content = readFabricModJson()
        assertFalse(content.contains("discord.gg/socialsync"),
            "The fake Discord link should have been removed")
    }

    @Test
    @DisplayName("fabric.mod.json should have the modmenu entrypoint registered")
    fun testFabricModJsonHasModMenuEntrypoint() {
        val content = readFabricModJson()
        assertTrue(content.contains("\"modmenu\""),
            "fabric.mod.json should have a modmenu entrypoint")
        assertTrue(content.contains("ModMenuIntegration"),
            "fabric.mod.json should reference ModMenuIntegration class")
    }

    // ── Server-side command registration ────────────────────────

    @Test
    @DisplayName("AtProtoCommands should not register a logout command")
    fun testNoLogoutCommand() {
        val source = readSourceFile("AtProtoCommands.kt")
        // The command registration should not contain a logout literal
        // But the word "logout" may appear in comments or help text —
        // check specifically for Commands.literal("logout")
        assertFalse(source.contains("Commands.literal(\"logout\")"),
            "Server-side /atproto logout should be removed — config is in ModMenu")
    }

    @Test
    @DisplayName("AtProtoCommands should not register sync toggle subcommands")
    fun testNoSyncToggleCommands() {
        val source = readSourceFile("AtProtoCommands.kt")
        // The sync subcommands (stats on|off, sessions on|off, etc.) should be gone
        assertFalse(source.contains("setSyncConsent"),
            "setSyncConsent method should be removed — sync consent is managed via ModMenu")
    }

    @Test
    @DisplayName("AtProtoCommands should still have sync read-only command")
    fun testSyncReadOnlyCommandExists() {
        val source = readSourceFile("AtProtoCommands.kt")
        assertTrue(source.contains("syncConsentStatus"),
            "Read-only /atproto sync command should still exist for viewing consent")
    }

    @Test
    @DisplayName("AtProtoCommands should still have link, unlink, whoami, whois, status, profile, export, admin")
    fun testInfoActionCommandsExist() {
        val source = readSourceFile("AtProtoCommands.kt")
        assertTrue(source.contains("Commands.literal(\"link\")"), "link command should exist")
        assertTrue(source.contains("Commands.literal(\"unlink\")"), "unlink command should exist")
        assertTrue(source.contains("Commands.literal(\"whoami\")"), "whoami command should exist")
        assertTrue(source.contains("Commands.literal(\"whois\")"), "whois command should exist")
        assertTrue(source.contains("Commands.literal(\"status\")"), "status command should exist")
        assertTrue(source.contains("Commands.literal(\"profile\")"), "profile command should exist")
        assertTrue(source.contains("Commands.literal(\"export\")"), "export command should exist")
        assertTrue(source.contains("Commands.literal(\"admin\")"), "admin command should exist")
    }

    @Test
    @DisplayName("AtProtoCommands help should mention ModMenu config screen")
    fun testHelpMentionsModMenu() {
        val source = readSourceFile("AtProtoCommands.kt")
        assertTrue(source.contains("ModMenu"),
            "Help text should direct users to ModMenu for configuration")
    }

    // ── Client-side command registration ────────────────────────

    @Test
    @DisplayName("ClientAtProtoCommands should not have login, logout, sync, or status commands")
    fun testNoClientConfigCommands() {
        val source = readSourceFile("ClientAtProtoCommands.kt")
        assertFalse(source.contains("ClientCommandManager.literal(\"login\")"),
            "Client /atproto login should be removed — use ModMenu")
        assertFalse(source.contains("ClientCommandManager.literal(\"logout\")"),
            "Client /atproto logout should be removed — use ModMenu")
        assertFalse(source.contains("ClientCommandManager.literal(\"sync\")"),
            "Client /atproto sync should be removed — use ModMenu")
        assertFalse(source.contains("ClientCommandManager.literal(\"status\")"),
            "Client /atproto status should be removed — use ModMenu")
        assertFalse(source.contains("ClientCommandManager.literal(\"oauth\")"),
            "Client /atproto oauth should be removed — use ModMenu")
    }

    @Test
    @DisplayName("ClientAtProtoCommands should only have help command")
    fun testClientOnlyHasHelp() {
        val source = readSourceFile("ClientAtProtoCommands.kt")
        assertTrue(source.contains("ClientCommandManager.literal(\"help\")"),
            "Client /atproto help should still exist")
    }

    // ── Config screen ────────────────────────────────────────────

    @Test
    @DisplayName("AtProtoConfigScreen should return false from isPauseScreen")
    fun testConfigScreenNotPause() {
        val source = readSourceFile("AtProtoConfigScreen.kt")
        assertTrue(source.contains("override fun isPauseScreen(): Boolean = false"),
            "Config screen should not pause the game in singleplayer")
    }

    @Test
    @DisplayName("AtProtoConfigScreen should have scrolling support")
    fun testConfigScreenHasScrolling() {
        val source = readSourceFile("AtProtoConfigScreen.kt")
        assertTrue(source.contains("mouseScrolled"),
            "Config screen should support mouse scrolling for small windows")
        assertTrue(source.contains("scrollOffset"),
            "Config screen should track scroll offset")
    }

    @Test
    @DisplayName("AtProtoConfigScreen should track section header positions from init")
    fun testConfigScreenTracksHeaderPositions() {
        val source = readSourceFile("AtProtoConfigScreen.kt")
        assertTrue(source.contains("authHeaderY"),
            "Config screen should track auth header Y position")
        assertTrue(source.contains("syncConsentHeaderY"),
            "Config screen should track sync consent header Y position")
        assertTrue(source.contains("syncFreqHeaderY"),
            "Config screen should track sync frequency header Y position")
        assertTrue(source.contains("uiHeaderY"),
            "Config screen should track UI header Y position")
        assertTrue(source.contains("privacyHeaderY"),
            "Config screen should track privacy header Y position")
    }

    // ── build.gradle ModMenu version ────────────────────────────

    @Test
    @DisplayName("build.gradle should use stable ModMenu 16.0.1 (not 11.0.3)")
    fun testBuildGradleModMenuVersion() {
        val source = readBuildGradle()
        assertFalse(source.contains("modmenu:11.0.3"),
            "ModMenu 11.0.3 is for MC 1.21–1.21.1, not 1.21.10")
        assertTrue(source.contains("modmenu:16.0.1"),
            "ModMenu should be stable 16.0.1 for MC 1.21.10")
    }

    @Test
    @DisplayName("build.gradle should use the documented pre-26.x modImplementation setup")
    fun testBuildGradleModMenuDevelopmentDependency() {
        val source = readBuildGradle()
        assertTrue(source.contains("modImplementation \"maven.modrinth:modmenu:16.0.1\""),
            "ModMenu should use modImplementation for a pre-26.x development environment")
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun readFabricModJson(): String {
        val path = Path.of("src/main/resources/fabric.mod.json")
        return Files.readString(path)
    }

    private fun readSourceFile(fileName: String): String {
        // Search both main and client source sets
        val mainPath = Path.of("src/main/kotlin/com/jollywhoppers/atproto/server/$fileName")
        if (Files.exists(mainPath)) return Files.readString(mainPath)

        val clientPath = Path.of("src/client/kotlin/com/jollywhoppers/atproto/client/$fileName")
        if (Files.exists(clientPath)) return Files.readString(clientPath)

        val clientScreenPath = Path.of("src/client/kotlin/com/jollywhoppers/screen/$fileName")
        if (Files.exists(clientScreenPath)) return Files.readString(clientScreenPath)

        fail("Could not find source file: $fileName")
    }

    private fun readBuildGradle(): String {
        return Files.readString(Path.of("build.gradle"))
    }

    private fun extractLicense(json: String): String {
        val regex = Regex("\"license\"\\s*:\\s*\"([^\"]+)\"")
        return regex.find(json)?.groupValues?.get(1) ?: "not found"
    }
}
