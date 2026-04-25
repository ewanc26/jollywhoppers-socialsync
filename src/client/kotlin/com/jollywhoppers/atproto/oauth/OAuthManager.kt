package com.jollywhoppers.atproto.oauth

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.time.Duration
import java.util.UUID

/**
 * Manages the ATProto OAuth authorization flow for client-side login.
 *
 * Flow overview:
 * 1. Resolve the user's handle/DID to find their PDS
 * 2. Fetch PDS resource server metadata to discover the authorization server
 * 3. Fetch authorization server metadata to discover OAuth endpoints
 * 4. Generate PKCE code verifier + challenge
 * 5. Generate DPoP key pair for the session
 * 6. Submit a Pushed Authorization Request (PAR) with DPoP proof
 * 7. Open the browser at the authorization endpoint with the PAR request_uri
 * 8. Start a localhost callback server to capture the redirect
 * 9. Exchange the authorization code for tokens (with PKCE verifier + DPoP proof)
 * 10. Verify the returned DID matches the expected identity
 * 11. Store the OAuth session
 */
class OAuthManager {
    private val logger = LoggerFactory.getLogger("atproto-connect:oauth")
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /**
     * Result of a successful OAuth flow.
     */
    data class OAuthResult(
        val session: OAuthSession,
        val dpopKeyPair: KeyPair,
    )

    /**
     * Runs the full OAuth authorization flow for a given handle or DID.
     *
     * @param identifier The user's ATProto handle (e.g. alice.bsky.social) or DID
     * @param scope The OAuth scopes to request (default: atproto)
     * @param timeoutSeconds How long to wait for the browser callback
     * @return The OAuth result containing the session and DPoP key pair
     */
    suspend fun authorize(
        identifier: String,
        scope: String = "atproto",
        timeoutSeconds: Long = 300,
    ): Result<OAuthResult> = runCatching {
        logger.info("Starting OAuth flow for: $identifier")

        // Step 1: Resolve identity to find PDS
        val (did, handle, pdsUrl) = resolveIdentity(identifier)
        logger.info("Resolved identity: $handle ($did) at PDS: $pdsUrl")

        // Step 2: Fetch PDS resource server metadata
        val resourceMetadata = fetchResourceServerMetadata(pdsUrl)
        logger.info("Fetched resource server metadata from PDS")

        // Step 3: Discover authorization server
        val authServerIssuer = resourceMetadata.authorizationServers?.firstOrNull()
            ?: resourceMetadata.issuer
        logger.info("Authorization server: $authServerIssuer")

        // Step 4: Fetch authorization server metadata
        val authMetadata = fetchAuthorizationServerMetadata(authServerIssuer)
        logger.info("Fetched authorization server metadata")

        // Step 5: Generate PKCE
        val codeVerifier = PkceUtils.generateCodeVerifier()
        val codeChallenge = PkceUtils.generateCodeChallenge(codeVerifier)
        logger.info("Generated PKCE code verifier and challenge")

        // Step 6: Generate DPoP key pair
        val dpopKeyPair = DpopProof.generateKeyPair()
        logger.info("Generated DPoP key pair")

        // Step 7: Start callback server
        val callbackServer = OAuthCallbackServer()
        val port = callbackServer.start()
        val redirectUri = callbackServer.getRedirectUri()
        logger.info("Callback server started at $redirectUri")

        // Step 8: Build client_id for localhost
        val clientId = buildLocalhostClientId(redirectUri, scope)

        // Step 9: Submit PAR
        val state = UUID.randomUUID().toString()
        val parRequest = ParRequest(
            clientId = clientId,
            redirectUri = redirectUri,
            scope = scope,
            state = state,
            codeChallenge = codeChallenge,
            codeChallengeMethod = PkceUtils.getChallengeMethod(),
            loginHint = identifier,
        )

        val parEndpoint = authMetadata.parEndpoint
            ?: throw IllegalStateException("Authorization server does not support PAR")

        val dpopProofForPar = DpopProof.buildProof(
            keyPair = dpopKeyPair,
            url = parEndpoint,
            method = "POST",
        )

        val parResponse = submitPar(parEndpoint, parRequest, dpopProofForPar)
        logger.info("PAR submitted successfully, got request_uri")

        // Step 10: Build authorization URL and open browser
        val authUrl = buildAuthorizationUrl(
            authorizationEndpoint = authMetadata.authorizationEndpoint,
            clientId = clientId,
            requestUri = parResponse.requestUri,
        )

        logger.info("Opening browser for authorization")
        openBrowser(authUrl)

        // Step 11: Wait for callback
        val code = callbackServer.awaitCode(timeoutSeconds)
            ?: throw IllegalStateException("OAuth callback timed out or failed")

        logger.info("Received authorization code from callback")

        // Step 12: Exchange code for tokens
        val tokenRequest = TokenRequest(
            code = code,
            redirectUri = redirectUri,
            codeVerifier = codeVerifier,
            clientId = clientId,
        )

        val dpopProofForToken = DpopProof.buildProof(
            keyPair = dpopKeyPair,
            url = authMetadata.tokenEndpoint,
            method = "POST",
        )

        val tokenResponse = exchangeCodeForTokens(
            tokenEndpoint = authMetadata.tokenEndpoint,
            request = tokenRequest,
            dpopProof = dpopProofForToken,
        )

        logger.info("Token exchange successful")

        // Step 13: Verify DID
        val returnedDid = tokenResponse.sub
        if (returnedDid != null && returnedDid != did) {
            throw SecurityException("DID mismatch: expected $did, got $returnedDid")
        }
        logger.info("DID verified: $did")

        // Step 14: Build session
        val session = OAuthSession(
            did = did,
            handle = handle,
            pdsUrl = pdsUrl,
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken ?: throw IllegalStateException("No refresh token in response"),
            authServerIssuer = authServerIssuer,
            tokenEndpoint = authMetadata.tokenEndpoint,
            scope = tokenResponse.scope ?: scope,
            clientId = clientId,
            dpopPrivateKeyEncoded = dpopKeyPair.private.encoded,
            dpopPublicKeyEncoded = dpopKeyPair.public.encoded,
        )

        // Clean up
        callbackServer.stop()

        OAuthResult(session = session, dpopKeyPair = dpopKeyPair)
    }

    /**
     * Refreshes an OAuth session using the refresh token.
     */
    suspend fun refreshSession(
        session: OAuthSession,
        dpopKeyPair: KeyPair,
    ): Result<OAuthSession> = runCatching {
        logger.info("Refreshing OAuth session for ${session.handle}")

        val refreshRequest = TokenRefreshRequest(
            refreshToken = session.refreshToken,
            clientId = session.clientId,
        )

        val dpopProof = DpopProof.buildProof(
            keyPair = dpopKeyPair,
            url = session.tokenEndpoint,
            method = "POST",
            nonce = session.authServerNonce,
        )

        val requestBody = json.encodeToString(TokenRefreshRequest.serializer(), refreshRequest)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(session.tokenEndpoint))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("DPoP", dpopProof)
            .timeout(Duration.ofSeconds(15))
            .build()

        // For token refresh, we need to send as form-encoded
        val formBody = buildFormBody(mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to session.refreshToken,
            "client_id" to session.clientId,
        ))

        val formRequest = HttpRequest.newBuilder()
            .uri(URI.create(session.tokenEndpoint))
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("DPoP", dpopProof)
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(formRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            // Check for DPoP nonce error
            val dpopNonce = response.headers().firstValue("DPoP-Nonce").orElse(null)
            if (dpopNonce != null) {
                logger.info("Received new DPoP nonce from token endpoint, retrying")
                return retryRefreshWithNonce(session, dpopKeyPair, dpopNonce)
            }
            throw IllegalStateException("Token refresh failed: HTTP ${response.statusCode()}")
        }

        val tokenResponse = json.decodeFromString<TokenResponse>(response.body())

        session.copy(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken ?: session.refreshToken,
            lastRefreshed = System.currentTimeMillis(),
        )
    }

    /**
     * Retries a token refresh with a new DPoP nonce.
     */
    private suspend fun retryRefreshWithNonce(
        session: OAuthSession,
        dpopKeyPair: KeyPair,
        nonce: String,
    ): Result<OAuthSession> = runCatching {
        val dpopProof = DpopProof.buildProof(
            keyPair = dpopKeyPair,
            url = session.tokenEndpoint,
            method = "POST",
            nonce = nonce,
        )

        val formBody = buildFormBody(mapOf(
            "grant_type" to "refresh_token",
            "refresh_token" to session.refreshToken,
            "client_id" to session.clientId,
        ))

        val request = HttpRequest.newBuilder()
            .uri(URI.create(session.tokenEndpoint))
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("DPoP", dpopProof)
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw IllegalStateException("Token refresh retry failed: HTTP ${response.statusCode()}")
        }

        val tokenResponse = json.decodeFromString<TokenResponse>(response.body())

        session.copy(
            accessToken = tokenResponse.accessToken,
            refreshToken = tokenResponse.refreshToken ?: session.refreshToken,
            authServerNonce = nonce,
            lastRefreshed = System.currentTimeMillis(),
        )
    }

    // ============================================================================
    // IDENTITY RESOLUTION
    // ============================================================================

    /**
     * Resolves a handle or DID to (did, handle, pdsUrl).
     */
    private fun resolveIdentity(identifier: String): Triple<String, String, String> {
        // For now, delegate to the existing client-side identity resolution.
        // This will be replaced with a direct implementation that doesn't depend
        // on the existing ClientAtProtoClient.
        throw NotImplementedError("Identity resolution will be wired up in the integration step")
    }

    // ============================================================================
    // SERVER METADATA DISCOVERY
    // ============================================================================

    /**
     * Fetches the resource server metadata from a PDS.
     * Tries the well-known endpoint first.
     */
    private fun fetchResourceServerMetadata(pdsUrl: String): ResourceServerMetadata {
        val url = "$pdsUrl/.well-known/oauth-protected-resource"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("Failed to fetch resource server metadata: HTTP ${response.statusCode()}")
        }

        return json.decodeFromString<ResourceServerMetadata>(response.body())
    }

    /**
     * Fetches the authorization server metadata.
     */
    private fun fetchAuthorizationServerMetadata(issuer: String): AuthorizationServerMetadata {
        val url = "$issuer/.well-known/oauth-authorization-server"
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .GET()
            .timeout(Duration.ofSeconds(10))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("Failed to fetch authorization server metadata: HTTP ${response.statusCode()}")
        }

        return json.decodeFromString<AuthorizationServerMetadata>(response.body())
    }

    // ============================================================================
    // PAR
    // ============================================================================

    /**
     * Submits a Pushed Authorization Request.
     */
    private fun submitPar(
        parEndpoint: String,
        request: ParRequest,
        dpopProof: String,
    ): ParResponse {
        val formBody = buildFormBody(mapOf(
            "response_type" to request.responseType,
            "client_id" to request.clientId,
            "redirect_uri" to request.redirectUri,
            "scope" to request.scope,
            "state" to request.state,
            "code_challenge" to request.codeChallenge,
            "code_challenge_method" to request.codeChallengeMethod,
            "login_hint" to (request.loginHint ?: ""),
        ))

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(parEndpoint))
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("DPoP", dpopProof)
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 201 && response.statusCode() != 200) {
            // Check for DPoP nonce
            val dpopNonce = response.headers().firstValue("DPoP-Nonce").orElse(null)
            if (dpopNonce != null) {
                logger.info("Received DPoP nonce from PAR endpoint, retrying")
                return retryParWithNonce(parEndpoint, request, dpopNonce)
            }
            throw IllegalStateException("PAR request failed: HTTP ${response.statusCode()}: ${response.body()}")
        }

        return json.decodeFromString<ParResponse>(response.body())
    }

    /**
     * Retries PAR with a DPoP nonce.
     */
    private fun retryParWithNonce(
        parEndpoint: String,
        request: ParRequest,
        nonce: String,
    ): ParResponse {
        // Re-generate DPoP key pair for retry (or reuse — spec allows either)
        val dpopKeyPair = DpopProof.generateKeyPair()
        val dpopProof = DpopProof.buildProof(
            keyPair = dpopKeyPair,
            url = parEndpoint,
            method = "POST",
            nonce = nonce,
        )

        val formBody = buildFormBody(mapOf(
            "response_type" to request.responseType,
            "client_id" to request.clientId,
            "redirect_uri" to request.redirectUri,
            "scope" to request.scope,
            "state" to request.state,
            "code_challenge" to request.codeChallenge,
            "code_challenge_method" to request.codeChallengeMethod,
            "login_hint" to (request.loginHint ?: ""),
        ))

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(parEndpoint))
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("DPoP", dpopProof)
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw IllegalStateException("PAR retry failed: HTTP ${response.statusCode()}: ${response.body()}")
        }

        return json.decodeFromString<ParResponse>(response.body())
    }

    // ============================================================================
    // TOKEN EXCHANGE
    // ============================================================================

    /**
     * Exchanges an authorization code for access and refresh tokens.
     */
    private fun exchangeCodeForTokens(
        tokenEndpoint: String,
        request: TokenRequest,
        dpopProof: String,
    ): TokenResponse {
        val formBody = buildFormBody(mapOf(
            "grant_type" to request.grantType,
            "code" to request.code,
            "redirect_uri" to request.redirectUri,
            "code_verifier" to request.codeVerifier,
            "client_id" to request.clientId,
        ))

        val httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(tokenEndpoint))
            .POST(HttpRequest.BodyPublishers.ofString(formBody))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("DPoP", dpopProof)
            .timeout(Duration.ofSeconds(15))
            .build()

        val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw IllegalStateException("Token exchange failed: HTTP ${response.statusCode()}: ${response.body()}")
        }

        return json.decodeFromString<TokenResponse>(response.body())
    }

    // ============================================================================
    // AUTHORIZATION URL
    // ============================================================================

    /**
     * Builds the authorization URL to redirect the user to.
     */
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

    // ============================================================================
    // LOCALHOST CLIENT ID
    // ============================================================================

    /**
     * Builds the localhost client_id per ATProto OAuth spec.
     *
     * For development and native apps, the client_id is `http://localhost`
     * with redirect_uri and scope as query parameters.
     */
    private fun buildLocalhostClientId(redirectUri: String, scope: String): String {
        return "http://localhost?redirect_uri=${encodeURIComponent(redirectUri)}&scope=${encodeURIComponent(scope)}"
    }

    // ============================================================================
    // BROWSER
    // ============================================================================

    /**
     * Opens the user's default browser at the given URL.
     */
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

    // ============================================================================
    // UTILITIES
    // ============================================================================

    /**
     * Encodes a string for use in a URL query parameter.
     */
    private fun encodeURIComponent(value: String): String {
        return java.net.URLEncoder.encode(value, Charsets.UTF_8)
            .replace("+", "%20")
            .replace("%21", "!")
            .replace("%27", "'")
            .replace("%28", "(")
            .replace("%29", ")")
            .replace("%7E", "~")
    }

    /**
     * Builds an application/x-www-form-urlencoded body from a map.
     */
    private fun buildFormBody(params: Map<String, String>): String {
        return params.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&") { (key, value) ->
                "${encodeURIComponent(key)}=${encodeURIComponent(value)}"
            }
    }
}
