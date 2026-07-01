package com.jollywhoppers.paper

import com.jollywhoppers.atproto.server.AtProtoCollections
import com.jollywhoppers.atproto.server.RecordManager
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PaperServerDataTracker(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val recordManager: RecordManager,
) : Listener {
    private val joinedAt = ConcurrentHashMap<UUID, Instant>()
    private val plainText = PlainTextComponentSerializer.plainText()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        joinedAt[event.player.uniqueId] = Instant.now()
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        val joined = joinedAt.remove(event.player.uniqueId) ?: return
        if (!ServerAccount.isConfigured()) return
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
            recordManager.createRecord(ServerAccount.SERVER_PLAYER_UUID, AtProtoCollections.PLAYER_SESSION, record)
                .onFailure { plugin.logger.warning("Failed to publish session for ${event.player.name}: ${it.message}") }
        }
    }

    fun publishServerStatus() {
        if (!ServerAccount.isConfigured()) return
        val record = buildJsonObject {
            putJsonObject("server") { put("serverId", ServerIdentity.buildServerId()) }
            put("version", plugin.server.minecraftVersion)
            put("maxPlayers", plugin.server.maxPlayers)
            put("onlinePlayers", plugin.server.onlinePlayers.size)
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
            put("updatedAt", Instant.now().toString())
        }
        scope.launch {
            recordManager.putRecord(ServerAccount.SERVER_PLAYER_UUID, AtProtoCollections.SERVER_STATUS, "self", record)
                .onFailure { plugin.logger.warning("Failed to publish server status: ${it.message}") }
        }
    }

    fun publishPlayerStats() {
        if (!ServerAccount.isConfigured()) return
        plugin.server.onlinePlayers.forEach { player ->
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
            val record = buildJsonObject {
                putJsonObject("player") {
                    put("uuid", player.uniqueId.toString())
                    put("username", player.name)
                }
                putJsonObject("server") { put("serverId", ServerIdentity.buildServerId()) }
                put("statistics", buildJsonArray { statistics.forEach(::add) })
                put("playtimeMinutes", player.getStatistic(Statistic.PLAY_ONE_MINUTE).toLong() / (20L * 60L))
                put("level", player.level.toLong())
                put("gamemode", player.gameMode.name.lowercase())
                put("dimension", player.world.key.toString())
                put("syncedAt", Instant.now().toString())
            }
            scope.launch {
                recordManager.createRecord(ServerAccount.SERVER_PLAYER_UUID, AtProtoCollections.PLAYER_STATS, record)
                    .onFailure { plugin.logger.warning("Failed to publish stats for ${player.name}: ${it.message}") }
            }
        }
    }
}
