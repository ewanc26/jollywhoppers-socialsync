package com.jollywhoppers.atproto.client

import com.jollywhoppers.atproto.oauth.OAuthSession
import com.jollywhoppers.atproto.oauth.DpopProof
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Client-side session manager.
 * Stores authentication tokens locally on the player's computer.
 * Supports both app-password sessions and OAuth sessions.
 *
 * SECURITY:
 * - Sessions stored only on client machine
 * - File saved in Minecraft's run directory (client-side config)
 * - No passwords stored - only JWT tokens
 * - OAuth sessions include DPoP key material for authenticated requests
 */
class ClientSessionManager(
    private val client: ClientAtProtoClient
) {
    private val logger = LoggerFactory.getLogger("atproto-connect-client")
    private val storageFile: Path
    private var currentSession: PlayerSession? = null
    private var currentOAuthSession: OAuthSession? = null
    private var dpopKeyPair: KeyPair? = null

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
        val lastRefreshed: Long = System.currentTimeMillis(),
        val authType: String = "app_password",
    )

    @Serializable
    private data class SessionStorage(
        val version: Int = 2,
        val session: PlayerSession? = null,
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
     * Creates a new session by authenticating with AT Protocol using an app password.
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
            lastRefreshed = System.currentTimeMillis(),
            authType = "app_password",
        )

        currentSession = session
        currentOAuthSession = null
        dpopKeyPair = null
        save()

        logger.info("Session created successfully for ${session.handle}")
        session
    }

    /**
     * Stores an OAuth session from a completed OAuth flow.
     * Reconstructs the DPoP key pair from the stored encoded keys.
     */
    fun storeOAuthSession(oauthSession: OAuthSession, keyPair: KeyPair) {
        currentOAuthSession = oauthSession
        dpopKeyPair = keyPair

        // Also store as a PlayerSession for compatibility with existing server-side code
        currentSession = PlayerSession(
            did = oauthSession.did,
            handle = oauthSession.handle,
            pdsUrl = oauthSession.pdsUrl,
            accessJwt = oauthSession.accessToken,
            refreshJwt = oauthSession.refreshToken,
            createdAt = oauthSession.createdAt,
            lastRefreshed = oauthSession.lastRefreshed,
            authType = "oauth",
        )

        save()
        logger.info("OAuth session stored for ${oauthSession.handle}")
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
     * Gets the current OAuth session, if any.
     */
    fun getOAuthSession(): OAuthSession? = currentOAuthSession

    /**
     * Gets the DPoP key pair for the current session.
     */
    fun getDpopKeyPair(): KeyPair? = dpopKeyPair

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
        currentOAuthSession = null
        dpopKeyPair = null
        save()
        logger.info("Session deleted")
    }

    /**
     * Checks if there's an active session.
     */
    fun hasSession(): Boolean = currentSession != null

    /**
     * Checks if the current session is an OAuth session.
     */
    fun isOAuthSession(): Boolean = currentSession?.authType == "oauth"

    /**
     * Makes an authenticated XRPC request.
     * For OAuth sessions, includes DPoP proof header.
     */
    suspend fun makeAuthenticatedRequest(
        method: String,
        endpoint: String,
        body: String? = null
    ): Result<String> = runCatching {
        val session = getSession().getOrThrow()

        // For OAuth sessions, include DPoP proof
        val dpopHeader = if (session.authType == "oauth" && dpopKeyPair != null && currentOAuthSession != null) {
            val oauth = currentOAuthSession!!
            val url = "${oauth.pdsUrl}/xrpc/$endpoint"
            DpopProof.buildProof(
                keyPair = dpopKeyPair!!,
                url = url,
                method = method,
                nonce = oauth.pdsNonce,
                accessToken = session.accessJwt,
            )
        } else null

        if (dpopHeader != null) {
            // Use DPoP-aware request
            client.xrpcRequestWithDpop(
                method = method,
                endpoint = endpoint,
                accessJwt = session.accessJwt,
                pdsUrl = session.pdsUrl,
                body = body,
                dpopProof = dpopHeader,
            ).getOrThrow()
        } else {
            client.xrpcRequest(
                method = method,
                endpoint = endpoint,
                accessJwt = session.accessJwt,
                pdsUrl = session.pdsUrl,
                body = body,
            ).getOrThrow()
        }
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
                logger.info("Loaded session from disk: ${currentSession?.handle ?: "none"} (auth: ${currentSession?.authType ?: "none"})")
            } else {
                logger.info("No existing session found")
            }
        } catch (e: Exception) {
            logger.error("Failed to load session", e)
        }
    }

    /**
     * Saves session to disk.
     * TODO: Add encryption for added security (server-side already has it).
     */
    private fun save() {
        try {
            Files.createDirectories(storageFile.parent)

            val storage = SessionStorage(
                version = 2,
                session = currentSession,
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
