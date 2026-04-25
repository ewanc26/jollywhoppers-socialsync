package com.jollywhoppers.atproto.oauth

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID

/**
 * DPoP (Demonstrating Proof of Possession) proof generation per RFC 9449.
 *
 * ATProto OAuth mandates DPoP for all clients. Every authenticated request
 * must include a fresh DPoP proof JWT in the DPoP header, and the
 * Authorization header must use the DPoP auth scheme.
 *
 * Key management:
 * - Generate one ES256 key pair per OAuth session
 * - Reuse the same key pair for all requests within that session
 * - Track nonces separately for the authorization server and the PDS
 */
object DpopProof {
    private const val ALGORITHM = "EC"
    private const val CURVE = "P-256"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val JWT_ALGORITHM = "ES256"

    private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val base64UrlDecoder = Base64.getUrlDecoder()

    /**
     * Generates a new ES256 (P-256) key pair for DPoP.
     * Should be called once per OAuth session and reused for all requests.
     */
    fun generateKeyPair(): KeyPair {
        val keyGen = KeyPairGenerator.getInstance(ALGORITHM)
        keyGen.initialize(256)
        return keyGen.generateKeyPair()
    }

    /**
     * Builds a DPoP proof JWT for an authorization server request.
     *
     * @param keyPair The DPoP key pair for this session
     * @param url The target URL of the request
     * @param method The HTTP method (GET, POST, etc.)
     * @param nonce Optional server-issued nonce (from DPoP-Nonce header)
     * @param accessToken Optional access token hash for resource server requests
     * @return The signed DPoP proof JWT string
     */
    fun buildProof(
        keyPair: KeyPair,
        url: String,
        method: String,
        nonce: String? = null,
        accessToken: String? = null
    ): String {
        val jwk = publicJwk(keyPair.public as ECPublicKey)

        val header = buildJsonObject {
            put("typ", "dpop+jwt")
            put("alg", JWT_ALGORITHM)
            put("jwk", jwk)
        }

        val now = System.currentTimeMillis() / 1000

        val payload = buildJsonObject {
            put("jti", UUID.randomUUID().toString())
            put("htm", method.uppercase())
            put("htu", url)
            put("iat", now)
            nonce?.let { put("nonce", it) }
            accessToken?.let { put("ath", sha256Base64Url(it)) }
        }

        val headerB64 = base64UrlEncoder.encodeToString(header.toByteArray(Charsets.UTF_8))
        val payloadB64 = base64UrlEncoder.encodeToString(payload.toByteArray(Charsets.UTF_8))

        val signingInput = "$headerB64.$payloadB64"

        val signature = sign(keyPair, signingInput)
        val signatureB64 = base64UrlEncoder.encodeToString(signature)

        return "$signingInput.$signatureB64"
    }

    /**
     * Computes the SHA-256 hash of the access token, base64url-encoded.
     * Used as the `ath` claim in DPoP proofs for resource server requests.
     */
    fun sha256Base64Url(input: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.US_ASCII))
        return base64UrlEncoder.encodeToString(hash)
    }

    /**
     * Signs the input string with the ES256 private key.
     */
    private fun sign(keyPair: KeyPair, input: String): ByteArray {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(keyPair.private as ECPrivateKey)
        signature.update(input.toByteArray(Charsets.US_ASCII))
        return signature.sign()
    }

    /**
     * Builds the public JWK from an EC public key.
     * Only includes the minimum fields needed for DPoP (RFC 9449 §4.2).
     */
    private fun publicJwk(publicKey: ECPublicKey): String {
        val params = publicKey.params
        val affineX = params.curve.field.fieldSize
        val x = base64UrlEncoder.encodeToString(publicKey.w.affineX.toByteArray().trimLeadingZeros())
        val y = base64UrlEncoder.encodeToString(publicKey.w.affineY.toByteArray().trimLeadingZeros())

        return buildJsonObject {
            put("kty", "EC")
            put("crv", CURVE)
            put("x", x)
            put("y", y)
        }
    }

    /**
     * Trims leading zero bytes from a BigInteger's byte array representation.
     * Required for correct base64url encoding of EC point coordinates.
     */
    private fun ByteArray.trimLeadingZeros(): ByteArray {
        var offset = 0
        while (offset < this.size - 1 && this[offset] == 0.toByte()) {
            offset++
        }
        return if (offset == 0) this else this.copyOfRange(offset, this.size)
    }

    /**
     * Simple JSON object builder.
     * Avoids pulling in a JSON dependency just for DPoP proof construction.
     */
    private fun buildJsonObject(block: JsonObjectBuilder.() -> Unit): String {
        val builder = JsonObjectBuilder()
        builder.block()
        return builder.build()
    }

    /**
     * Minimal JSON object builder for DPoP proof JWTs.
     * Produces compact JSON without external dependencies.
     */
    class JsonObjectBuilder {
        private val entries = mutableListOf<String>()

        fun put(key: String, value: String) {
            entries.add("\"$key\":\"$value\"")
        }

        fun put(key: String, value: Number) {
            entries.add("\"$key\":$value")
        }

        fun put(key: String, value: Boolean) {
            entries.add("\"$key\":$value")
        }

        fun build(): String = "{${entries.joinToString(",")}}"
    }
}
