package com.jollywhoppers.atproto.oauth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data models for ATProto OAuth flows.
 *
 * These models represent the various requests and responses involved in:
 * - Authorization server metadata discovery
 * - Pushed Authorization Requests (PAR)
 * - Token exchange (authorization code → access + refresh tokens)
 * - Token refresh
 * - Client metadata
 */

// ============================================================================
// AUTHORIZATION SERVER METADATA
// ============================================================================

/**
 * Authorization server metadata per RFC 8414 and ATProto OAuth extensions.
 * Fetched from the PDS or entryway to discover OAuth endpoints.
 */
@Serializable
data class AuthorizationServerMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("pushed_authorization_request_endpoint") val parEndpoint: String? = null,
    @SerialName("revocation_endpoint") val revocationEndpoint: String? = null,
    @SerialName("introspection_endpoint") val introspectionEndpoint: String? = null,
    @SerialName("scopes_supported") val scopesSupported: List<String>? = null,
    @SerialName("code_challenge_methods_supported") val codeChallengeMethodsSupported: List<String>? = null,
    @SerialName("response_types_supported") val responseTypesSupported: List<String>? = null,
    @SerialName("grant_types_supported") val grantTypesSupported: List<String>? = null,
    @SerialName("token_endpoint_auth_methods_supported") val tokenEndpointAuthMethodsSupported: List<String>? = null,
    @SerialName("dpop_signing_alg_values_supported") val dpopSigningAlgValuesSupported: List<String>? = null,
)

// ============================================================================
// RESOURCE SERVER METADATA (PDS)
// ============================================================================

/**
 * Resource server metadata for a PDS.
 * Used to discover the authorization server for a given PDS.
 */
@Serializable
data class ResourceServerMetadata(
    val issuer: String,
    @SerialName("authorization_servers") val authorizationServers: List<String>? = null,
    @SerialName("token_endpoint") val tokenEndpoint: String? = null,
    @SerialName("pushed_authorization_request_endpoint") val parEndpoint: String? = null,
)

// ============================================================================
// CLIENT METADATA
// ============================================================================

/**
 * OAuth client metadata document.
 * Published at the client_id URL and fetched by the authorization server.
 *
 * For localhost development, ATProto supports `http://localhost` as a client_id
 * with virtual metadata constructed from query parameters.
 */
@Serializable
data class OAuthClientMetadata(
    @SerialName("client_id") val clientId: String,
    @SerialName("client_name") val clientName: String? = null,
    @SerialName("client_uri") val clientUri: String? = null,
    @SerialName("redirect_uris") val redirectUris: List<String>,
    @SerialName("grant_types") val grantTypes: List<String> = listOf("authorization_code", "refresh_token"),
    @SerialName("response_types") val responseTypes: List<String> = listOf("code"),
    val scope: String = "atproto",
    @SerialName("token_endpoint_auth_method") val tokenEndpointAuthMethod: String = "none",
    @SerialName("application_type") val applicationType: String = "native",
    @SerialName("dpop_bound_access_tokens") val dpopBoundAccessTokens: Boolean = true,
    @SerialName("logo_uri") val logoUri: String? = null,
    @SerialName("tos_uri") val tosUri: String? = null,
    @SerialName("policy_uri") val policyUri: String? = null,
)

// ============================================================================
// PUSHED AUTHORIZATION REQUEST (PAR)
// ============================================================================

/**
 * PAR request body.
 * Sent to the PAR endpoint to register the authorization parameters
 * before redirecting the user to the authorization endpoint.
 */
@Serializable
data class ParRequest(
    @SerialName("response_type") val responseType: String = "code",
    @SerialName("client_id") val clientId: String,
    @SerialName("redirect_uri") val redirectUri: String,
    val scope: String,
    val state: String,
    @SerialName("code_challenge") val codeChallenge: String,
    @SerialName("code_challenge_method") val codeChallengeMethod: String = "S256",
    @SerialName("login_hint") val loginHint: String? = null,
)

/**
 * PAR response.
 * Returns a request_uri that is used in the authorization redirect.
 */
@Serializable
data class ParResponse(
    @SerialName("request_uri") val requestUri: String,
    val expires: Int? = null,
)

// ============================================================================
// TOKEN EXCHANGE
// ============================================================================

/**
 * Token request body for exchanging an authorization code for tokens.
 */
@Serializable
data class TokenRequest(
    @SerialName("grant_type") val grantType: String = "authorization_code",
    val code: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("client_id") val clientId: String,
)

/**
 * Token response containing access and refresh tokens.
 * The `sub` field contains the account DID and must be verified.
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String = "DPoP",
    @SerialName("expires_in") val expiresIn: Int? = null,
    val scope: String? = null,
    val sub: String? = null,
)

// ============================================================================
// TOKEN REFRESH
// ============================================================================

/**
 * Token refresh request body.
 */
@Serializable
data class TokenRefreshRequest(
    @SerialName("grant_type") val grantType: String = "refresh_token",
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
)

// ============================================================================
// OAUTH SESSION
// ============================================================================

/**
 * A complete OAuth session, including tokens, key material, and metadata.
 * Stored persistently and used for all authenticated requests.
 */
data class OAuthSession(
    val did: String,
    val handle: String,
    val pdsUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val authServerIssuer: String,
    val tokenEndpoint: String,
    val pdsTokenEndpoint: String? = null,
    val scope: String,
    val clientId: String,
    val dpopPrivateKeyEncoded: ByteArray,
    val dpopPublicKeyEncoded: ByteArray,
    val authServerNonce: String? = null,
    val pdsNonce: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastRefreshed: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OAuthSession) return false
        return did == other.did && accessToken == other.accessToken
    }

    override fun hashCode(): Int {
        return 31 * did.hashCode() + accessToken.hashCode()
    }
}

// ============================================================================
// OAUTH ERROR
// ============================================================================

/**
 * OAuth error response per RFC 6749 §5.2.
 */
@Serializable
data class OAuthError(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_uri") val errorUri: String? = null,
)
