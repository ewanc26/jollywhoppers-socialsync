package com.jollywhoppers.atproto.server

import com.jollywhoppers.atproto.server.model.IdentityRecord
import com.jollywhoppers.security.SecurityUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Manages the mapping between Minecraft UUIDs and AT Protocol DIDs.
 * Handles persistence to disk with proper file permissions and thread safety.
 * 
 * SECURITY FEATURES:
 * - Restricted file permissions (owner-only)
 * - Atomic file writes to prevent corruption
 * - Thread-safe operations
 * - Path validation
 */
class PlayerIdentityStore(
    private val storageFile: Path,
    private val recordManager: RecordManager? = null,
    private val sessionManager: AtProtoSessionManager? = null,
) {
    private val logger = LoggerFactory.getLogger("atproto-connect")
    private val identities = ConcurrentHashMap<UUID, PlayerIdentity>()
    private val fileLock = ReentrantReadWriteLock()
    private val atProtoScope = CoroutineScope(Dispatchers.IO)
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Serializable
    data class PlayerIdentity(
        val uuid: String,
        val did: String,
        val handle: String,
        val linkedAt: Long = System.currentTimeMillis(),
        val lastVerified: Long = System.currentTimeMillis(),
        // Legacy fields kept for migration deserialization only.
        // Sync consent is now managed by PlayerSyncPreferencesStore.
        val syncStats: Boolean = true,
        val syncSessions: Boolean = true,
    )

    @Serializable
    private data class IdentityStorage(
        val version: Int = 1,
        val identities: List<PlayerIdentity>
    )

    init {
        // Validate storage path is in expected directory
        val configDir = storageFile.parent
        if (!SecurityUtils.validatePathInDirectory(storageFile, configDir)) {
            throw SecurityException("Storage file path is outside expected directory")
        }
        
        load()
        logger.info("Player identity store initialized with ${identities.size} identities")
    }

    /**
     * Links a Minecraft player UUID to an AT Protocol DID.
     */
    fun linkIdentity(uuid: UUID, did: String, handle: String): PlayerIdentity {
        val identity = PlayerIdentity(
            uuid = uuid.toString(),
            did = did,
            handle = handle,
            linkedAt = System.currentTimeMillis(),
            lastVerified = System.currentTimeMillis()
        )
        
        identities[uuid] = identity
        save()
        publishIdentityToAtProtocol(uuid, did, handle, null)
        
        logger.info("Linked player $uuid to AT Protocol identity $handle ($did)")
        return identity
    }

    /**
     * Publish identity mapping to AT Protocol as a record in the server's repo.
     * Non-critical; local file is authoritative. On failure, only a warning is logged.
     */
    fun publishIdentityToAtProtocol(uuid: UUID, did: String, handle: String?, displayName: String?) {
        val rm = recordManager ?: return
        val sm = sessionManager ?: return
        atProtoScope.launch {
            try {
                val record = IdentityRecord(
                    playerUuid = uuid.toString(),
                    did = did,
                    handle = handle,
                    displayName = displayName,
                    linkedAt = Instant.now().toString(),
                )
                rm.putTypedRecord(
                    ServerAccount.SERVER_PLAYER_UUID,
                    "com.jollywhoppers.minecraft.identity",
                    uuid.toString(),
                    record,
                )
                logger.debug("Published identity for player $uuid to AT Protocol")
            } catch (e: Exception) {
                logger.warn("Failed to publish identity to AT Protocol for player $uuid: ${e.message}")
            }
        }
    }

    /**
     * Removes the link between a Minecraft player UUID and their AT Protocol DID.
     */
    fun unlinkIdentity(uuid: UUID): Boolean {
        val removed = identities.remove(uuid)
        if (removed != null) {
            save()
            logger.info("Unlinked player $uuid from AT Protocol identity ${removed.handle}")
            return true
        }
        return false
    }

    /**
     * Gets the AT Protocol identity for a Minecraft player UUID.
     */
    fun getIdentity(uuid: UUID): PlayerIdentity? {
        return identities[uuid]
    }

    /**
     * Gets the Minecraft UUID for an AT Protocol DID.
     */
    fun getUuidByDid(did: String): UUID? {
        return identities.entries
            .firstOrNull { it.value.did == did }
            ?.key
    }

    /**
     * Gets the Minecraft UUID for an AT Protocol handle.
     */
    fun getUuidByHandle(handle: String): UUID? {
        return identities.entries
            .firstOrNull { it.value.handle.equals(handle, ignoreCase = true) }
            ?.key
    }

    /**
     * Checks if a Minecraft player UUID is linked to an AT Protocol identity.
     */
    fun isLinked(uuid: UUID): Boolean {
        return identities.containsKey(uuid)
    }

    /**
     * Gets all linked identities.
     */
    fun getAllIdentities(): Map<UUID, PlayerIdentity> {
        return identities.toMap()
    }

    /**
     * Updates the last verified timestamp for a player's identity.
     */
    fun updateVerification(uuid: UUID) {
        identities[uuid]?.let { identity ->
            val updated = identity.copy(lastVerified = System.currentTimeMillis())
            identities[uuid] = updated
            save()
        }
    }

    /**
     * Extracts legacy sync consent values for migration to PlayerSyncPreferencesStore.
     * Returns a map of UUID -> Pair(syncStats, syncSessions).
     * After migration, these fields are no longer authoritative.
     */
    fun extractLegacySyncConsent(): Map<UUID, Pair<Boolean, Boolean>> {
        return identities.entries.associate { (uuid, identity) ->
            uuid to Pair(identity.syncStats, identity.syncSessions)
        }
    }

    /**
     * Clears legacy sync consent fields from all identities after migration.
     * Sets syncStats and syncSessions to their defaults (true) since
     * PlayerSyncPreferencesStore is now the source of truth.
     */
    fun clearLegacySyncConsent() {
        var changed = false
        identities.forEach { (uuid, identity) ->
            if (!identity.syncStats || !identity.syncSessions) {
                identities[uuid] = identity.copy(syncStats = true, syncSessions = true)
                changed = true
            }
        }
        if (changed) {
            save()
            logger.info("Cleared legacy sync consent fields after migration")
        }
    }

    /**
     * Load identities from AT Protocol backup into the in-memory store.
     * Only populates entries that don't already exist locally.
     * Called on server start to recover identities if the local file was lost.
     */
    suspend fun loadIdentitiesFromAtProtocol() {
        val rm = recordManager ?: return
        try {
            val result = rm.listAllRecords(ServerAccount.SERVER_PLAYER_UUID, "com.jollywhoppers.minecraft.identity")
            val records = result.getOrNull() ?: return
            var loaded = 0
            for (recordData in records) {
                try {
                    val identityRecord = json.decodeFromJsonElement<IdentityRecord>(recordData.value)
                    val uuid = UUID.fromString(identityRecord.playerUuid)
                    if (!identities.containsKey(uuid)) {
                        identities[uuid] = PlayerIdentity(
                            uuid = identityRecord.playerUuid,
                            did = identityRecord.did,
                            handle = identityRecord.handle ?: "",
                        )
                        loaded++
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to decode identity record from AT Protocol: ${e.message}")
                }
            }
            if (loaded > 0) {
                save()
                logger.info("Loaded $loaded identities from AT Protocol backup")
            }
        } catch (e: Exception) {
            logger.warn("Failed to load identities from AT Protocol: ${e.message}")
        }
    }

    /**
     * Loads identities from disk.
     */
    private fun load() = fileLock.read {
        try {
            if (Files.exists(storageFile)) {
                val content = Files.readString(storageFile)
                val storage = json.decodeFromString<IdentityStorage>(content)
                
                storage.identities.forEach { identity ->
                    val uuid = UUID.fromString(identity.uuid)
                    identities[uuid] = identity
                }
                
                logger.info("Loaded ${identities.size} player identities from disk")
            } else {
                logger.info("No existing identity storage found, starting fresh")
            }
        } catch (e: Exception) {
            logger.error("Failed to load player identities", e)
        }
    }

    /**
     * Saves identities to disk with proper file permissions.
     * Uses atomic write pattern to prevent corruption.
     */
    private fun save() = fileLock.write {
        try {
            Files.createDirectories(storageFile.parent)
            
            val storage = IdentityStorage(
                version = 1,
                identities = identities.values.toList()
            )
            
            val content = json.encodeToString(storage)
            
            // Atomic write: write to temp file, then rename
            val tempFile = storageFile.parent.resolve("${storageFile.fileName}.tmp")
            Files.writeString(
                tempFile,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            
            // Set restricted permissions on temp file
            SecurityUtils.setRestrictedPermissions(tempFile)
            
            // Atomic rename
            Files.move(tempFile, storageFile,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            
            logger.debug("Saved ${identities.size} player identities to disk")
        } catch (e: Exception) {
            logger.error("Failed to save player identities", e)
        }
    }
}
