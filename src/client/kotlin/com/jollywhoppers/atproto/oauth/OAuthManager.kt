package com.jollywhoppers.atproto.oauth

import io.github.kikin81.atproto.oauth.DiscoveryChain
import io.github.kikin81.atproto.oauth.DpopSigner
import io.github.kikin81.atproto.oauth.OAuthSessionStore
import io.github.kikin81.atproto.runtime.XrpcClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.UUID

class OAuthManager @JvmOverloads constructor(
    @Deprecated("No longer used - DiscoveryChain replaces ClientAtProtoClient for identity resolution")
    @Suppress("UNUSED_PARAMETER")
    unused: Any? = null,
) {
    private val logger = LoggerFactory.getLogger("atproto-connect:oauth")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private val ktorClient = HttpClient(CIO) {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
        }
    }

    private val httpClient = JdkHttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(JdkHttpClient.Redirect.NEVER)
        .build()

    data class OAuthResult(
        val session: OAuthSession,
        val dpopKeyPair: KeyPair,
        val exportedKeyPair: DpopSigner.ExportedKeyPair,
    )

    suspend fun authorize(
        identifier: String,
        scope: String = "atproto",
        timeoutSeconds: Long = 300,
    ): Result<OAuthResult> = runCatching {
        logger.info("Starting OAuth flow for: $identifier")

        val discovery = DiscoveryChain(httpClient = ktorClient, json = json)
        val authMetadata = discovery.resolve(identifier)
        logger.info("Resolved: ${authMetadata.handle} (${authMetadata.did}) at PDS: ${authMetadata.pdsUrl}")

        val codeVerifier = PkceUtils.generateCodeVerifier()
        val codeChallenge = PkceUtils.generateCodeChallenge(codeVerifier)

        val dpopSigner = DpopSigner.generate()
        var authServerNonce: String? = null

        val callbackServer = OAuthCallbackServer()
        val port = callbackServer.start()
        val redirectUri = callbackServer.getRedirectUri()
        logger.info("Callback server started at $redirectUri")

        val clientId = buildLocalhostClientId(redirectUri, scope)

        val state = UUID.randomUUID().toString()

        val parResult = submitPar(
            parEndpoint = authMetadata.parEndpoint,
            clientId = clientId,
            redirectUri = redirectUri,
            scope = scope,
            state = state,
            codeChallenge = codeChallenge,
            loginHint = identifier,
            dpopSigner = dpopSigner,
            initialNonce = authServerNonce,
        )
        authServerNonce = parResult.dpopNonce
        logger.info("PAR submitted successfully")

        val authUrl = buildAuthorizationUrl(
            authorizationEndpoint = authMetadata.authorizationEndpoint,
            clientId = clientId,
            requestUri = parResult.parResponse.requestUri,
        )

        logger.info("Opening browser for authorization")
        openBrowser(authUrl)

        val code = callbackServer.awaitCode(timeoutSeconds)
            ?: throw IllegalStateException("OAuth callback timed out or failed")
        logger.info("Received authorization code from callback")

        val tokenResponse = exchangeCodeForTokens(
            tokenEndpoint = authMetadata.tokenEndpoint,
            code = code,
            redirectUri = redirectUri,
            codeVerifier = codeVerifier,
            clientId = clientId,
            dpopSigner = dpopSigner,
            nonce = authServerNonce,
        )
        logger.info("Token exchange successful")

        val returnedDid = tokenResponse.sub
        val expectedDid = authMetadata.did
        if (returnedDid != null && expectedDid != null && returnedDid != expectedDid) {
            throw SecurityException("DID mismatch: expected $expectedDid, got $returnedDid")
        }
        logger.info("DID verified: ${expectedDid ?: returnedDid}")

        val exportedKeyPair = dpopSigner.exportKeyPair()
        val keyFactory = KeyFactory.getInstance("EC")
        val dpopKeyPair = KeyPair(
            keyFactory.generatePublic(java.security.spec.X509EncodedKeySpec(exportedKeyPair.publicKeyEncoded)),
            keyFactory.generatePrivate(PKCS8EncodedKeySpec(exportedKeyPair.privateKeyEncoded)),
        )

        val session = OAuthSession(
            did = authMetadata.did ?: returnedDid ?: "",
            handle = authMetadata.handle ?: identifier,
            pdsUrl = authMetadata.pdsUrl ?: "",
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken ?: throw IllegalStateException("No refresh token"),
            authServerIssuer = authMetadata.issuer,
            tokenEndpoint = authMetadata.tokenEndpoint,
            scope = scope,
            clientId = clientId,
            dpopPrivateKeyEncoded = exportedKeyPair.privateKeyEncoded,
            dpopPublicKeyEncoded = exportedKeyPair.publicKeyEncoded,
        )

        callbackServer.stop()
        logger.info("OAuth flow completed for ${session.handle}")

        OAuthResult(
            session = session,
            dpopKeyPair = dpopKeyPair,
            exportedKeyPair = exportedKeyPair,
        )
    }

    fun createClient(
        session: OAuthSession,
        exportedKeyPair: DpopSigner.ExportedKeyPair,
        sessionStore: OAuthSessionStore,
    ): XrpcClient {
        val libSession = session.toLibrarySession()
        val signer = DpopSigner.fromExported(exportedKeyPair)
        val authProvider = io.github.kikin81.atproto.oauth.DpopAuthProvider(
            session = libSession,
            signer = signer,
            sessionStore = sessionStore,
            refreshClient = ktorClient,
            json = json,
        )
        return XrpcClient(
            baseUrl = session.pdsUrl,
            httpClient = ktorClient,
            json = json,
            authProvider = authProvider,
        )
    }

    private data class SubmitParResult(
        val parResponse: ParResponse,
        val dpopNonce: String?,
    )

    private fun submitPar(
        parEndpoint: String,
        clientId: String,
        redirectUri: String,
        scope: String,
        state: String,
        codeChallenge: String,
        loginHint: String?,
        dpopSigner: DpopSigner,
        initialNonce: String?,
    ): SubmitParResult {
        val dpopProof = dpopSigner.sign(method = "POST", url = parEndpoint, nonce = initialNonce)
        val formBody = buildFormBody(mapOf(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "scope" to scope,
            "state" to state,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to PkceUtils.getChallengeMethod(),
            "login_hint" to (loginHint ?: ""),
        ))

        val request = HttpRequest.newBuilder()
            .uri(URI.create(parEndpoint))
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("DPoP", dpopProof)
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(request, BodyHandlers.ofString())

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            val dpopNonce = response.headers().firstValue("DPoP-Nonce").orElse(null)
            if (dpopNonce != null) {
                logger.info("Received DPoP nonce from PAR endpoint, retrying")
                val retryProof = dpopSigner.sign(method = "POST", url = parEndpoint, nonce = dpopNonce)
                val retryForm = buildFormBody(mapOf(
                    "response_type" to "code",
                    "client_id" to clientId,
                    "redirect_uri" to redirectUri,
                    "scope" to scope,
                    "state" to state,
                    "code_challenge" to codeChallenge,
                    "code_challenge_method" to PkceUtils.getChallengeMethod(),
                    "login_hint" to (loginHint ?: ""),
                ))
                val retryRequest = HttpRequest.newBuilder()
                    .uri(URI.create(parEndpoint))
                    .POST(HttpRequest.BodyPublishers.ofString(retryForm))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("DPoP", retryProof)
                    .timeout(Duration.ofSeconds(15))
                    .build()
                val retryResponse = httpClient.send(retryRequest, BodyHandlers.ofString())
                if (retryResponse.statusCode() != 200 && retryResponse.statusCode() != 201) {
                    throw IllegalStateException("PAR retry failed: HTTP ${retryResponse.statusCode()}: ${retryResponse.body()}")
                }
                val parResponse = json.decodeFromString<ParResponse>(retryResponse.body())
                return SubmitParResult(parResponse = parResponse, dpopNonce = dpopNonce)
            }
            throw IllegalStateException("PAR failed: HTTP ${response.statusCode()}: ${response.body()}")
        }

        val parResponse = json.decodeFromString<ParResponse>(response.body())
        return SubmitParResult(parResponse = parResponse, dpopNonce = null)
    }

    private fun exchangeCodeForTokens(
        tokenEndpoint: String,
        code: String,
        redirectUri: String,
        codeVerifier: String,
        clientId: String,
        dpopSigner: DpopSigner,
        nonce: String?,
    ): TokenResponse {
        val dpopProof = dpopSigner.sign(method = "POST", url = tokenEndpoint, nonce = nonce)
        val formBody = buildFormBody(mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "code_verifier" to codeVerifier,
            "client_id" to clientId,
        ))

        val request = HttpRequest.newBuilder()
            .uri(URI.create(tokenEndpoint))
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("DPoP", dpopProof)
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(request, BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw IllegalStateException("Token exchange failed: HTTP ${response.statusCode()}: ${response.body()}")
        }

        return json.decodeFromString<TokenResponse>(response.body())
    }

    private fun buildAuthorizationUrl(
        authorizationEndpoint: String,
        clientId: String,
        requestUri: String,
    ): String {
        val params = buildString {
            append("client_id=${encodeURIComponent(clientId)}")
            append("&request_uri=${encodeURIComponent(requestUri)}")
        }
        return "$authorizationEndpoint?$params"
    }

    private fun buildLocalhostClientId(redirectUri: String, scope: String): String {
        return "http://localhost?redirect_uri=${encodeURIComponent(redirectUri)}&scope=${encodeURIComponent(scope)}"
    }

    private fun openBrowser(url: String) {
        try {
            val osName = System.getProperty("os.name").lowercase()
            when {
                osName.contains("mac") -> Runtime.getRuntime().exec(arrayOf("open", url))
                osName.contains("win") -> Runtime.getRuntime().exec(arrayOf("rundll32", "url.dll,FileProtocolHandler", url))
                else -> Runtime.getRuntime().exec(arrayOf("xdg-open", url))
            }
            logger.info("Opened browser at: $url")
        } catch (e: Exception) {
            logger.error("Failed to open browser", e)
            throw IllegalStateException("Could not open browser. Please open this URL manually: $url")
        }
    }

    private fun encodeURIComponent(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8)
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")
    }

    private fun buildFormBody(params: Map<String, String>): String {
        return params.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { (key, value) ->
                "${encodeURIComponent(key)}=${encodeURIComponent(value)}"
            }
    }
}

internal fun OAuthSession.toLibrarySession(): io.github.kikin81.atproto.oauth.OAuthSession {
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
