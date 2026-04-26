package com.jollywhoppers.atproto.server

import com.jollywhoppers.security.SecurityUtils
import com.jollywhoppers.security.SecurityAuditor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.crypto.SecretKey
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Manages AT Protocol authentication sessions for players.
 * Handles token storage with encryption, refresh, and session lifecycle.
 * 
 * SECURITY FEATURES:
 * - AES-256-GCM encryption for session data at rest
 * - Restricted file permissions (owner-only)
 * - Atomic file writes to prevent corruption
 * - Thread-safe session management
 * - Automatic token refresh
 */
class AtProtoSessionManager(
    private val storageFile: Path,
    private val client: AtProtoClient
) {
    private val logger = LoggerFactory.getLogger("atproto-connect")
    private val sessions = ConcurrentHashMap<UUID, PlayerSession>()
    private val encryptionKey: SecretKey
    private val fileLock = ReentrantReadWriteLock()
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Serializable
    data class PlayerSession(
        val uuid: String,
        val did: String,
        val handle: String,
        val pdsUrl: String,
        val accessJwt: String,
        val refreshJwt: String,
        val createdAt: Long = System.currentTimeMillis(),
        val lastRefreshed: Long = System.currentTimeMillis(),
        val authType: String = "app_password",
    )

    @Serializable
    private data class SessionStorage(
        val version: Int = 2, // Version 2 = encrypted
        val sessions: List<PlayerSession>
    )

    init {
        // Validate storage path is in expected directory
        val configDir = storageFile.parent
        if (!SecurityUtils.validatePathInDirectory(storageFile, configDir)) {
            throw SecurityException("Storage file path is outside expected directory")
        }
        
        // Load or generate encryption key
        val keyFile = configDir.resolve(".encryption.key")
        encryptionKey = SecurityUtils.loadOrGenerateServerKey(keyFile)
        
        // Load existing sessions
        load()
        
        logger.info("Session manager initialized with encryption enabled")
    }

    /**
     * Stores a verified session that was authenticated client-side.
     * This is the preferred method for storing sessions.
     * @param authType "oauth" or "app_password" (default: "app_password")
     */
    fun storeVerifiedSession(
        uuid: UUID,
        did: String,
        handle: String,
        pdsUrl: String,
        accessJwt: String,
        refreshJwt: String,
        authType: String = "app_password",
    ) {
        logger.info("Storing verified session for player $uuid (${handle}) via $authType")

        val session = PlayerSession(
            uuid = uuid.toString(),
            did = did,
            handle = handle,
            pdsUrl = pdsUrl,
            accessJwt = accessJwt,
            refreshJwt = refreshJwt,
            createdAt = System.currentTimeMillis(),
            lastRefreshed = System.currentTimeMillis(),
            authType = authType,
        )
        
        sessions[uuid] = session
        save()
        
        logger.info("Verified session stored successfully for $handle")
    }

    /**
     * Gets the active session for a player.
     * Automatically refreshes if the access token is expired.
     */
    suspend fun getSession(uuid: UUID): Result<PlayerSession> = runCatching {
        val session = sessions[uuid]
            ?: throw Exception("No session found for player")
        
        // Check if session needs refresh (access tokens typically expire after 2 hours)
        // We'll refresh if it's been more than 1.5 hours to be safe
        val hoursSinceRefresh = (System.currentTimeMillis() - session.lastRefreshed) / (1000.0 * 60 * 60)
        
        if (hoursSinceRefresh >= 1.5) {
            logger.info("Session for ${session.handle} needs refresh (${String.format("%.2f", hoursSinceRefresh)} hours old)")
            return refreshSession(uuid)
        }
        
        session
    }

    /**
     * Refreshes a player's session using their refresh token.
     */
    suspend fun refreshSession(uuid: UUID): Result<PlayerSession> = runCatching {
        val oldSession = sessions[uuid]
            ?: throw Exception("No session found for player")
        
        logger.info("Refreshing session for ${oldSession.handle}")
        
        val refreshResponse = client.refreshSession(
            oldSession.refreshJwt,
            oldSession.pdsUrl
        ).getOrThrow()
        
        val newSession = oldSession.copy(
            accessJwt = refreshResponse.accessJwt,
            refreshJwt = refreshResponse.refreshJwt,
            lastRefreshed = System.currentTimeMillis()
        )
        
        sessions[uuid] = newSession
        save()
        
        SecurityAuditor.logSessionRefresh(uuid, oldSession.handle)
        logger.info("Session refreshed successfully for ${oldSession.handle}")
        newSession
    }

    /**
     * Removes a player's session (logout).
     */
    fun deleteSession(uuid: UUID): Boolean {
        val removed = sessions.remove(uuid)
        if (removed != null) {
            save()
            logger.info("Session deleted for ${removed.handle}")
            return true
        }
        return false
    }

    /**
     * Checks if a player has an active session.
     */
    fun hasSession(uuid: UUID): Boolean {
        return sessions.containsKey(uuid)
    }

    /**
     * Gets all active sessions.
     */
    fun getAllSessions(): Map<UUID, PlayerSession> {
        return sessions.toMap()
    }

    /**
     * Makes an authenticated XRPC request for a player.
     * Automatically refreshes the session if needed.
     */
    suspend fun makeAuthenticatedRequest(
        uuid: UUID,
        method: String,
        endpoint: String,
        body: String? = null
    ): Result<String> = runCatching {
        val session = getSession(uuid).getOrThrow()
        
        client.xrpcRequest(
            method = method,
            endpoint = endpoint,
            accessJwt = session.accessJwt,
            pdsUrl = session.pdsUrl,
            body = body
        ).getOrThrow()
    }

    /**
     * Loads sessions from disk with decryption.
     * Supports both encrypted (v2) and legacy plain text (v1) formats.
     */
    private fun load() = fileLock.read {
        try {
            if (Files.exists(storageFile)) {
                val fileContent = Files.readString(storageFile)
                
                // Try to decrypt first (new format)
                val content = try {
                    SecurityUtils.decrypt(fileContent, encryptionKey)
                } catch (e: Exception) {
                    // If decryption fails, try parsing as plain JSON (legacy format)
                    logger.warn("Failed to decrypt sessions, attempting plain text read (legacy format)")
                    fileContent
                }
                
                val storage = json.decodeFromString<SessionStorage>(content)
                
                storage.sessions.forEach { session ->
                    val uuid = UUID.fromString(session.uuid)
                    sessions[uuid] = session
                }
                
                logger.info("Loaded ${sessions.size} sessions from disk (encrypted: ${storage.version >= 2})")
                
                // If we loaded legacy format, save in new encrypted format
                if (storage.version < 2) {
                    logger.info("Migrating sessions to encrypted format")
                    save()
                }
            } else {
                logger.info("No existing session storage found, starting fresh")
            }
        } catch (e: Exception) {
            logger.error("Failed to load sessions", e)
        }
    }

    /**
     * Saves sessions to disk with encryption and proper file permissions.
     * Uses atomic write pattern to prevent corruption.
     */
    private fun save() = fileLock.write {
        try {
            Files.createDirectories(storageFile.parent)
            
            val storage = SessionStorage(
                version = 2, // Version 2 = encrypted
                sessions = sessions.values.toList()
            )
            
            // Serialize to JSON
            val content = json.encodeToString(storage)
            
            // Encrypt the entire content
            val encrypted = SecurityUtils.encrypt(content, encryptionKey)
            
            // Atomic write: write to temp file, then rename
            val tempFile = storageFile.parent.resolve("${storageFile.fileName}.tmp")
            Files.writeString(
                tempFile,
                encrypted,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            
            // Set restricted permissions on temp file
            SecurityUtils.setRestrictedPermissions(tempFile)
            
            // Atomic rename
            Files.move(tempFile, storageFile, 
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            
            logger.debug("Saved ${sessions.size} encrypted sessions to disk")
        } catch (e: Exception) {
            logger.error("Failed to save sessions", e)
        }
    }
}
