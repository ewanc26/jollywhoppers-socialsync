package com.jollywhoppers.atproto.server

import com.jollywhoppers.security.SecurityUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class AchievementSyncStore(private val storageFile: Path) {
    private val logger = LoggerFactory.getLogger("atproto-connect")
    private val syncedAchievements = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val fileLock = ReentrantReadWriteLock()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    @Serializable
    data class AchievementState(val uuid: String, val syncedAdvancementIds: List<String>)

    @Serializable
    private data class AchievementStorage(val version: Int = 1, val states: List<AchievementState>)

    init {
        val configDir = storageFile.parent
        if (!SecurityUtils.validatePathInDirectory(storageFile, configDir)) {
            throw SecurityException("Storage file path is outside expected directory")
        }
        load()
        logger.info("Achievement sync store initialized with ${syncedAchievements.size} entries")
    }

    fun isSynced(uuid: UUID, advancementId: String): Boolean {
        return syncedAchievements[uuid]?.contains(advancementId) == true
    }

    fun markSynced(uuid: UUID, advancementId: String) = fileLock.write {
        syncedAchievements.getOrPut(uuid) { ConcurrentHashMap.newKeySet() }.add(advancementId)
        save()
    }

    fun removeSynced(uuid: UUID, advancementId: String) = fileLock.write {
        syncedAchievements[uuid]?.remove(advancementId)
        save()
    }

    private fun load() = fileLock.read {
        try {
            if (!Files.exists(storageFile)) return@read
            val content = Files.readString(storageFile)
            val storage = json.decodeFromString(AchievementStorage.serializer(), content)
            storage.states.forEach { state ->
                syncedAchievements[UUID.fromString(state.uuid)] = ConcurrentHashMap.newKeySet<String>().also { it.addAll(state.syncedAdvancementIds) }
            }
        } catch (e: Exception) {
            logger.error("Failed to load achievement sync state", e)
        }
    }

    private fun save() {
        try {
            Files.createDirectories(storageFile.parent)
            val storage = AchievementStorage(states = syncedAchievements.map { (uuid, ids) ->
                AchievementState(uuid.toString(), ids.toList())
            }.sortedBy { it.uuid })
            val content = json.encodeToString(AchievementStorage.serializer(), storage)
            val tempFile = storageFile.parent.resolve("${storageFile.fileName}.tmp")
            Files.writeString(tempFile, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            SecurityUtils.setRestrictedPermissions(tempFile)
            Files.move(tempFile, storageFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            logger.error("Failed to save achievement sync state", e)
        }
    }
}
