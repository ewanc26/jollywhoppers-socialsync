package com.jollywhoppers.atproto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import com.jollywhoppers.network.AtProtoPackets
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtProtoPacketsSerializationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `AuthenticatePacket serialization round-trip`() {
        val packet = AtProtoPackets.AuthenticatePacket(
            did = "did:plc:test123",
            handle = "alice.bsky.social",
            pdsUrl = "https://bsky.social",
            accessJwt = "access-token",
            refreshJwt = "refresh-token",
            authType = "oauth",
        )

        val element = json.encodeToJsonElement(packet)
        val decoded = json.decodeFromJsonElement<AtProtoPackets.AuthenticatePacket>(element)

        assertEquals(packet.did, decoded.did)
        assertEquals(packet.handle, decoded.handle)
        assertEquals(packet.pdsUrl, decoded.pdsUrl)
        assertEquals(packet.accessJwt, decoded.accessJwt)
        assertEquals(packet.refreshJwt, decoded.refreshJwt)
        assertEquals(packet.authType, decoded.authType)
    }

    @Test
    fun `AuthenticatePacket defaults authType to app_password`() {
        val packet = AtProtoPackets.AuthenticatePacket(
            did = "did:plc:test",
            handle = "test.bsky.social",
            pdsUrl = "https://bsky.social",
            accessJwt = "jwt",
            refreshJwt = "jwt",
        )

        assertEquals("app_password", packet.authType)
    }

    @Test
    fun `AuthenticateResponsePacket serialization round-trip`() {
        val packet = AtProtoPackets.AuthenticateResponsePacket(success = true, message = "OK")

        val element = json.encodeToJsonElement(packet)
        val decoded = json.decodeFromJsonElement<AtProtoPackets.AuthenticateResponsePacket>(element)

        assertTrue(decoded.success)
        assertEquals("OK", decoded.message)
    }

    @Test
    fun `AuthenticateResponsePacket failure message`() {
        val packet = AtProtoPackets.AuthenticateResponsePacket(success = false, message = "Invalid token")

        val element = json.encodeToJsonElement(packet)
        val decoded = json.decodeFromJsonElement<AtProtoPackets.AuthenticateResponsePacket>(element)

        assertEquals(false, decoded.success)
        assertEquals("Invalid token", decoded.message)
    }

    @Test
    fun `LogoutPacket serialization round-trip`() {
        val packet = AtProtoPackets.LogoutPacket()

        val element = json.encodeToJsonElement(packet)
        val decoded = json.decodeFromJsonElement<AtProtoPackets.LogoutPacket>(element)

        assertTrue(decoded.placeholder)
    }

    @Test
    fun `SyncPreferencesPacket serialization round-trip`() {
        val packet = AtProtoPackets.SyncPreferencesPacket(
            syncStatsEnabled = true,
            syncSessionsEnabled = false,
            syncAchievementsEnabled = true,
            syncServerStatusEnabled = false,
            statsSyncFrequency = 30,
            sessionSyncFrequency = 5,
            achievementSyncFrequency = 60,
        )

        val element = json.encodeToJsonElement(packet)
        val decoded = json.decodeFromJsonElement<AtProtoPackets.SyncPreferencesPacket>(element)

        assertEquals(packet.syncStatsEnabled, decoded.syncStatsEnabled)
        assertEquals(packet.syncSessionsEnabled, decoded.syncSessionsEnabled)
        assertEquals(packet.syncAchievementsEnabled, decoded.syncAchievementsEnabled)
        assertEquals(packet.syncServerStatusEnabled, decoded.syncServerStatusEnabled)
        assertEquals(packet.statsSyncFrequency, decoded.statsSyncFrequency)
        assertEquals(packet.sessionSyncFrequency, decoded.sessionSyncFrequency)
        assertEquals(packet.achievementSyncFrequency, decoded.achievementSyncFrequency)
    }

    @Test
    fun `SyncPreferencesPacket all false`() {
        val packet = AtProtoPackets.SyncPreferencesPacket(
            syncStatsEnabled = false,
            syncSessionsEnabled = false,
            syncAchievementsEnabled = false,
            syncServerStatusEnabled = false,
            statsSyncFrequency = 60,
            sessionSyncFrequency = 60,
            achievementSyncFrequency = 60,
        )

        val element = json.encodeToJsonElement(packet)
        val decoded = json.decodeFromJsonElement<AtProtoPackets.SyncPreferencesPacket>(element)

        assertEquals(false, decoded.syncStatsEnabled)
        assertEquals(60, decoded.statsSyncFrequency)
    }
}
