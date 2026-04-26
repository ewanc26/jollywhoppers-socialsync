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
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Periodically syncs server status to AT Protocol.
 *
 * Creates a `com.jollywhoppers.minecraft.server.status` record with the
 * `literal:self` rkey, meaning one status record per server.
 *
 * Requirements:
 * - At least one authenticated player must be online (we need their session
 *   to write to AT Protocol)
 * - The first authenticated player's session is used for the write
 *
 * This is a limitation of the current design — a future "server account"
 * concept would allow the server operator to authenticate independently.
 */
class ServerStatusSyncService(
    private val recordManager: RecordManager,
    private val sessionManager: AtProtoSessionManager,
    private val identityStore: PlayerIdentityStore,
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:server-status")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val COLLECTION_ID = "com.jollywhoppers.minecraft.server.status"
        private const val RKEY = "self"
    }

    /**
     * Called periodically to sync server status.
     * Only syncs if there's at least one authenticated player online.
     */
    fun onSyncTick(server: MinecraftServer) {
        if (!server.isRunning || server.isStopped) return

        // Find an authenticated player to use their session
        val authenticatedPlayer = server.playerList.players.firstOrNull { player ->
            identityStore.isLinked(player.uuid) && sessionManager.hasSession(player.uuid)
        }

        if (authenticatedPlayer == null) {
            logger.debug("Skipping server status sync: no authenticated players online")
            return
        }

        coroutineScope.launch {
            try {
                val status = buildStatus(server)
                recordManager.putTypedRecord(
                    playerUuid = authenticatedPlayer.uuid,
                    collection = COLLECTION_ID,
                    rkey = RKEY,
                    record = status,
                ).getOrThrow()

                logger.info("Synced server status for ${status.server.serverName}")
            } catch (e: Exception) {
                logger.error("Failed to sync server status", e)
            }
        }
    }

    private fun buildStatus(server: MinecraftServer): MinecraftServerStatusRecord {
        val serverId = buildServerId(server)
        val serverName = server.getMotd().ifBlank { "Minecraft Server" }
        val serverAddress = server.getLocalIp().takeIf { it.isNotBlank() }?.let { ip ->
            val port = server.getPort()
            if (port > 0) "$ip:$port" else ip
        }

        val onlinePlayers = server.playerList.players.map { player ->
            PlayerReference(
                uuid = player.uuid.toString(),
                username = player.name.string,
            )
        }.take(100) // Limit per lexicon

        val overworld = server.getLevel(net.minecraft.world.level.Level.OVERWORLD)

        return MinecraftServerStatusRecord(
            server = ServerReference(
                serverId = serverId,
                serverName = serverName,
                serverAddress = serverAddress,
            ),
            version = server.serverVersion,
            protocolVersion = null, // Not directly accessible
            maxPlayers = server.maxPlayers,
            onlinePlayers = server.playerList.players.size,
            playerSample = onlinePlayers.takeIf { it.isNotEmpty() },
            motd = server.getMotd().takeIf { it.isNotBlank() },
            gameMode = inferPrimaryGameMode(server),
            difficulty = overworld?.difficulty?.name?.lowercase() ?: "normal",
            hardcore = server.isHardcore,
            pvpEnabled = server.isPvpAllowed,
            updatedAt = Instant.now().toString(),
        )
    }

    private fun inferPrimaryGameMode(server: MinecraftServer): String {
        // Default to survival; could be enhanced to check default game mode
        return server.getDefaultGameType().name.lowercase()
    }

    private fun buildServerId(server: MinecraftServer): String {
        val serverPath = server.serverDirectory
            .toAbsolutePath()
            .normalize()
            .toString()
        val payload = "socialsync:$serverPath"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
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
    data class MinecraftServerStatusRecord(
        @SerialName("\$type") val type: String = COLLECTION_ID,
        val server: ServerReference,
        val version: String,
        val protocolVersion: Int? = null,
        val maxPlayers: Int? = null,
        val onlinePlayers: Int,
        val playerSample: List<PlayerReference>? = null,
        val motd: String? = null,
        val gameMode: String? = null,
        val difficulty: String = "normal",
        val hardcore: Boolean = false,
        val pvpEnabled: Boolean = true,
        val updatedAt: String,
    )
}
