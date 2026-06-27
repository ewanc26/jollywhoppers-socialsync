package com.jollywhoppers.atproto.client

import com.jollywhoppers.atproto.oauth.DpopProof
import com.jollywhoppers.atproto.oauth.OAuthSession
import com.jollywhoppers.config.PreferencesManager
import io.github.kikin81.atproto.oauth.DpopAuthProvider
import io.github.kikin81.atproto.oauth.DpopSigner
import io.github.kikin81.atproto.oauth.OAuthSessionStore
import io.github.kikin81.atproto.runtime.XrpcClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.KeyPair

class ClientSessionManager(
    private val client: ClientAtProtoClient
) {
    private val logger = LoggerFactory.getLogger("atproto-connect-client")
    private val storageFile: Path
    private val keyFile: Path
    private var encryptionKey: javax.crypto.SecretKey
    private var currentSession: PlayerSession? = null
    private var currentOAuthSession: OAuthSession? = null
    private var dpopKeyPair: KeyPair? = null
    private var cachedXrpcClient: XrpcClient? = null
    private var ktorClient: HttpClient? = null

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val oauthStore: ClientOAuthSessionStore

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
        val version: Int = 3,
        val session: PlayerSession? = null,
        val encrypted: Boolean = false,
    )

    init {
        val configDir = FabricLoader.getInstance().configDir.resolve("atproto-connect")
        Files.createDirectories(configDir)
        storageFile = configDir.resolve("client-session.json")
        keyFile = configDir.resolve(".session-key")

        encryptionKey = ClientSecurityUtils.loadOrGenerateKey(keyFile)

        oauthStore = ClientOAuthSessionStore(configDir)

        load()
        logger.info("Client session manager initialized at: $storageFile")
    }

    suspend fun createSession(identifier: String, password: String): Result<PlayerSession> = runCatching {
        logger.info("Creating session for identifier ${sanitize(identifier)}")

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
        cachedXrpcClient = null
        runBlocking { oauthStore.clear() }
        save()

        logger.info("Session created successfully for ${session.handle}")
        session
    }

    fun storeOAuthSession(oauthSession: OAuthSession, keyPair: KeyPair) {
        currentOAuthSession = oauthSession
        dpopKeyPair = keyPair
        cachedXrpcClient = null

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

        val libSession = oauthSession.toLibrarySession()
        runBlocking { oauthStore.save(libSession) }
        save()

        logger.info("OAuth session stored for ${oauthSession.handle}")
    }

    suspend fun getSession(): Result<PlayerSession> = runCatching {
        val session = currentSession
            ?: throw Exception("No active session - please login")

        val hoursSinceRefresh = (System.currentTimeMillis() - session.lastRefreshed) / (1000.0 * 60 * 60)

        if (hoursSinceRefresh >= 1.5) {
            logger.info("Session needs refresh (${String.format("%.2f", hoursSinceRefresh)} hours old)")
            return refreshSession()
        }

        session
    }

    fun getOAuthSession(): OAuthSession? = currentOAuthSession

    fun getDpopKeyPair(): KeyPair? = dpopKeyPair

    suspend fun getXrpcClient(): XrpcClient? {
        val session = currentOAuthSession ?: return null
        val keyPair = dpopKeyPair ?: return null

        if (cachedXrpcClient != null) return cachedXrpcClient

        try {
            val exportedKeyPair = DpopSigner.ExportedKeyPair(
                privateKeyEncoded = keyPair.private.encoded,
                publicKeyEncoded = keyPair.public.encoded,
            )
            val libSession = session.toLibrarySession()
            val signer = DpopSigner.fromExported(exportedKeyPair)

            if (ktorClient == null) {
                ktorClient = HttpClient(CIO) {
                    install(HttpTimeout) {
                        requestTimeoutMillis = 15_000
                    }
                }
            }

            val authProvider = DpopAuthProvider(
                session = libSession,
                signer = signer,
                sessionStore = oauthStore,
                refreshClient = ktorClient!!,
                json = json,
            )

            cachedXrpcClient = XrpcClient(
                baseUrl = session.pdsUrl,
                httpClient = ktorClient!!,
                json = json,
                authProvider = authProvider,
            )
        } catch (e: Exception) {
            logger.error("Failed to create XrpcClient", e)
            return null
        }

        return cachedXrpcClient
    }

    suspend fun refreshSession(): Result<PlayerSession> = runCatching {
        val oldSession = currentSession
            ?: throw Exception("No session to refresh")

        logger.info("Refreshing session for ${oldSession.handle}")

        if (oldSession.authType == "oauth" && currentOAuthSession != null) {
            val xrpcClient = getXrpcClient()
            if (xrpcClient != null) {
                val libSession = runBlocking { oauthStore.load() }
                if (libSession != null) {
                    val updated = currentOAuthSession!!.copy(
                        accessToken = libSession.accessToken,
                        refreshToken = libSession.refreshToken,
                        lastRefreshed = System.currentTimeMillis(),
                    )
                    currentOAuthSession = updated
                    currentSession = oldSession.copy(
                        accessJwt = libSession.accessToken,
                        refreshJwt = libSession.refreshToken,
                        lastRefreshed = System.currentTimeMillis(),
                    )
                    save()
                    return@runCatching currentSession!!
                }
            }
        }

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

    fun deleteSession() {
        currentSession = null
        currentOAuthSession = null
        dpopKeyPair = null
        cachedXrpcClient = null

        runBlocking { oauthStore.clear() }

        if (PreferencesManager.get().clearLocalCacheOnLogout) {
            try {
                Files.deleteIfExists(storageFile)
                Files.deleteIfExists(keyFile)
                logger.info("Session and encryption key deleted from disk")
            } catch (e: Exception) {
                logger.warn("Failed to delete session files: ${e.message}")
            }
        } else {
            save()
        }

        logger.info("Session deleted")
    }

    fun hasSession(): Boolean = currentSession != null

    fun isOAuthSession(): Boolean = currentSession?.authType == "oauth"

    suspend fun makeAuthenticatedRequest(
        method: String,
        endpoint: String,
        body: String? = null
    ): Result<String> = runCatching {
        val session = getSession().getOrThrow()

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

    private fun load() {
        try {
            if (Files.exists(storageFile)) {
                val content = Files.readString(storageFile)

                val storage = try {
                    val decrypted = ClientSecurityUtils.decrypt(content, encryptionKey)
                    json.decodeFromString<SessionStorage>(decrypted)
                } catch (_: Exception) {
                    json.decodeFromString<SessionStorage>(content)
                }

                currentSession = storage.session
                logger.info("Loaded session from disk: ${currentSession?.handle ?: "none"} (auth: ${currentSession?.authType ?: "none"}")

                if (!storage.encrypted && currentSession != null) {
                    logger.info("Migrating plaintext session to encrypted storage")
                    save()
                }
            } else {
                logger.info("No existing session found")
            }
        } catch (e: Exception) {
            logger.error("Failed to load session", e)
        }
    }

    private fun save() {
        try {
            Files.createDirectories(storageFile.parent)

            val useEncryption = PreferencesManager.get().encryptedLocalStorage

            val storage = SessionStorage(
                version = 3,
                session = currentSession,
                encrypted = useEncryption,
            )

            val plaintext = json.encodeToString(storage)

            val content = if (useEncryption) {
                ClientSecurityUtils.encrypt(plaintext, encryptionKey)
            } else {
                plaintext
            }

            Files.writeString(
                storageFile,
                content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            )

            try {
                storageFile.toFile().setReadable(true, true)
                storageFile.toFile().setWritable(true, true)
            } catch (_: Exception) {
            }

            logger.debug("Saved session to disk (encrypted: $useEncryption)")
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

    private inner class ClientOAuthSessionStore(
        private val storageDir: Path,
    ) : OAuthSessionStore {
        private val sessionFile: Path = storageDir.resolve("oauth-session.json")

        override suspend fun load(): io.github.kikin81.atproto.oauth.OAuthSession? {
            return try {
                if (Files.exists(sessionFile)) {
                    val content = Files.readString(sessionFile)
                    json.decodeFromString<io.github.kikin81.atproto.oauth.OAuthSession>(content)
                } else null
            } catch (e: Exception) {
                logger.warn("Failed to load OAuth session", e)
                null
            }
        }

        override suspend fun save(session: io.github.kikin81.atproto.oauth.OAuthSession) {
            try {
                Files.createDirectories(sessionFile.parent)
                Files.writeString(
                    sessionFile,
                    json.encodeToString(session),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                )
                logger.debug("OAuth session saved to disk")
            } catch (e: Exception) {
                logger.error("Failed to save OAuth session", e)
            }
        }

        override suspend fun clear() {
            try {
                Files.deleteIfExists(sessionFile)
                logger.debug("OAuth session cleared from disk")
            } catch (e: Exception) {
                logger.warn("Failed to clear OAuth session", e)
            }
        }
    }

    private fun OAuthSession.toLibrarySession(): io.github.kikin81.atproto.oauth.OAuthSession {
        return io.github.kikin81.atproto.oauth.OAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            did = did,
            handle = handle,
            pdsUrl = pdsUrl,
            tokenEndpoint = tokenEndpoint,
            revocationEndpoint = null,
            clientId = clientId,
            dpopPrivateKey = dpopPrivateKeyEncoded,
            dpopPublicKey = dpopPublicKeyEncoded,
            authServerNonce = authServerNonce,
            pdsNonce = pdsNonce,
        )
    }
}
