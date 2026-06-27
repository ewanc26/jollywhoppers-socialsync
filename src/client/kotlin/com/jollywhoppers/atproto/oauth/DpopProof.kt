package com.jollywhoppers.atproto.oauth

import io.github.kikin81.atproto.oauth.DpopSigner
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

object DpopProof {
    fun generateKeyPair(): KeyPair {
        val signer = DpopSigner.generate()
        val exported = signer.exportKeyPair()
        val keyFactory = KeyFactory.getInstance("EC")
        return KeyPair(
            keyFactory.generatePublic(X509EncodedKeySpec(exported.publicKeyEncoded)),
            keyFactory.generatePrivate(PKCS8EncodedKeySpec(exported.privateKeyEncoded)),
        )
    }

    fun buildProof(
        keyPair: KeyPair,
        url: String,
        method: String,
        nonce: String? = null,
        accessToken: String? = null
    ): String {
        val exported = DpopSigner.ExportedKeyPair(
            privateKeyEncoded = keyPair.private.encoded,
            publicKeyEncoded = keyPair.public.encoded,
        )
        val signer = DpopSigner.fromExported(exported)
        return signer.sign(
            method = method,
            url = url,
            accessTokenHash = accessToken?.let { DpopSigner.accessTokenHash(it) },
            nonce = nonce,
        )
    }

    fun sha256Base64Url(input: String): String = DpopSigner.accessTokenHash(input)
}
