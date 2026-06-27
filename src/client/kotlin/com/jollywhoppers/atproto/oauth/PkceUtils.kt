package com.jollywhoppers.atproto.oauth

import java.security.SecureRandom
import java.util.Base64

object PkceUtils {
    private const val VERIFIER_LENGTH = 32
    private const val CHALLENGE_METHOD = "S256"

    private val secureRandom = SecureRandom()
    private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun generateCodeVerifier(): String {
        val bytes = ByteArray(VERIFIER_LENGTH)
        secureRandom.nextBytes(bytes)
        return base64UrlEncoder.encodeToString(bytes)
    }

    fun generateCodeChallenge(codeVerifier: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return base64UrlEncoder.encodeToString(hash)
    }

    fun getChallengeMethod(): String = CHALLENGE_METHOD
}
