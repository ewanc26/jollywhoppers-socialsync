package com.jollywhoppers.atproto

import com.jollywhoppers.security.SecurityUtils
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

class SecurityUtilsExtendedTest {

    @Test
    fun `generateKey produces a non-null AES key`() {
        val key = SecurityUtils.generateKey()
        assertNotNull(key)
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun `encrypt then decrypt empty string`() {
        val key = SecurityUtils.generateKey()
        val encrypted = SecurityUtils.encrypt("", key)
        val decrypted = SecurityUtils.decrypt(encrypted, key)
        assertEquals("", decrypted)
    }

    @Test
    fun `decrypt tampered ciphertext throws exception`() {
        val key = SecurityUtils.generateKey()
        val encrypted = SecurityUtils.encrypt("valid data", key)
        val tampered = encrypted.replace('"', '\'')
        assertThrows(Exception::class.java) { SecurityUtils.decrypt(tampered, key) }
    }

    @Test
    fun `decrypt invalid json throws exception`() {
        val key = SecurityUtils.generateKey()
        assertThrows(Exception::class.java) { SecurityUtils.decrypt("not-json", key) }
    }

    @Test
    fun `validatePathInDirectory rejects parent directory traversal`(@TempDir tempDir: Path) {
        val badPath = tempDir.resolve("..").resolve("outside.txt")
        assertFalse(SecurityUtils.validatePathInDirectory(badPath, tempDir))
    }

    @Test
    fun `validatePathInDirectory rejects absolute path outside parent`(@TempDir tempDir: Path) {
        val outsidePath = Path.of("/tmp")
        assertFalse(SecurityUtils.validatePathInDirectory(outsidePath, tempDir))
    }

    @Test
    fun `validatePathInDirectory accepts file in subdirectory`(@TempDir tempDir: Path) {
        val subDir = tempDir.resolve("sub")
        Files.createDirectories(subDir)
        val filePath = subDir.resolve("data.json")
        assertTrue(SecurityUtils.validatePathInDirectory(filePath, tempDir))
    }

    @Test
    fun `setRestrictedPermissions does not throw on existing file`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("key.bin")
        Files.writeString(file, "test-key-material")
        assertDoesNotThrow { SecurityUtils.setRestrictedPermissions(file) }
    }

    @Test
    fun `setRestrictedPermissions restricts to owner-only on POSIX systems`(@TempDir tempDir: Path) {
        val file = tempDir.resolve("secure.key")
        Files.writeString(file, "sensitive")
        SecurityUtils.setRestrictedPermissions(file)
        val perms = Files.getPosixFilePermissions(file)
        assertTrue(perms.contains(PosixFilePermission.OWNER_READ))
        assertTrue(perms.contains(PosixFilePermission.OWNER_WRITE))
        assertFalse(perms.contains(PosixFilePermission.OWNER_EXECUTE))
        assertFalse(perms.contains(PosixFilePermission.GROUP_READ))
        assertFalse(perms.contains(PosixFilePermission.OTHERS_READ))
    }

    @Test
    fun `clearCharArray zeroes all positions`() {
        val chars = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
        SecurityUtils.clearCharArray(chars)
        assertTrue(chars.all { it == '\u0000' })
    }

    @Test
    fun `clearCharArray handles empty array`() {
        val chars = charArrayOf()
        SecurityUtils.clearCharArray(chars)
        assertTrue(chars.isEmpty())
    }

    @Test
    fun `sanitizeForLog masks empty or short strings`() {
        assertEquals("***", SecurityUtils.sanitizeForLog(""))
        assertEquals("***", SecurityUtils.sanitizeForLog("ab"))
        assertEquals("***", SecurityUtils.sanitizeForLog("12345678"))
    }

    @Test
    fun `sanitizeForLog shows first and last four for long strings`() {
        val result = SecurityUtils.sanitizeForLog("abcdefghijklmnop")
        assertTrue(result.startsWith("abcd"))
        assertTrue(result.endsWith("mnop"))
        assertTrue(result.contains("..."))
    }

    @Test
    fun `loadOrGenerateServerKey creates new key file`(@TempDir tempDir: Path) {
        val keyFile = tempDir.resolve(".server-key")
        assertFalse(Files.exists(keyFile))
        val key = SecurityUtils.loadOrGenerateServerKey(keyFile)
        assertNotNull(key)
        assertTrue(Files.exists(keyFile))
        assertTrue(Files.size(keyFile) > 0)
    }

    @Test
    fun `loadOrGenerateServerKey returns same key on reload`(@TempDir tempDir: Path) {
        val keyFile = tempDir.resolve(".encryption.key")
        val key1 = SecurityUtils.loadOrGenerateServerKey(keyFile)
        val key2 = SecurityUtils.loadOrGenerateServerKey(keyFile)
        assertTrue(key1.encoded.contentEquals(key2.encoded))
    }
}
