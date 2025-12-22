package com.jollywhoppers.security

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Security utilities for encrypting sensitive data and managing file permissions.
 * Uses AES-256-GCM for encryption with authenticated encryption.
 */
object SecurityUtils {
    private val logger = LoggerFactory.getLogger("atproto-connect-security")
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_SIZE = 256
    private const val IV_SIZE = 12
    private const val TAG_SIZE = 128
    
    @Serializable
    private data class EncryptedData(
        val iv: String,
        val ciphertext: String
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
     * Loads or generates the server's encryption key.
     * Stores the key in a secure file with restricted permissions.
     */
    fun loadOrGenerateServerKey(keyFile: Path): SecretKey {
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
        setRestrictedPermissions(keyFile)
        logger.info("Generated new server encryption key")
        return key
    }
    
    /**
     * Encrypts sensitive data using AES-256-GCM.
     * Returns a base64-encoded JSON object containing IV and ciphertext.
     */
    fun encrypt(data: String, key: SecretKey): String {
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = ByteArray(IV_SIZE)
        SecureRandom().nextBytes(iv)
        
        val spec = GCMParameterSpec(TAG_SIZE, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        
        val ciphertext = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        
        val encrypted = EncryptedData(
            iv = Base64.getEncoder().encodeToString(iv),
            ciphertext = Base64.getEncoder().encodeToString(ciphertext)
        )
        
        return Json.encodeToString(encrypted)
    }
    
    /**
     * Decrypts data encrypted with encrypt().
     */
    fun decrypt(encryptedJson: String, key: SecretKey): String {
        val encrypted = Json.decodeFromString<EncryptedData>(encryptedJson)
        
        val cipher = Cipher.getInstance(ALGORITHM)
        val iv = Base64.getDecoder().decode(encrypted.iv)
        val ciphertext = Base64.getDecoder().decode(encrypted.ciphertext)
        
        val spec = GCMParameterSpec(TAG_SIZE, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        
        val plaintext = cipher.doFinal(ciphertext)
        return String(plaintext, Charsets.UTF_8)
    }
    
    /**
     * Sets file permissions to owner-only read/write (0600 on Unix).
     * On Windows, sets to owner-only access.
     */
    fun setRestrictedPermissions(file: Path) {
        try {
            // Try POSIX permissions first (Linux, macOS)
            val perms = setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE
            )
            Files.setPosixFilePermissions(file, perms)
            logger.debug("Set POSIX permissions (0600) on ${file.fileName}")
        } catch (e: UnsupportedOperationException) {
            // Fall back to basic file permissions (Windows)
            try {
                val javaFile = file.toFile()
                javaFile.setReadable(true, true)
                javaFile.setWritable(true, true)
                javaFile.setExecutable(false, false)
                logger.debug("Set basic file permissions on ${javaFile.name}")
            } catch (ex: Exception) {
                logger.warn("Failed to set file permissions on ${file.fileName}", ex)
            }
        } catch (e: Exception) {
            logger.warn("Failed to set POSIX permissions on ${file.fileName}", e)
        }
    }
    
    /**
     * Sanitizes a string for safe logging (removes sensitive parts).
     */
    fun sanitizeForLog(input: String): String {
        return when {
            input.length <= 8 -> "***"
            else -> "${input.take(4)}...${input.takeLast(4)}"
        }
    }
    
    /**
     * Validates that a path is within an expected parent directory.
     * Prevents path traversal attacks.
     */
    fun validatePathInDirectory(path: Path, parentDir: Path): Boolean {
        return try {
            val normalized = path.normalize().toAbsolutePath()
            val parent = parentDir.normalize().toAbsolutePath()
            normalized.startsWith(parent)
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Securely clears a CharArray (for passwords).
     */
    fun clearCharArray(array: CharArray) {
        array.fill('\u0000')
    }
}
