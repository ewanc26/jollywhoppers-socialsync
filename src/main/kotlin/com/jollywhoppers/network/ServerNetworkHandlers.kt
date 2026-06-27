package com.jollywhoppers.network

import com.jollywhoppers.atproto.server.PlayerSyncPreferencesStore
import com.jollywhoppers.security.SecurityAuditor
import com.jollywhoppers.socialsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import org.slf4j.LoggerFactory

/**
 * Server-side packet handlers for AT Protocol network communication.
 * Processes client packets and sends responses.
 *
 * SECURITY MODEL:
 * - All handlers verify player authentication before accepting changes
 * - All sensitive operations are audit logged
 * - Rate limiting is applied where appropriate
 */
object ServerNetworkHandlers {
    private val logger = LoggerFactory.getLogger("atproto-connect-network")
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    /**
     * Register all server packet handlers.
     * Called during server initialization.
     */
    fun register() {
        // Register sync preferences packet type
        PayloadTypeRegistry.playC2S().register(
            AtProtoPackets.SyncPreferencesPacket.TYPE,
            AtProtoPackets.SyncPreferencesPacket.CODEC
        )

        registerSyncPreferencesHandler()
        logger.info("Registered AT Protocol packet handlers")
    }

    /**
     * Handle sync preferences update packet from client.
     * Validates and persists player sync preferences.
     */
    private fun registerSyncPreferencesHandler() {
        ServerPlayNetworking.registerGlobalReceiver(
            AtProtoPackets.SyncPreferencesPacket.TYPE
        ) { packet, context ->
            val player = context.player()
            val playerId = player.uuid

            // Process in coroutine to avoid blocking
            coroutineScope.launch {
                try {
                    // Verify player is authenticated before accepting preferences
                    if (!socialsync.sessionManager.hasSession(player.uuid)) {
                        logger.warn("Unauthenticated player ${player.name.string} attempted to set sync preferences")
                        SecurityAuditor.logSecurityEvent(
                            "sync_preference_unauthorized",
                            playerId,
                            "Unauthenticated player attempted to set sync preferences",
                        )
                        return@launch
                    }

                    // Clamp frequency values to valid range (1-240 minutes)
                    val statsFreq = packet.statsSyncFrequency.coerceIn(1, 240)
                    val sessionsFreq = packet.sessionSyncFrequency.coerceIn(1, 240)
                    val achievementsFreq = packet.achievementSyncFrequency.coerceIn(1, 240)

                    // Update sync preferences
                    PlayerSyncPreferencesStore.update(
                        playerId = playerId,
                        username = player.name.string,
                        stats = packet.syncStatsEnabled,
                        sessions = packet.syncSessionsEnabled,
                        achievements = packet.syncAchievementsEnabled,
                        serverStatus = packet.syncServerStatusEnabled,
                        statsFrequency = statsFreq,
                        sessionsFrequency = sessionsFreq,
                        achievementsFrequency = achievementsFreq,
                    )

                    // Audit log
                    SecurityAuditor.logSyncPreferenceChange(
                        playerId = playerId,
                        playerName = player.name.string,
                        stats = packet.syncStatsEnabled,
                        sessions = packet.syncSessionsEnabled,
                        achievements = packet.syncAchievementsEnabled,
                        serverStatus = packet.syncServerStatusEnabled,
                    )

                    logger.info("Updated sync preferences for player ${player.name.string} ($playerId)")
                } catch (e: Exception) {
                    logger.error("Failed to process sync preferences packet for ${player.name.string}: ${e.message}", e)
                    SecurityAuditor.logSecurityEvent(
                        "sync_preference_error",
                        playerId,
                        "Failed to process sync preferences: ${e.message}",
                    )
                }
            }
        }
    }
}
