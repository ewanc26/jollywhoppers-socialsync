package com.jollywhoppers.atproto.client

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side security utilities for encrypting session data.
 * Uses the same AES-256-GCM encryption as the server-side SecurityUtils.
 *
 * The encryption key is stored in the Minecraft config directory
 * with restricted permissions. Each client machine has its own key.
 */
object ClientSecurityUtils {
    private val logger = LoggerFactory.getLogger("atproto-connect-client-security")
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128

    private val json = Json { prettyPrint = false }

    @Serializable
    private data class EncryptedData(
        val iv: String,
        val ciphertext: String,
    )

    /**
     * Generates a new AES-256 encryption key.
     */
    fun generateKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance("AES")
        keyGen.init(KEY_SIZE, SecureRandom())
        return keyGen.generateKey()
    }

    /**
     * Loads or generates the client's encryption key.
     * Stores the key in a secure file.
     */
    fun loadOrGenerateKey(keyFile: Path): SecretKey {
        return if (Files.exists(keyFile)) {
            try {
                val encodedKey = Files.readAllBytes(keyFile)
                SecretKeySpec(encodedKey, "AES")
            } catch (e: Exception) {
                logger.warn("Failed to load existing key, generating new one", e)
                generateAndStoreKey(keyFile)
            }
        } else {
            generateAndStoreKey(keyFile)
        }
    }

    private fun generateAndStoreKey(keyFile: Path): SecretKey {
        val key = generateKey()
        Files.createDirectories(keyFile.parent)
        Files.write(keyFile, key.encoded, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)

        // Try to set restricted permissions
        try {
            keyFile.toFile().setReadable(true, true) // owner-only read
            keyFile.toFile().setWritable(true, true) // owner-only write
        } catch (e: Exception) {
            logger.warn("Could not set restricted file permissions on key file", e)
        }

        logger.info("Generated new client encryption key")
        return key
    }

    /**
     * Encrypts a string using AES-256-GCM.
     * Returns a JSON string containing the IV and ciphertext (both base64-encoded).
     */
    fun encrypt(plaintext: String, key: SecretKey): String {
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(TAG_SIZE, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val encryptedData = EncryptedData(
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext),
        )

        return json.encodeToString(encryptedData)
    }

    /**
     * Decrypts a string that was encrypted with [encrypt].
     */
    fun decrypt(encryptedJson: String, key: SecretKey): String {
        val encryptedData = json.decodeFromString<EncryptedData>(encryptedJson)

        val iv = Base64.getDecoder().decode(encryptedData.iv)
        val ciphertext = Base64.getDecoder().decode(encryptedData.ciphertext)

        val cipher = Cipher.getInstance(ALGORITHM)
        val gcmSpec = GCMParameterSpec(TAG_SIZE, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)

        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }

    /**
     * Sanitizes a string for logging (hides sensitive content).
     */
    fun sanitizeForLog(input: String): String {
        return when {
            input.length <= 8 -> "***"
            else -> "${input.take(4)}...${input.takeLast(4)}"
        }
    }
}
