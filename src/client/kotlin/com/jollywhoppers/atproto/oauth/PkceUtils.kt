package com.jollywhoppers.atproto.oauth

import java.security.SecureRandom
import java.util.Base64

/**
 * PKCE (Proof Key for Code Exchange) utilities per RFC 7636.
 *
 * ATProto OAuth mandates PKCE for every authorization request.
 * The client generates a random code verifier, derives a code challenge
 * from it (S256 method), sends the challenge in the authorization request,
 * and proves possession of the verifier when exchanging the code for tokens.
 */
object PkceUtils {
    private const val VERIFIER_LENGTH = 32 // 256 bits of entropy
    private const val CHALLENGE_METHOD = "S256"

    private val secureRandom = SecureRandom()
    private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

    /**
     * Generates a PKCE code verifier.
     *
     * Per RFC 7636 §4.1: the verifier is a random string between 43 and 128
     * characters using the unreserved characters [A-Z] / [a-z] / [0-9] / "-" / "." / "_" / "~".
     *
     * We generate 32 random bytes and base64url-encode them, producing a 43-character string.
     */
    fun generateCodeVerifier(): String {
        val bytes = ByteArray(VERIFIER_LENGTH)
        secureRandom.nextBytes(bytes)
        return base64UrlEncoder.encodeToString(bytes)
    }

    /**
     * Derives a PKCE code challenge from a code verifier using S256.
     *
     * Per RFC 7636 §4.2: CODE_CHALLENGE = BASE64URL(SHA256(CODE_VERIFIER))
     */
    fun generateCodeChallenge(codeVerifier: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return base64UrlEncoder.encodeToString(hash)
    }

    /**
     * Returns the PKCE code challenge method used.
     * ATProto OAuth requires S256.
     */
    fun getChallengeMethod(): String = CHALLENGE_METHOD
}
