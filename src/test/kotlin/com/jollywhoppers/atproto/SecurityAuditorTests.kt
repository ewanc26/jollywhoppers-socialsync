package com.jollywhoppers.atproto

import com.jollywhoppers.security.SecurityAuditor
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertTrue

class SecurityAuditorTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var auditFile: Path

    @BeforeEach
    fun setup() {
        SecurityAuditor.initialize(tempDir)
        auditFile = tempDir.resolve("security-audit.log")
    }

    @AfterEach
    fun teardown() {
        // Reset initialized flag via reinit so next test gets clean state
    }

    @Test
    fun `initialize creates audit file with system entry`() {
        assertTrue(Files.exists(auditFile))
        val content = Files.readString(auditFile)
        assertTrue(content.contains("SYSTEM"))
        assertTrue(content.contains("Security auditor initialized"))
    }

    @Test
    fun `logAuthSuccess writes formatted entry`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logAuthSuccess(uuid, "alice.bsky.social", "Alice")

        val content = Files.readString(auditFile)
        assertTrue(content.contains("AUTH_SUCCESS"))
        assertTrue(content.contains(uuid.toString()))
        assertTrue(content.contains("Authenticated as alice.bsky.social"))
    }

    @Test
    fun `logAuthFailure writes formatted entry with ip`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logAuthFailure(uuid, "bob.bsky.social", "wrong password", "Bob", "192.168.1.1")

        val content = Files.readString(auditFile)
        assertTrue(content.contains("AUTH_FAILURE"))
        assertTrue(content.contains("wrong password"))
        assertTrue(content.contains("IP=192.168.1.1"))
    }

    @Test
    fun `logRateLimitHit writes entry`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logRateLimitHit(uuid, "Charlie")

        val content = Files.readString(auditFile)
        assertTrue(content.contains("RATE_LIMIT"))
        assertTrue(content.contains("Charlie"))
    }

    @Test
    fun `logRateLimitLockout includes minutes remaining`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logRateLimitLockout(uuid, "Dave", 15)

        val content = Files.readString(auditFile)
        assertTrue(content.contains("RATE_LIMIT_LOCKOUT"))
        assertTrue(content.contains("locked out for 15 minutes"))
    }

    @Test
    fun `logIdentityLink writes entry`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logIdentityLink(uuid, "eve.bsky.social", "Eve")

        val content = Files.readString(auditFile)
        assertTrue(content.contains("IDENTITY_LINK"))
        assertTrue(content.contains("linked to eve.bsky.social"))
    }

    @Test
    fun `logIdentityUnlink writes entry`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logIdentityUnlink(uuid, "eve.bsky.social", "Eve")

        val content = Files.readString(auditFile)
        assertTrue(content.contains("IDENTITY_UNLINK"))
        assertTrue(content.contains("unlinked from eve.bsky.social"))
    }

    @Test
    fun `logSessionRefresh writes entry`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logSessionRefresh(uuid, "frank.bsky.social")

        val content = Files.readString(auditFile)
        assertTrue(content.contains("SESSION_REFRESH"))
    }

    @Test
    fun `logLogout writes entry`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logLogout(uuid, "grace.bsky.social", "Grace")

        val content = Files.readString(auditFile)
        assertTrue(content.contains("LOGOUT"))
    }

    @Test
    fun `logSuspiciousActivity writes entry with null uuid`() {
        SecurityAuditor.logSuspiciousActivity(null, "Multiple failed attempts")

        val content = Files.readString(auditFile)
        assertTrue(content.contains("SUSPICIOUS"))
        assertTrue(content.contains("UUID=SYSTEM"))
    }

    @Test
    fun `logSyncPreferenceChange writes formatted entry`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logSyncPreferenceChange(uuid, "Heidi", true, false, true, false)

        val content = Files.readString(auditFile)
        assertTrue(content.contains("SYNC_PREFERENCE_UPDATE"))
        assertTrue(content.contains("stats=true sessions=false achievements=true serverStatus=false"))
    }

    @Test
    fun `logSecurityEvent uses custom event type`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logSecurityEvent("CUSTOM_EVENT", uuid, "Something happened")

        val content = Files.readString(auditFile)
        assertTrue(content.contains("CUSTOM_EVENT"))
        assertTrue(content.contains("Something happened"))
    }

    @Test
    fun `all log entries have timestamp prefix`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logAuthSuccess(uuid, "test.bsky.social", "Test")

        val content = Files.readString(auditFile)
        val lines = content.lines().filter { it.isNotBlank() }
        // First line is the system init entry
        assertTrue(lines.all { it.startsWith("[") })
    }

    @Test
    fun `multiple log entries are appended sequentially`() {
        val uuid = UUID.randomUUID()
        SecurityAuditor.logAuthSuccess(uuid, "a.bsky.social", "A")
        SecurityAuditor.logAuthFailure(uuid, "b.bsky.social", "bad password", "B")
        SecurityAuditor.logLogout(uuid, "c.bsky.social", "C")

        val content = Files.readString(auditFile)
        val lines = content.lines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 4) // init + 3 events
        assertTrue(lines.any { it.contains("AUTH_SUCCESS") })
        assertTrue(lines.any { it.contains("AUTH_FAILURE") })
        assertTrue(lines.any { it.contains("LOGOUT") })
    }
}
