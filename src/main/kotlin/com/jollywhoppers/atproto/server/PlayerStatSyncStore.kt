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

/**
 * Persistent sync state for Minecraft stat snapshots.
 * Tracks the last successful snapshot hash so the sync service can skip duplicates.
 */
class PlayerStatSyncStore(private val storageFile: Path) {
    private val logger = LoggerFactory.getLogger("atproto-connect")
    private val states = ConcurrentHashMap<UUID, SyncState>()
    private val fileLock = ReentrantReadWriteLock()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Serializable
    data class SyncState(
        val uuid: String,
        val lastSyncedHash: String? = null,
        val lastSyncedAt: Long? = null,
        val lastAttemptAt: Long? = null,
        val lastError: String? = null
    )

    @Serializable
    private data class SyncStorage(
        val version: Int = 1,
        val states: List<SyncState>
    )

    init {
        val configDir = storageFile.parent
        if (!SecurityUtils.validatePathInDirectory(storageFile, configDir)) {
            throw SecurityException("Storage file path is outside expected directory")
        }

        load()
        logger.info("Player stat sync store initialized with ${states.size} entries")
    }

    fun getState(uuid: UUID): SyncState? = states[uuid]

    fun shouldSync(uuid: UUID, snapshotHash: String): Boolean {
        return states[uuid]?.lastSyncedHash != snapshotHash
    }

    fun recordAttempt(uuid: UUID) {
        updateState(uuid) { current ->
            current.copy(
                lastAttemptAt = System.currentTimeMillis(),
                lastError = null
            )
        }
    }

    fun recordSuccess(uuid: UUID, snapshotHash: String) {
        updateState(uuid) { current ->
            current.copy(
                lastSyncedHash = snapshotHash,
                lastSyncedAt = System.currentTimeMillis(),
                lastAttemptAt = System.currentTimeMillis(),
                lastError = null
            )
        }
    }

    fun recordFailure(uuid: UUID, errorMessage: String) {
        updateState(uuid) { current ->
            current.copy(
                lastAttemptAt = System.currentTimeMillis(),
                lastError = errorMessage.take(500)
            )
        }
    }

    private fun updateState(uuid: UUID, transform: (SyncState) -> SyncState) = fileLock.write {
        val updated = transform(
            states[uuid] ?: SyncState(uuid = uuid.toString())
        )
        states[uuid] = updated.copy(uuid = uuid.toString())
        save()
    }

    private fun load() {
        fileLock.read {
            try {
                if (!Files.exists(storageFile)) {
                    logger.info("No existing player stat sync store found, starting fresh")
                    return@read
                }

                val content = Files.readString(storageFile)
                val storage = json.decodeFromString(SyncStorage.serializer(), content)

                storage.states.forEach { state ->
                    states[UUID.fromString(state.uuid)] = state
                }

                logger.info("Loaded ${states.size} player stat sync entries from disk")
            } catch (e: Exception) {
                logger.error("Failed to load player stat sync state", e)
            }
        }
    }

    private fun save() {
        try {
            Files.createDirectories(storageFile.parent)

            val storage = SyncStorage(
                version = 1,
                states = states.values.sortedBy { it.uuid }
            )
            val content = json.encodeToString(SyncStorage.serializer(), storage)

            val tempFile = storageFile.parent.resolve("${storageFile.fileName}.tmp")
            Files.writeString(
                tempFile,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )

            SecurityUtils.setRestrictedPermissions(tempFile)

            Files.move(
                tempFile,
                storageFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )

            logger.debug("Saved ${states.size} player stat sync entries to disk")
        } catch (e: Exception) {
            logger.error("Failed to save player stat sync state", e)
        }
    }
}
