package com.jollywhoppers.atproto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages AT Protocol authentication sessions for players.
 * Handles token storage, refresh, and session lifecycle.
 */
class AtProtoSessionManager(
    private val storageFile: Path,
    private val client: AtProtoClient
) {
    private val logger = LoggerFactory.getLogger("atproto-connect")
    private val sessions = ConcurrentHashMap<UUID, PlayerSession>()
    
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
        val lastRefreshed: Long = System.currentTimeMillis()
    )

    @Serializable
    private data class SessionStorage(
        val version: Int = 1,
        val sessions: List<PlayerSession>
    )

    init {
        load()
    }

    /**
     * Creates or updates a session for a player.
     */
    suspend fun createSession(
        uuid: UUID,
        identifier: String,
        password: String
    ): Result<PlayerSession> = runCatching {
        logger.info("Creating session for player $uuid with identifier $identifier")
        
        // Create the session via AT Protocol
        val sessionResponse = client.createSession(identifier, password).getOrThrow()
        
        // Resolve to get PDS URL
        val (did, handle, pdsUrl) = client.resolveIdentifier(sessionResponse.did).getOrThrow()
        
        val session = PlayerSession(
            uuid = uuid.toString(),
            did = did,
            handle = handle,
            pdsUrl = pdsUrl,
            accessJwt = sessionResponse.accessJwt,
            refreshJwt = sessionResponse.refreshJwt,
            createdAt = System.currentTimeMillis(),
            lastRefreshed = System.currentTimeMillis()
        )
        
        sessions[uuid] = session
        save()
        
        logger.info("Session created successfully for $handle")
        session
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
        val hoursSinceRefresh = (System.currentTimeMillis() - session.lastRefreshed) / (1000 * 60 * 60)
        
        if (hoursSinceRefresh >= 1.5) {
            logger.info("Session for ${session.handle} needs refresh")
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
     * Loads sessions from disk.
     */
    private fun load() {
        try {
            if (Files.exists(storageFile)) {
                val content = Files.readString(storageFile)
                val storage = json.decodeFromString<SessionStorage>(content)
                
                storage.sessions.forEach { session ->
                    val uuid = UUID.fromString(session.uuid)
                    sessions[uuid] = session
                }
                
                logger.info("Loaded ${sessions.size} sessions from disk")
            } else {
                logger.info("No existing session storage found, starting fresh")
            }
        } catch (e: Exception) {
            logger.error("Failed to load sessions", e)
        }
    }

    /**
     * Saves sessions to disk.
     */
    private fun save() {
        try {
            Files.createDirectories(storageFile.parent)
            
            val storage = SessionStorage(
                version = 1,
                sessions = sessions.values.toList()
            )
            
            val content = json.encodeToString(storage)
            Files.writeString(
                storageFile,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            
            logger.debug("Saved ${sessions.size} sessions to disk")
        } catch (e: Exception) {
            logger.error("Failed to save sessions", e)
        }
    }
}
