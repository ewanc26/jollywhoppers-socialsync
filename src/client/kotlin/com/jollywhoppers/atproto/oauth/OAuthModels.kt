package com.jollywhoppers.atproto.oauth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

@Serializable
data class ParResponse(
    @SerialName("request_uri") val requestUri: String,
    val expires: Int? = null,
)

@Serializable
data class TokenRequest(
    @SerialName("grant_type") val grantType: String = "authorization_code",
    val code: String,
    @SerialName("redirect_uri") val redirectUri: String,
    @SerialName("code_verifier") val codeVerifier: String,
    @SerialName("client_id") val clientId: String,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("token_type") val tokenType: String = "DPoP",
    @SerialName("expires_in") val expiresIn: Int? = null,
    val scope: String? = null,
    val sub: String? = null,
)

@Serializable
data class TokenRefreshRequest(
    @SerialName("grant_type") val grantType: String = "refresh_token",
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("client_id") val clientId: String,
)

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

@Serializable
data class OAuthError(
    val error: String,
    @SerialName("error_description") val errorDescription: String? = null,
    @SerialName("error_uri") val errorUri: String? = null,
)
