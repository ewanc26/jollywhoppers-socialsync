package com.jollywhoppers.paper

import com.jollywhoppers.atproto.server.AchievementSyncStore
import com.jollywhoppers.atproto.server.AchievementMetadata
import com.jollywhoppers.atproto.server.AtProtoCollections
import com.jollywhoppers.atproto.server.RecordManager
import com.jollywhoppers.atproto.server.ServerAccount
import com.jollywhoppers.atproto.server.ServerIdentity
import com.jollywhoppers.atproto.server.model.Achievement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import io.papermc.paper.advancement.AdvancementDisplay
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.plugin.java.JavaPlugin
import java.time.Instant

class PaperAchievementTracker(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val recordManager: RecordManager,
    private val syncStore: AchievementSyncStore,
) : Listener {
    private val json = Json { encodeDefaults = false }
    private val plainText = PlainTextComponentSerializer.plainText()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onAdvancementDone(event: PlayerAdvancementDoneEvent) {
        val player = event.player
        val advancementId = event.advancement.key.toString()
        if (syncStore.isSynced(player.uniqueId, advancementId)) return
        if (!ServerAccount.isConfigured()) {
            plugin.logger.fine("Achievement $advancementId not published: server AT Protocol account is not configured")
            return
        }

        val display = event.advancement.display
        val record = Achievement(
            player = buildJsonObject {
                put("uuid", player.uniqueId.toString())
                put("username", player.name)
            },
            server = buildJsonObject { put("serverId", ServerIdentity.buildServerId()) },
            achievementId = advancementId,
            achievementName = display?.title()?.let(plainText::serialize),
            achievementDescription = display?.description()?.let(plainText::serialize),
            achievedAt = Instant.now().toString(),
            category = AchievementMetadata.categoryOf(advancementId),
            isChallenge = display?.frame() == AdvancementDisplay.Frame.CHALLENGE,
        )

        scope.launch {
            recordManager.createRecord(
                playerUuid = ServerAccount.SERVER_PLAYER_UUID,
                collection = AtProtoCollections.ACHIEVEMENT,
                record = json.encodeToJsonElement(Achievement.serializer(), record).jsonObject,
            ).onSuccess {
                syncStore.markSynced(player.uniqueId, advancementId)
                plugin.logger.info("Published achievement '$advancementId' for ${player.name}")
            }.onFailure { error ->
                plugin.logger.warning("Failed to publish achievement '$advancementId' for ${player.name}: ${error.message}")
            }
        }
    }
}
