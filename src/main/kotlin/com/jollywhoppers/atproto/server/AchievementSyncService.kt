package com.jollywhoppers.atproto.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.advancements.AdvancementHolder
import net.minecraft.advancements.DisplayInfo
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Syncs Minecraft advancements (achievements) to AT Protocol records.
 *
 * When a player earns a full advancement, the mixin in PlayerAdvancementTrackerMixin
 * calls [onAdvancementCompleted], which creates an achievement record in the
 * player's AT Protocol repository.
 *
 * Privacy controls:
 * - Respects the `publicStats` privacy setting from PlayerIdentityStore
 *   (achievements are considered stats-adjacent data)
 * - If publicStats is false, achievements are not synced
 *
 * Deduplication:
 * - Tracks recently synced advancements to avoid duplicates
 * - The AT Protocol record key (TID) provides natural dedup since each
 *   record gets a unique TID, but we still avoid re-syncing the same
 *   advancement within a session
 */
class AchievementSyncService(
    private val recordManager: RecordManager,
    private val sessionManager: AtProtoSessionManager,
    private val identityStore: PlayerIdentityStore,
    private val syncPreferencesStore: PlayerSyncPreferencesStore,
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:achievements")
    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Track advancements already synced this session to avoid duplicates
    private val syncedAdvancements = ConcurrentHashMap<UUID, MutableSet<String>>()

    companion object {
        private const val COLLECTION_ID = "com.jollywhoppers.minecraft.achievement"

        /**
         * Singleton instance for the mixin to call.
         * Set by the mod initializer.
         */
        lateinit var INSTANCE: AchievementSyncService
    }

    /**
     * Called by the mixin when a player completes a full advancement.
     * This is the entry point from the Java mixin.
     */
    fun onAdvancementCompleted(player: ServerPlayer, advancement: AdvancementHolder) {
        val uuid = player.uuid

        // Check if linked and authenticated
        if (!identityStore.isLinked(uuid) || !sessionManager.hasSession(uuid)) {
            return
        }

        // Check sync consent
        if (!syncPreferencesStore.getOrDefault(uuid).shouldSync("achievements")) {
            logger.debug("Skipping achievement sync for ${player.name.string}: achievements sync consent is disabled")
            return
        }

        // Dedup: check if already synced this session
        val advancementId = advancement.id().toString()
        val playerSynced = syncedAdvancements.getOrPut(uuid) { ConcurrentHashMap.newKeySet() }
        if (!playerSynced.add(advancementId)) {
            logger.debug("Already synced advancement $advancementId for ${player.name.string}")
            return
        }

        // Extract advancement details
        val display = advancement.value().display().orElse(null)
        val advancementName = display?.getTitle()?.string ?: advancementId.substringAfterLast('/')
        val advancementDescription = display?.getDescription()?.string ?: ""
        val category = extractCategory(advancementId)
        val isChallenge = display?.isHidden ?: false

        coroutineScope.launch {
            try {
                val record = MinecraftAchievementRecord(
                    player = PlayerReference(
                        uuid = uuid.toString(),
                        username = player.name.string,
                    ),
                    achievementId = advancementId,
                    achievementName = advancementName,
                    achievementDescription = advancementDescription.ifEmpty { null },
                    achievedAt = Instant.now().toString(),
                    category = category,
                    isChallenge = isChallenge,
                )

                recordManager.createTypedRecord(
                    playerUuid = uuid,
                    collection = COLLECTION_ID,
                    record = record,
                ).getOrThrow()

                logger.info("Synced achievement '$advancementName' for ${player.name.string} ($uuid)")
            } catch (e: Exception) {
                logger.error("Failed to sync achievement '$advancementId' for ${player.name.string} ($uuid)", e)
                // Remove from synced set so it can be retried
                playerSynced.remove(advancementId)
            }
        }
    }

    /**
     * Clears the sync tracking for a player (e.g., on disconnect).
     */
    fun clearPlayerTracking(uuid: UUID) {
        syncedAdvancements.remove(uuid)
    }

    /**
     * Extracts the advancement category from the advancement ID.
     * Minecraft advancement IDs follow the pattern: minecraft:<category>/<name>
     */
    private fun extractCategory(advancementId: String): String {
        return when {
            advancementId.contains("minecraft:story") -> "story"
            advancementId.contains("minecraft:nether") -> "nether"
            advancementId.contains("minecraft:end") -> "end"
            advancementId.contains("minecraft:adventure") -> "adventure"
            advancementId.contains("minecraft:husbandry") -> "husbandry"
            else -> {
                // Try to extract from the ID pattern
                val parts = advancementId.split("/")
                if (parts.size > 1) parts[0].substringAfterLast(":") else "other"
            }
        }
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
    data class MinecraftAchievementRecord(
        @SerialName("\$type") val type: String = COLLECTION_ID,
        val player: PlayerReference,
        val achievementId: String,
        val achievementName: String,
        val achievementDescription: String? = null,
        val achievedAt: String,
        val category: String,
        val isChallenge: Boolean = false,
    )
}
