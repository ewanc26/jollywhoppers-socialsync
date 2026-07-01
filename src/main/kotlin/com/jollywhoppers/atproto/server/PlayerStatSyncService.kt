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
 *
 * Non-sensitive stat filtering is driven by a per-server [PlayerStatFilterConfig]
 * loaded from `config/atproto-connect/stats-filter-config.json`. Server operators
 * can edit this file to control exactly which stats are synced.
 *
 * Filtering rules (from config):
 * - Allowlist: only stat keys under known non-sensitive prefixes (e.g. "minecraft:") are synced
 * - Blocklist: specific sensitive stats (e.g. leave_game, player_kills) are always excluded
 * - Custom/unknown stat types are excluded by default
 * - Per-player sync frequency from [PlayerSyncPreferences] is respected
 */
class PlayerStatSyncService(
    private val recordManager: RecordManager,
    private val sessionManager: AtProtoSessionManager,
    private val identityStore: PlayerIdentityStore,
    private val syncPreferencesStore: PlayerSyncPreferencesStore,
    storageFile: Path,
    private val defaultSyncIntervalTicks: Long = 20L * 60L * 5L,
    filterConfig: PlayerStatFilterConfig = PlayerStatFilterConfig()
) {
    /** The active filter rules derived from the config */
    @Volatile
    var filterSets: PlayerStatFilterConfig.FilterSets = filterConfig.toFilterSets()
        private set
    private val logger = LoggerFactory.getLogger("atproto-connect:stats")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val syncStateStore = PlayerStatSyncStore(storageFile)
    private val activeSyncs = ConcurrentHashMap.newKeySet<UUID>()
    private val sessionCache = ConcurrentHashMap<UUID, Result<*>>()

    @Volatile
    private var lastCacheRefreshTime = 0L

    private val playerNextTick = ConcurrentHashMap<UUID, Long>()

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val COLLECTION_ID = AtProtoCollections.PLAYER_STATS
        private const val MAX_STATISTICS_PER_RECORD = 1000
        private const val TICKS_PER_MINUTE = 20L * 60L
        private const val MIN_SYNC_INTERVAL_MINUTES = 1L
        private const val MAX_SYNC_INTERVAL_MINUTES = 1440L

        fun isStatNonSensitive(valueKey: String, categoryKey: String): Boolean {
            return PlayerStatFilterConfig.FilterSets().isStatNonSensitive(valueKey, categoryKey)
        }
    }

    /**
     * Reloads the filter configuration from disk.
     * Called by the admin reload-stats-filter command to apply config changes at runtime.
     */
    fun reloadFilterConfig(configDir: Path) {
        val config = PlayerStatFilterConfig.load(configDir)
        filterSets = config.toFilterSets()
        logger.info("Stats filter config reloaded from $configDir/stats-filter-config.json")
    }

    private fun isStatNonSensitive(valueKey: String, categoryKey: String): Boolean {
        return filterSets.isStatNonSensitive(valueKey, categoryKey)
    }

    fun onServerTick(server: MinecraftServer) {
        if (!server.isRunning || server.isStopped) return
        val currentTick = server.tickCount.toLong()

        refreshSessionCache(server)

        for (player in server.playerList.players) {
            if (!identityStore.isLinked(player.uuid)) continue
            val prefs = syncPreferencesStore.getOrDefault(player.uuid)
            if (!prefs.shouldSync("stats")) continue

            val playerIntervalTicks = (prefs.statsSyncFrequency.toLong()
                .coerceIn(MIN_SYNC_INTERVAL_MINUTES, MAX_SYNC_INTERVAL_MINUTES)) * TICKS_PER_MINUTE

            val nextTick = playerNextTick.getOrPut(player.uuid) { currentTick }
            if (currentTick < nextTick) continue

            // Use player's configured frequency for this player's next check
            playerNextTick[player.uuid] = currentTick + playerIntervalTicks

            val snapshot = buildSnapshot(server, player) ?: continue
            queueSync(snapshot)
        }
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

        val prefs = syncPreferencesStore.getOrDefault(player.uuid)
        if (!prefs.shouldSync("stats")) return null

        val statistics = extractNonSensitiveStatistics(player)
        if (statistics.isEmpty()) {
            logger.debug("No non-sensitive stats to sync for ${player.name.string}")
            return null
        }

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

    /**
     * Extracts only non-sensitive statistics from a player.
     *
     * Filtering rules:
     * 1. Stat keys matching a [SENSITIVE_STAT_KEYS] entry are always excluded
     * 2. Stats in [NON_SENSITIVE_CATEGORIES] (mined, crafted, used, broken, picked_up, dropped) are included
     * 3. Custom stats matching [NON_SENSITIVE_CUSTOM_STATS] are included
     * 4. Stats with a key prefix in [NON_SENSITIVE_PREFIXES] are included (default: minecraft:)
     * 5. All other stats (custom mod stats, unknown prefixes) are excluded
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractNonSensitiveStatistics(player: ServerPlayer): List<StatisticEntry> {
        val statsCounter = player.getStats()
        val entries = mutableListOf<StatisticEntry>()
        val statTypeRegistry = BuiltInRegistries.STAT_TYPE

        for (statType in statTypeRegistry) {
            val categoryKey = statTypeRegistry.getKey(statType)?.toString() ?: continue

            val valueRegistry = statType.registry
            for (stat in statType) {
                val value = statsCounter.getValue(stat)
                if (value <= 0) continue

                val valueKey = try {
                    (valueRegistry as net.minecraft.core.Registry<Any>)
                        .getKey(stat.value ?: continue)?.toString() ?: continue
                } catch (e: Exception) {
                    logger.debug("Failed to resolve stat value key for category $categoryKey")
                    continue
                }

                if (!isStatNonSensitive(valueKey, categoryKey)) {
                    logger.debug("Excluding stat $valueKey (category: $categoryKey) - not in non-sensitive allowlist")
                    continue
                }

                entries.add(StatisticEntry(key = valueKey, value = value, category = categoryKey))
            }
        }
        return entries
    }

    private fun mapGameMode(player: ServerPlayer) = player.gameMode.getGameModeForPlayer().name.lowercase()

    private fun computeFingerprint(stats: List<StatisticEntry>, playtime: Int, player: ServerPlayer): String {
        val payload = "$stats$playtime${player.experienceLevel}${player.level().dimension().location()}"
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
