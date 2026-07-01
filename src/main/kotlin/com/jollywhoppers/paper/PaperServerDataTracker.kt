package com.jollywhoppers.paper

import com.jollywhoppers.atproto.server.AtProtoCollections
import com.jollywhoppers.atproto.server.BlueskyPostPublisher
import com.jollywhoppers.atproto.server.RecordManager
import com.jollywhoppers.atproto.server.AtProtoSessionManager
import com.jollywhoppers.atproto.server.PlayerStatSyncStore
import com.jollywhoppers.atproto.server.ServerAccount
import com.jollywhoppers.atproto.server.ServerIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.Statistic
import java.time.Duration
import java.time.Instant
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PaperServerDataTracker(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val recordManager: RecordManager,
    private val sessionManager: AtProtoSessionManager,
    private val blueskyPostPublisher: BlueskyPostPublisher,
    private val statsSyncStore: PlayerStatSyncStore,
) : Listener {
    private val joinedAt = ConcurrentHashMap<UUID, Instant>()
    private val plainText = PlainTextComponentSerializer.plainText()
    private var lastStatusPostAt: Instant? = null
    private var lastStatusPostSignature: String? = null

    private companion object {
        val STATUS_POST_INTERVAL: Duration = Duration.ofMinutes(30)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        joinedAt[event.player.uniqueId] = Instant.now()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val joined = joinedAt.remove(event.player.uniqueId) ?: return
        if (!sessionManager.hasSession(event.player.uniqueId)) return
        val left = Instant.now()
        val record = buildJsonObject {
            putJsonObject("player") {
                put("uuid", event.player.uniqueId.toString())
                put("username", event.player.name)
            }
            putJsonObject("server") { put("serverId", ServerIdentity.buildServerId()) }
            put("joinedAt", joined.toString())
            put("leftAt", left.toString())
            put("durationMinutes", Duration.between(joined, left).toMinutes().coerceAtLeast(0))
            put("quitReason", event.reason.name.lowercase())
        }
        scope.launch {
            recordManager.createRecord(event.player.uniqueId, AtProtoCollections.PLAYER_SESSION, record)
                .onFailure { plugin.logger.warning("Failed to publish session for ${event.player.name}: ${it.message}") }
        }
    }

    fun publishServerStatus() {
        if (!ServerAccount.isConfigured()) return
        val now = Instant.now()
        val version = plugin.server.minecraftVersion
        val onlinePlayers = plugin.server.onlinePlayers.size
        val maxPlayers = plugin.server.maxPlayers
        val samplePlayers = plugin.server.onlinePlayers.take(3).map { it.name }
        val statusSignature = listOf(
            version,
            onlinePlayers.toString(),
            maxPlayers.toString(),
            plugin.server.worlds.firstOrNull()?.difficulty?.name ?: "normal",
            plugin.server.worlds.firstOrNull()?.pvp?.toString() ?: "true",
            samplePlayers.joinToString(","),
        ).joinToString("|")
        val record = buildJsonObject {
            putJsonObject("server") { put("serverId", ServerIdentity.buildServerId()) }
            put("version", version)
            put("maxPlayers", maxPlayers)
            put("onlinePlayers", onlinePlayers)
            put("playerSample", buildJsonArray {
                plugin.server.onlinePlayers.take(100).forEach { player ->
                    add(buildJsonObject {
                        put("uuid", player.uniqueId.toString())
                        put("username", player.name)
                    })
                }
            })
            put("motd", plainText.serialize(plugin.server.motd()))
            put("difficulty", plugin.server.worlds.firstOrNull()?.difficulty?.name?.lowercase() ?: "normal")
            put("hardcore", plugin.server.isHardcore)
            put("pvpEnabled", plugin.server.worlds.firstOrNull()?.pvp ?: true)
            put("updatedAt", now.toString())
        }
        scope.launch {
            recordManager.putRecord(ServerAccount.SERVER_PLAYER_UUID, AtProtoCollections.SERVER_STATUS, "self", record)
                .onSuccess {
                    maybePostServerStatus(
                        now = now,
                        version = version,
                        onlinePlayers = onlinePlayers,
                        maxPlayers = maxPlayers,
                        motd = plainText.serialize(plugin.server.motd()),
                        samplePlayers = samplePlayers,
                        serverId = ServerIdentity.buildServerId(),
                        statusSignature = statusSignature,
                    )
                }.onFailure { plugin.logger.warning("Failed to publish server status: ${it.message}") }
        }
    }

    fun publishPlayerStats() {
        plugin.server.onlinePlayers.forEach { player ->
            if (!sessionManager.hasSession(player.uniqueId)) return@forEach
            val statistics = Statistic.entries.asSequence()
                .filter { it.type == Statistic.Type.UNTYPED }
                .mapNotNull { statistic ->
                    runCatching {
                        buildJsonObject {
                            put("key", "minecraft:${statistic.name.lowercase()}")
                            put("value", player.getStatistic(statistic))
                            put("category", "custom")
                        }
                    }.getOrNull()
                }
                .take(1000)
                .toList()
            val playtimeMinutes = player.getStatistic(Statistic.PLAY_ONE_MINUTE).toLong() / (20L * 60L)
            val level = player.level.toLong()
            val gamemode = player.gameMode.name.lowercase()
            val dimension = player.world.key.toString()
            val snapshotSeed = buildString {
                append(player.uniqueId)
                append('|')
                append(playtimeMinutes)
                append('|')
                append(level)
                append('|')
                append(gamemode)
                append('|')
                append(dimension)
                append('|')
                statistics.take(25).forEach { stat ->
                    append(stat["key"])
                    append('=')
                    append(stat["value"])
                    append(';')
                }
            }
            val snapshotHash = sha256(snapshotSeed)
            if (!statsSyncStore.shouldSync(player.uniqueId, snapshotHash)) {
                return@forEach
            }
            val record = buildJsonObject {
                putJsonObject("player") {
                    put("uuid", player.uniqueId.toString())
                    put("username", player.name)
                }
                putJsonObject("server") { put("serverId", ServerIdentity.buildServerId()) }
                put("statistics", buildJsonArray { statistics.forEach(::add) })
                put("playtimeMinutes", playtimeMinutes)
                put("level", level)
                put("gamemode", gamemode)
                put("dimension", dimension)
                put("syncedAt", Instant.now().toString())
            }
            val summary = buildString {
                append(player.name)
                append(" updated their Minecraft stats: ")
                append(level)
                append(" XP level, ")
                append(playtimeMinutes)
                append(" minutes played, ")
                append(gamemode)
                append(" in ")
                append(dimension)
                append(".")
            }
            scope.launch {
                recordManager.createRecord(player.uniqueId, AtProtoCollections.PLAYER_STATS, record)
                    .onSuccess {
                        statsSyncStore.recordSuccess(player.uniqueId, snapshotHash)
                        blueskyPostPublisher.postPlayerStats(player.uniqueId, summary)
                            .onFailure { error ->
                                plugin.logger.warning("Failed to post stats for ${player.name} to Bluesky: ${error.message}")
                            }
                    }
                    .onFailure {
                        statsSyncStore.recordFailure(player.uniqueId, it.message ?: "unknown error")
                        plugin.logger.warning("Failed to publish stats for ${player.name}: ${it.message}")
                    }
            }
        }
    }

    private fun maybePostServerStatus(
        now: Instant,
        version: String,
        onlinePlayers: Int,
        maxPlayers: Int,
        motd: String,
        samplePlayers: List<String>,
        serverId: String,
        statusSignature: String,
    ) {
        val previousPostAt = lastStatusPostAt
        val withinWindow = previousPostAt != null && Duration.between(previousPostAt, now) < STATUS_POST_INTERVAL
        if (statusSignature == lastStatusPostSignature) {
            return
        }
        if (withinWindow) {
            return
        }

        scope.launch {
            blueskyPostPublisher.postServerStatus(
                version = version,
                onlinePlayers = onlinePlayers,
                maxPlayers = maxPlayers,
                motd = motd,
                serverId = serverId,
                samplePlayers = samplePlayers,
            ).onSuccess {
                lastStatusPostAt = now
                lastStatusPostSignature = statusSignature
            }.onFailure { error ->
                plugin.logger.warning("Failed to post server status to Bluesky: ${error.message}")
            }
        }
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
