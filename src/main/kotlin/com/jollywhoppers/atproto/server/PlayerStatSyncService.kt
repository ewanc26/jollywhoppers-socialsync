package com.jollywhoppers.atproto.server

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stat
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import com.jollywhoppers.atproto.server.ServerIdentity
import com.jollywhoppers.atproto.server.model.Stats

/**
 * Periodically snapshots online players' stats and syncs them to AT Protocol.
 */
class PlayerStatSyncService(
    private val recordManager: RecordManager,
    private val sessionManager: AtProtoSessionManager,
    private val identityStore: PlayerIdentityStore,
    private val syncPreferencesStore: PlayerSyncPreferencesStore,
    storageFile: Path,
    private val syncIntervalTicks: Long = 20L * 60L * 5L
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:stats")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val syncStateStore = PlayerStatSyncStore(storageFile)
    private val activeSyncs = ConcurrentHashMap.newKeySet<UUID>()
    private val sessionCache = ConcurrentHashMap<UUID, Result<*>>()

    @Volatile
    private var lastCacheRefreshTime = 0L

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var nextEvaluationTick: Long = 0L

    companion object {
        private const val COLLECTION_ID = "com.jollywhoppers.minecraft.player.stats"
        private const val MAX_STATISTICS_PER_RECORD = 1000
    }

    fun onServerTick(server: MinecraftServer) {
        if (!server.isRunning || server.isStopped) return
        val currentTick = server.tickCount.toLong()
        if (currentTick < nextEvaluationTick) return
        nextEvaluationTick = currentTick + syncIntervalTicks

        refreshSessionCache(server)

        server.playerList.players
            .mapNotNull { player -> buildSnapshot(server, player) }
            .forEach { snapshot -> queueSync(snapshot) }
    }

    private fun queueSync(snapshot: StatsSnapshot) {
        if (!activeSyncs.add(snapshot.player.uuid)) return

        coroutineScope.launch {
            try {
                if (!syncStateStore.shouldSync(snapshot.player.uuid, snapshot.fingerprint)) {
                    return@launch
                }
                syncStateStore.recordAttempt(snapshot.player.uuid)

                recordManager.createRecord(
                    playerUuid = snapshot.player.uuid,
                    collection = COLLECTION_ID,
                    record = json.encodeToJsonElement(snapshot.record).jsonObject,
                    validate = true
                ).getOrThrow()

                syncStateStore.recordSuccess(snapshot.player.uuid, snapshot.fingerprint)
                logger.info("Synced stats for ${snapshot.player.username} (${snapshot.player.uuid})")
            } catch (e: Exception) {
                syncStateStore.recordFailure(snapshot.player.uuid, e.message ?: "Unknown error")
                logger.error("Failed to sync stats", e)
            } finally {
                activeSyncs.remove(snapshot.player.uuid)
            }
        }
    }

    private fun refreshSessionCache(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        if (now - lastCacheRefreshTime < 30_000) return
        lastCacheRefreshTime = now
        coroutineScope.launch {
            val players = server.playerList.players
            for (player in players) {
                if (identityStore.isLinked(player.uuid)) {
                    sessionCache[player.uuid] = sessionManager.getSession(player.uuid)
                }
            }
        }
    }

    private fun buildSnapshot(server: MinecraftServer, player: ServerPlayer): StatsSnapshot? {
        if (!identityStore.isLinked(player.uuid)) return null
        
        val cachedSession = sessionCache[player.uuid]
        if (cachedSession == null || !cachedSession.isSuccess) return null
        
        if (!syncPreferencesStore.getOrDefault(player.uuid).shouldSync("stats")) return null

        val statistics = extractStatistics(player)
        val orderedStatistics = statistics.sortedWith(
            compareBy<StatisticEntry> { it.category }.thenBy { it.key }.thenBy { it.value }
        ).take(MAX_STATISTICS_PER_RECORD)

        val playtimeMinutes = extractPlaytimeMinutes(statistics)
        val fingerprint = computeFingerprint(orderedStatistics, playtimeMinutes, player)

        val record = Stats(
            player = buildJsonObject { put("uuid", player.uuid.toString()); put("username", player.name.string) },
            server = buildJsonObject { put("serverId", ServerIdentity.buildServerId()) },
            statistics = orderedStatistics.map { 
                buildJsonObject { put("key", it.key); put("value", it.value.toLong()); put("category", it.category) } 
            },
            playtimeMinutes = playtimeMinutes.toLong(),
            level = player.experienceLevel.toLong(),
            gamemode = mapGameMode(player),
            dimension = player.level().dimension().location().toString(),
            syncedAt = Instant.now().toString()
        )

        return StatsSnapshot(SnapshotPlayer(player.uuid, player.name.string), record, fingerprint)
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractStatistics(player: ServerPlayer): List<StatisticEntry> {
        val statsCounter = player.getStats()
        val entries = mutableListOf<StatisticEntry>()
        val statTypeRegistry = BuiltInRegistries.STAT_TYPE

        for (statType in statTypeRegistry) {
            val categoryKey = statTypeRegistry.getKey(statType)?.toString() ?: continue
            val valueRegistry = statType.registry
            for (stat in statType) {
                val value = statsCounter.getValue(stat)
                if (value > 0) {
                    val valueKey = (valueRegistry as net.minecraft.core.Registry<Any>).getKey(stat.value!!)?.toString() ?: continue
                    entries.add(StatisticEntry(key = valueKey, value = value, category = categoryKey))
                }
            }
        }
        return entries
    }

    private fun mapGameMode(player: ServerPlayer) = player.gameMode.getGameModeForPlayer().name.lowercase()

    private fun computeFingerprint(stats: List<StatisticEntry>, playtime: Int, player: ServerPlayer): String {
        val payload = "$stats$playtime${player.experienceLevel}"
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun extractPlaytimeMinutes(stats: List<StatisticEntry>): Int {
        val playTicks = stats.find { it.key == "minecraft:play_time" }?.value ?: 0
        return playTicks / (20 * 60)
    }

    fun shutdown() {
        try {
            runBlocking {
                withTimeout(5000) {
                    coroutineScope.coroutineContext[Job]?.children?.forEach { it.join() }
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("Timeout while shutting down ${this::class.simpleName}")
        }
    }

    data class SnapshotPlayer(val uuid: UUID, val username: String)
    data class StatsSnapshot(val player: SnapshotPlayer, val record: Stats, val fingerprint: String)
    data class StatisticEntry(val key: String, val value: Int, val category: String)
}
