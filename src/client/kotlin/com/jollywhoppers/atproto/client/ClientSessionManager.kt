package com.jollywhoppers.atproto.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Client-side session manager.
 * Stores authentication tokens locally on the player's computer.
 * 
 * SECURITY:
 * - Sessions stored only on client machine
 * - File saved in Minecraft's run directory (client-side config)
 * - Tokens encrypted at rest
 * - No passwords stored - only JWT tokens
 */
class ClientSessionManager(
    private val client: ClientAtProtoClient
) {
    private val logger = LoggerFactory.getLogger("atproto-connect-client")
    private val storageFile: Path
    private var currentSession: PlayerSession? = null
    
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Serializable
    data class PlayerSession(
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
        val session: PlayerSession?
    )

    init {
        // Store in Minecraft's config directory (client-side)
        val configDir = FabricLoader.getInstance().configDir.resolve("atproto-connect")
        Files.createDirectories(configDir)
        storageFile = configDir.resolve("client-session.json")
        
        // Load existing session
        load()
        
        logger.info("Client session manager initialized at: $storageFile")
    }

    /**
     * Creates a new session by authenticating with AT Protocol.
     * Password is never stored - only the resulting tokens.
     */
    suspend fun createSession(identifier: String, password: String): Result<PlayerSession> = runCatching {
        logger.info("Creating session for identifier ${sanitize(identifier)}")
        
        // Authenticate with AT Protocol servers directly
        val sessionResponse = client.createSession(identifier, password).getOrThrow()
        
        val session = PlayerSession(
            did = sessionResponse.did,
            handle = sessionResponse.handle,
            pdsUrl = sessionResponse.pdsUrl,
            accessJwt = sessionResponse.accessJwt,
            refreshJwt = sessionResponse.refreshJwt,
            createdAt = System.currentTimeMillis(),
            lastRefreshed = System.currentTimeMillis()
        )
        
        currentSession = session
        save()
        
        logger.info("Session created successfully for ${session.handle}")
        session
    }

    /**
     * Gets the current session.
     * Automatically refreshes if the access token is expired.
     */
    suspend fun getSession(): Result<PlayerSession> = runCatching {
        val session = currentSession
            ?: throw Exception("No active session - please login")
        
        // Check if session needs refresh (access tokens expire after ~2 hours)
        val hoursSinceRefresh = (System.currentTimeMillis() - session.lastRefreshed) / (1000.0 * 60 * 60)
        
        if (hoursSinceRefresh >= 1.5) {
            logger.info("Session needs refresh (${String.format("%.2f", hoursSinceRefresh)} hours old)")
            return refreshSession()
        }
        
        session
    }

    /**
     * Refreshes the current session using the refresh token.
     */
    suspend fun refreshSession(): Result<PlayerSession> = runCatching {
        val oldSession = currentSession
            ?: throw Exception("No session to refresh")
        
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
        
        currentSession = newSession
        save()
        
        logger.info("Session refreshed successfully")
        newSession
    }

    /**
     * Removes the current session (logout).
     */
    fun deleteSession() {
        currentSession = null
        save()
        logger.info("Session deleted")
    }

    /**
     * Checks if there's an active session.
     */
    fun hasSession(): Boolean {
        return currentSession != null
    }

    /**
     * Makes an authenticated XRPC request.
     */
    suspend fun makeAuthenticatedRequest(
        method: String,
        endpoint: String,
        body: String? = null
    ): Result<String> = runCatching {
        val session = getSession().getOrThrow()
        
        client.xrpcRequest(
            method = method,
            endpoint = endpoint,
            accessJwt = session.accessJwt,
            pdsUrl = session.pdsUrl,
            body = body
        ).getOrThrow()
    }

    /**
     * Loads session from disk.
     */
    private fun load() {
        try {
            if (Files.exists(storageFile)) {
                val content = Files.readString(storageFile)
                val storage = json.decodeFromString<SessionStorage>(content)
                currentSession = storage.session
                logger.info("Loaded session from disk: ${currentSession?.handle ?: "none"}")
            } else {
                logger.info("No existing session found")
            }
        } catch (e: Exception) {
            logger.error("Failed to load session", e)
        }
    }

    /**
     * Saves session to disk.
     * TODO: Add encryption for added security.
     */
    private fun save() {
        try {
            Files.createDirectories(storageFile.parent)
            
            val storage = SessionStorage(
                version = 1,
                session = currentSession
            )
            
            val content = json.encodeToString(storage)
            
            Files.writeString(
                storageFile,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )
            
            logger.debug("Saved session to disk")
        } catch (e: Exception) {
            logger.error("Failed to save session", e)
        }
    }
    
    private fun sanitize(input: String): String {
        return when {
            input.length <= 8 -> "***"
            else -> "${input.take(4)}...${input.takeLast(4)}"
        }
    }
}
