package com.jollywhoppers.security

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/**
 * Security audit logger for tracking authentication and security events.
 * Logs are stored separately from application logs for security analysis.
 */
object SecurityAuditor {
    private val logger = LoggerFactory.getLogger("atproto-connect-audit")
    private lateinit var auditFile: Path
    private var initialized = false
    
    fun initialize(configDir: Path) {
        auditFile = configDir.resolve("security-audit.log")
        initialized = true
        log("SYSTEM", null, "Security auditor initialized", null)
    }
    
    fun logAuthSuccess(uuid: UUID, handle: String, playerName: String, ip: String? = null) {
        log("AUTH_SUCCESS", uuid, "Authenticated as $handle (player: $playerName)", ip)
    }
    
    fun logAuthFailure(uuid: UUID, identifier: String, reason: String, playerName: String, ip: String? = null) {
        log("AUTH_FAILURE", uuid, "Failed login attempt for $identifier by $playerName: $reason", ip)
    }
    
    fun logRateLimitHit(uuid: UUID, playerName: String, ip: String? = null) {
        log("RATE_LIMIT", uuid, "Rate limit exceeded for $playerName", ip)
    }
    
    fun logRateLimitLockout(uuid: UUID, playerName: String, minutesRemaining: Long, ip: String? = null) {
        log("RATE_LIMIT_LOCKOUT", uuid, "Player $playerName locked out for $minutesRemaining minutes", ip)
    }
    
    fun logIdentityLink(uuid: UUID, handle: String, playerName: String) {
        log("IDENTITY_LINK", uuid, "Player $playerName linked to $handle", null)
    }
    
    fun logIdentityUnlink(uuid: UUID, handle: String, playerName: String) {
        log("IDENTITY_UNLINK", uuid, "Player $playerName unlinked from $handle", null)
    }
    
    fun logSessionRefresh(uuid: UUID, handle: String) {
        log("SESSION_REFRESH", uuid, "Session refreshed for $handle", null)
    }
    
    fun logLogout(uuid: UUID, handle: String, playerName: String) {
        log("LOGOUT", uuid, "Player $playerName ($handle) logged out", null)
    }
    
    fun logSuspiciousActivity(uuid: UUID?, message: String, ip: String? = null) {
        log("SUSPICIOUS", uuid, message, ip)
    }

    fun logPrivacyChange(uuid: UUID, playerName: String, publicStats: Boolean? = null, publicSessions: Boolean? = null) {
        val changes = buildString {
            if (publicStats != null) append(" publicStats=$publicStats")
            if (publicSessions != null) append(" publicSessions=$publicSessions")
        }
        log("PRIVACY_CHANGE", uuid, "Player $playerName changed privacy settings:$changes", null)
    }
    
    private fun log(event: String, uuid: UUID?, message: String, ip: String?) {
        if (!initialized) {
            logger.warn("Security auditor not initialized, skipping log: $event $message")
            return
        }
        
        val timestamp = Instant.now().toString()
        val uuidStr = uuid?.toString() ?: "SYSTEM"
        val ipStr = ip?.let { " IP=$it" } ?: ""
        val entry = "[$timestamp] $event UUID=$uuidStr$ipStr $message\n"
        
        try {
            Files.writeString(
                auditFile,
                entry,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND
            )
            logger.info("AUDIT: $event UUID=$uuidStr $message")
        } catch (e: Exception) {
            logger.error("Failed to write audit log", e)
        }
    }
}
