package com.jollywhoppers.atproto.server

import net.minecraft.advancements.AdvancementHolder
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import com.jollywhoppers.atproto.server.ServerIdentity
import com.jollywhoppers.atproto.server.model.Achievement
import com.jollywhoppers.atproto.server.AchievementSyncStore

/**
 * Syncs Minecraft advancements (achievements) to AT Protocol records.
 */
class AchievementSyncService(
    private val recordManager: RecordManager,
    private val sessionManager: AtProtoSessionManager,
    private val identityStore: PlayerIdentityStore,
    private val syncPreferencesStore: PlayerSyncPreferencesStore,
    private val achievementSyncStore: AchievementSyncStore,
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:achievements")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val COLLECTION_ID = AtProtoCollections.ACHIEVEMENT
        lateinit var INSTANCE: AchievementSyncService
    }

    fun onAdvancementCompleted(player: ServerPlayer, advancement: AdvancementHolder) {
        val uuid = player.uuid

        coroutineScope.launch {
            // Check if linked and authenticated
            if (!identityStore.isLinked(uuid) || !sessionManager.getSession(uuid).isSuccess) {
                return@launch
            }

            // Check sync consent
            if (!syncPreferencesStore.getOrDefault(uuid).shouldSync("achievements")) {
                logger.debug("Skipping achievement sync for ${player.name.string}: achievements sync consent is disabled")
                return@launch
            }

            // Dedup: check if already synced
            val advancementId = advancement.id().toString()
            if (achievementSyncStore.isSynced(uuid, advancementId)) {
                logger.debug("Already synced advancement $advancementId for ${player.name.string}")
                return@launch
            }

            // Extract advancement details
            val display = advancement.value().display().orElse(null)
            val advancementName = display?.getTitle()?.string ?: advancementId.substringAfterLast('/')
            val advancementDescription = display?.getDescription()?.string ?: ""
            val category = extractCategory(advancementId)
            val isChallenge = display?.isHidden ?: false

            try {
                val record = Achievement(
                    player = buildJsonObject { put("uuid", uuid.toString()); put("username", player.name.string) },
                    server = buildJsonObject { put("serverId", ServerIdentity.buildServerId()) },
                    achievementId = advancementId,
                    achievementName = advancementName,
                    achievementDescription = advancementDescription.ifEmpty { "" },
                    achievedAt = Instant.now().toString(),
                    category = category,
                    isChallenge = isChallenge,
                )

                recordManager.createRecord(
                    playerUuid = uuid,
                    collection = COLLECTION_ID,
                    record = json.encodeToJsonElement(Achievement.serializer(), record).jsonObject,
                ).getOrThrow()

                achievementSyncStore.markSynced(uuid, advancementId)
                logger.info("Synced achievement '$advancementName' for ${player.name.string} ($uuid)")
            } catch (e: Exception) {
                logger.error("Failed to sync achievement '$advancementId' for ${player.name.string} ($uuid)", e)
                achievementSyncStore.removeSynced(uuid, advancementId)
            }
        }
    }

    internal fun extractCategory(advancementId: String): String {
        return when {
            advancementId.contains("minecraft:story") -> "story"
            advancementId.contains("minecraft:nether") -> "nether"
            advancementId.contains("minecraft:end") -> "end"
            advancementId.contains("minecraft:adventure") -> "adventure"
            advancementId.contains("minecraft:husbandry") -> "husbandry"
            else -> {
                val parts = advancementId.split("/")
                if (parts.size > 1) parts[0].substringAfterLast(":") else "other"
            }
        }
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
}
