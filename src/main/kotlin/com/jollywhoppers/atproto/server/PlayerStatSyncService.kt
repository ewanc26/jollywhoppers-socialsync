package com.jollywhoppers.atproto.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stat
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Periodically snapshots online players' stats and syncs them to AT Protocol.
 */
class PlayerStatSyncService(
    private val recordManager: RecordManager,
    private val sessionManager: AtProtoSessionManager,
    private val identityStore: PlayerIdentityStore,
    storageFile: Path,
    private val syncIntervalTicks: Long = 20L * 60L * 5L
) {
    private val logger = LoggerFactory.getLogger("atproto-connect")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val syncStateStore = PlayerStatSyncStore(storageFile)
    private val activeSyncs = ConcurrentHashMap.newKeySet<UUID>()

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var nextEvaluationTick: Long = 0L

    fun onServerTick(server: MinecraftServer) {
        if (!server.isRunning || server.isStopped) {
            return
        }

        val currentTick = server.tickCount.toLong()
        if (currentTick < nextEvaluationTick) {
            return
        }

        nextEvaluationTick = currentTick + syncIntervalTicks

        val snapshots = server.playerList.players
            .mapNotNull { player -> buildSnapshot(server, player) }

        if (snapshots.isEmpty()) {
            return
        }

        snapshots.forEach { snapshot ->
            queueSync(snapshot)
        }
    }

    fun shutdown() {
        coroutineScope.cancel()
    }

    private fun queueSync(snapshot: StatsSnapshot) {
        if (!activeSyncs.add(snapshot.player.uuid)) {
            return
        }

        coroutineScope.launch {
            try {
                if (!syncStateStore.shouldSync(snapshot.player.uuid, snapshot.fingerprint)) {
                    logger.debug(
                        "Skipping stat sync for ${snapshot.player.username} (${snapshot.player.uuid}); snapshot unchanged"
                    )
                    return@launch
                }

                syncStateStore.recordAttempt(snapshot.player.uuid)

                recordManager.createTypedRecord(
                    playerUuid = snapshot.player.uuid,
                    collection = COLLECTION_ID,
                    record = snapshot.record
                ).getOrThrow()

                syncStateStore.recordSuccess(snapshot.player.uuid, snapshot.fingerprint)
                logger.info(
                    "Synced Minecraft stats for ${snapshot.player.username} (${snapshot.player.uuid})"
                )
            } catch (e: Exception) {
                val errorMessage = e.message ?: e.javaClass.simpleName
                syncStateStore.recordFailure(snapshot.player.uuid, errorMessage)
                logger.error(
                    "Failed to sync Minecraft stats for ${snapshot.player.username} (${snapshot.player.uuid})",
                    e
                )
            } finally {
                activeSyncs.remove(snapshot.player.uuid)
            }
        }
    }

    private fun buildSnapshot(server: MinecraftServer, player: ServerPlayer): StatsSnapshot? {
        if (!identityStore.isLinked(player.uuid) || !sessionManager.hasSession(player.uuid)) {
            return null
        }

        val statistics = extractStatistics(player)
        if (statistics.isEmpty()) {
            logger.debug("No statistics found for ${player.name.string} (${player.uuid}); syncing baseline snapshot")
        }

        val orderedStatistics = statistics
            .sortedWith(
                compareBy<StatisticEntry> { it.category }
                    .thenBy { it.key }
                    .thenBy { it.value }
            )
            .take(MAX_STATISTICS_PER_RECORD)

        val playtimeMinutes = extractPlaytimeMinutes(statistics)
        val fingerprint = computeFingerprint(
            StatsFingerprint(
                player = PlayerReference(
                    uuid = player.uuid.toString(),
                    username = player.name.string
                ),
                server = buildServerReference(server),
                statistics = orderedStatistics,
                playtimeMinutes = playtimeMinutes,
                level = player.experienceLevel,
                gamemode = mapGameMode(player),
                dimension = player.level().dimension().location().toString()
            )
        )

        val record = MinecraftPlayerStatsRecord(
            player = PlayerReference(
                uuid = player.uuid.toString(),
                username = player.name.string
            ),
            server = buildServerReference(server),
            statistics = orderedStatistics,
            playtimeMinutes = playtimeMinutes,
            level = player.experienceLevel,
            gamemode = mapGameMode(player),
            dimension = player.level().dimension().location().toString(),
            syncedAt = Instant.now().toString()
        )

        return StatsSnapshot(
            player = SnapshotPlayer(
                uuid = player.uuid,
                username = player.name.string
            ),
            record = record,
            fingerprint = fingerprint
        )
    }

    private fun extractStatistics(player: ServerPlayer): List<StatisticEntry> {
        val statsCounter = player.getStats()
        val statsField = runCatching {
            statsCounter.javaClass.superclass.getDeclaredField("stats").apply {
                isAccessible = true
            }
        }.getOrElse { error ->
            logger.warn(
                "Unable to access stats map for ${player.name.string} (${player.uuid}): ${error.message}",
                error
            )
            return emptyList()
        }

        val statsMap = runCatching {
            @Suppress("UNCHECKED_CAST")
            statsField.get(statsCounter) as? Map<*, *>
        }.getOrElse { error ->
            logger.warn(
                "Unable to read stats map for ${player.name.string} (${player.uuid}): ${error.message}",
                error
            )
            return emptyList()
        } ?: return emptyList()

        return statsMap.entries.mapNotNull { (rawStat, rawValue) ->
            val stat = rawStat as? Stat<*> ?: return@mapNotNull null
            val value = (rawValue as? Number)?.toInt() ?: return@mapNotNull null
            val category = normalizeStatCategory(stat.getType().getDisplayName().string)
            val key = "${category}/${stat.getValue()}"

            StatisticEntry(
                key = key,
                value = value,
                category = category
            )
        }
    }

    private fun normalizeStatCategory(rawCategory: String): String {
        return rawCategory
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "custom" }
    }

    private fun extractPlaytimeMinutes(statistics: List<StatisticEntry>): Int {
        val playTimeTicks = statistics.firstOrNull { entry ->
            entry.key.contains("play_time", ignoreCase = true)
        }?.value ?: 0

        val minutes = playTimeTicks / 20 / 60
        return minutes.coerceAtLeast(0)
    }

    private fun buildServerReference(server: MinecraftServer): ServerReference {
        val serverId = buildServerId(server)
        val serverName = server.getMotd().ifBlank { "Minecraft Server" }
        val serverAddress = server.getLocalIp().takeIf { it.isNotBlank() }?.let { ip ->
            val port = server.getPort()
            if (port > 0) {
                "$ip:$port"
            } else {
                ip
            }
        }

        return ServerReference(
            serverId = serverId,
            serverName = serverName,
            serverAddress = serverAddress
        )
    }

    private fun buildServerId(server: MinecraftServer): String {
        val serverPath = server.getServerDirectory()
            .toAbsolutePath()
            .normalize()
            .toString()

        val payload = "socialsync:$serverPath"
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun mapGameMode(player: ServerPlayer): String {
        return when (player.gameMode.getGameModeForPlayer().name.lowercase()) {
            "creative" -> "creative"
            "adventure" -> "adventure"
            "spectator" -> "spectator"
            else -> "survival"
        }
    }

    private fun computeFingerprint(fingerprint: StatsFingerprint): String {
        val payload = json.encodeToString(StatsFingerprint.serializer(), fingerprint)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    data class SnapshotPlayer(
        val uuid: UUID,
        val username: String
    )

    data class StatsSnapshot(
        val player: SnapshotPlayer,
        val record: MinecraftPlayerStatsRecord,
        val fingerprint: String
    )

    @Serializable
    data class StatsFingerprint(
        val player: PlayerReference,
        val server: ServerReference,
        val statistics: List<StatisticEntry>,
        val playtimeMinutes: Int,
        val level: Int,
        val gamemode: String,
        val dimension: String
    )

    @Serializable
    data class PlayerReference(
        val uuid: String,
        val username: String
    )

    @Serializable
    data class ServerReference(
        val serverId: String,
        val serverName: String,
        val serverAddress: String? = null
    )

    @Serializable
    data class StatisticEntry(
        val key: String,
        val value: Int,
        val category: String
    )

    @Serializable
    data class MinecraftPlayerStatsRecord(
        @SerialName("\$type") val type: String = COLLECTION_ID,
        val player: PlayerReference,
        val server: ServerReference,
        val statistics: List<StatisticEntry>,
        val playtimeMinutes: Int,
        val level: Int,
        val gamemode: String,
        val dimension: String,
        val syncedAt: String
    )

    companion object {
        private const val COLLECTION_ID = "com.jollywhoppers.minecraft.player.stats"
        private const val MAX_STATISTICS_PER_RECORD = 1000
    }
}
