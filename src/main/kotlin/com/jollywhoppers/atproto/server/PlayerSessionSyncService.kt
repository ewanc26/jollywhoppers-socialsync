package com.jollywhoppers.atproto.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks player play sessions and syncs them to AT Protocol records.
 *
 * When a player joins the server, we record the join time.
 * When they leave, we create a `com.jollywhoppers.minecraft.player.session` record
 * with the join time, leave time, duration, and quit reason.
 *
 * Sync consent:
 * - Checks `syncSessions` from PlayerIdentityStore before writing
 * - AT Protocol data is always public, so the real privacy control
 *   is not writing data the user doesn't want published
 *
 * Edge cases:
 * - Server stopping while players are online: flush all open sessions
 *   with quitReason = "server_stop"
 * - Player reconnecting quickly: each join/leave is a separate session
 */
class PlayerSessionSyncService(
    private val recordManager: RecordManager,
    private val sessionManager: AtProtoSessionManager,
    private val identityStore: PlayerIdentityStore,
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:sessions")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track active sessions: UUID -> join timestamp
    private val activeSessions = ConcurrentHashMap<UUID, ActiveSession>()

    companion object {
        private const val COLLECTION_ID = "com.jollywhoppers.minecraft.player.session"
    }

    data class ActiveSession(
        val joinedAt: Instant,
        val serverId: String,
        val serverName: String,
        val serverAddress: String? = null,
    )

    /**
     * Called when a player joins the server.
     * Records the join time for later session record creation.
     */
    fun onPlayerJoin(player: ServerPlayer, server: MinecraftServer) {
        val uuid = player.uuid

        // If there's an existing unclosed session (shouldn't happen normally),
        // close it first
        if (activeSessions.containsKey(uuid)) {
            logger.warn("Player ${player.name.string} joined with an existing unclosed session; closing it")
            onPlayerLeave(uuid, "reconnected", server)
        }

        val serverId = buildServerId(server)
        val serverName = server.getMotd().ifBlank { "Minecraft Server" }
        val serverAddress = server.getLocalIp().takeIf { it.isNotBlank() }?.let { ip ->
            val port = server.getPort()
            if (port > 0) "$ip:$port" else ip
        }

        activeSessions[uuid] = ActiveSession(
            joinedAt = Instant.now(),
            serverId = serverId,
            serverName = serverName,
            serverAddress = serverAddress,
        )

        logger.debug("Tracked session start for ${player.name.string} ($uuid)")
    }

    /**
     * Called when a player leaves the server.
     * Creates a session record if the player is linked and has consented.
     */
    fun onPlayerLeave(uuid: UUID, quitReason: String, server: MinecraftServer) {
        val session = activeSessions.remove(uuid) ?: run {
            logger.debug("No active session for $uuid on leave")
            return
        }

        // Check if linked and authenticated
        if (!identityStore.isLinked(uuid) || !sessionManager.hasSession(uuid)) {
            logger.debug("Skipping session record for $uuid: not linked or not authenticated")
            return
        }

        // Check sync consent
        val syncConsent = identityStore.getSyncConsent(uuid)
        if (syncConsent != null && !syncConsent.second) {
            logger.debug("Skipping session record for $uuid: syncSessions consent is disabled")
            return
        }

        val leftAt = Instant.now()
        val durationMinutes = Duration.between(session.joinedAt, leftAt).toMinutes().toInt().coerceAtLeast(0)

        // Get player name from the identity store (player may have already disconnected)
        val identity = identityStore.getIdentity(uuid)
        val playerName = identity?.handle ?: uuid.toString().take(8)

        coroutineScope.launch {
            try {
                val record = MinecraftPlayerSessionRecord(
                    player = PlayerReference(
                        uuid = uuid.toString(),
                        username = playerName,
                    ),
                    server = ServerReference(
                        serverId = session.serverId,
                        serverName = session.serverName,
                        serverAddress = session.serverAddress,
                    ),
                    joinedAt = session.joinedAt.toString(),
                    leftAt = leftAt.toString(),
                    durationMinutes = durationMinutes,
                    quitReason = normalizeQuitReason(quitReason),
                )

                recordManager.createTypedRecord(
                    playerUuid = uuid,
                    collection = COLLECTION_ID,
                    record = record,
                ).getOrThrow()

                logger.info("Synced session record for $playerName ($uuid): ${durationMinutes}min")
            } catch (e: Exception) {
                logger.error("Failed to sync session record for $uuid", e)
            }
        }
    }

    /**
     * Called when the server is stopping.
     * Flushes all open sessions with quitReason = "server_stop".
     */
    fun flushAllSessions(server: MinecraftServer) {
        val openSessions = activeSessions.keys.toList()
        if (openSessions.isEmpty()) return

        logger.info("Flushing ${openSessions.size} open sessions on server stop")
        openSessions.forEach { uuid ->
            onPlayerLeave(uuid, "server_stop", server)
        }
    }

    /**
     * Clears tracking for a player (e.g., on unlink).
     */
    fun clearPlayerTracking(uuid: UUID) {
        activeSessions.remove(uuid)
    }

    private fun normalizeQuitReason(reason: String): String {
        return when {
            reason.equals("server_stop", ignoreCase = true) -> "server_stop"
            reason.equals("reconnected", ignoreCase = true) -> "reconnected"
            reason.contains("kicked", ignoreCase = true) -> "kicked"
            reason.contains("timeout", ignoreCase = true) -> "timeout"
            else -> "disconnected"
        }.take(256) // Lexicon maxLength
    }

    private fun buildServerId(server: MinecraftServer): String {
        val serverPath = server.serverDirectory
            .toAbsolutePath()
            .normalize()
            .toString()
        val payload = "socialsync:$serverPath"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun shutdown() {
        coroutineScope.cancel()
    }

    @Serializable
    data class PlayerReference(
        val uuid: String,
        val username: String,
    )

    @Serializable
    data class ServerReference(
        val serverId: String,
        val serverName: String,
        val serverAddress: String? = null,
    )

    @Serializable
    data class MinecraftPlayerSessionRecord(
        @SerialName("\$type") val type: String = COLLECTION_ID,
        val player: PlayerReference,
        val server: ServerReference,
        val joinedAt: String,
        val leftAt: String? = null,
        val durationMinutes: Int? = null,
        val quitReason: String? = null,
    )
}
