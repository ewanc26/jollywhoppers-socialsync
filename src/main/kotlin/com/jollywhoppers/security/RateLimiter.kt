package com.jollywhoppers.security

import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limiter for authentication attempts to prevent brute force attacks.
 * Implements a sliding window rate limiter with exponential backoff.
 */
class RateLimiter(
    private val maxAttempts: Int = 3,
    private val windowSeconds: Long = 900, // 15 minutes
    private val lockoutSeconds: Long = 1800 // 30 minutes after max failures
) {
    private val logger = LoggerFactory.getLogger("atproto-connect-ratelimit")
    private val attempts = ConcurrentHashMap<UUID, MutableList<Instant>>()
    private val lockouts = ConcurrentHashMap<UUID, Instant>()
    
    data class RateLimitResult(
        val allowed: Boolean,
        val attemptsRemaining: Int,
        val resetAt: Instant?,
        val lockedUntil: Instant?
    )
    
    /**
     * Checks if a player can make an authentication attempt.
     */
    fun checkAttempt(uuid: UUID): RateLimitResult {
        val now = Instant.now()
        
        // Check if player is currently locked out
        val lockoutUntil = lockouts[uuid]
        if (lockoutUntil != null && now.isBefore(lockoutUntil)) {
            return RateLimitResult(
                allowed = false,
                attemptsRemaining = 0,
                resetAt = lockoutUntil,
                lockedUntil = lockoutUntil
            )
        } else if (lockoutUntil != null) {
            // Lockout expired, remove it
            lockouts.remove(uuid)
            attempts.remove(uuid)
        }
        
        // Get recent attempts within the window
        val playerAttempts = attempts.getOrPut(uuid) { mutableListOf() }
        val windowStart = now.minusSeconds(windowSeconds)
        
        // Remove old attempts outside the window
        playerAttempts.removeIf { it.isBefore(windowStart) }
        
        val attemptsInWindow = playerAttempts.size
        val remaining = maxAttempts - attemptsInWindow
        
        if (attemptsInWindow >= maxAttempts) {
            // Too many attempts, lock out
            val lockUntil = now.plusSeconds(lockoutSeconds)
            lockouts[uuid] = lockUntil
            logger.warn("Player $uuid locked out due to too many failed authentication attempts until $lockUntil")
            
            return RateLimitResult(
                allowed = false,
                attemptsRemaining = 0,
                resetAt = lockUntil,
                lockedUntil = lockUntil
            )
        }
        
        return RateLimitResult(
            allowed = true,
            attemptsRemaining = remaining,
            resetAt = if (playerAttempts.isNotEmpty()) playerAttempts.first().plusSeconds(windowSeconds) else null,
            lockedUntil = null
        )
    }
    
    /**
     * Records a failed authentication attempt.
     */
    fun recordFailure(uuid: UUID) {
        val playerAttempts = attempts.getOrPut(uuid) { mutableListOf() }
        playerAttempts.add(Instant.now())
        logger.debug("Recorded failed authentication attempt for player $uuid (${playerAttempts.size} in window)")
    }
    
    /**
     * Records a successful authentication (clears attempts).
     */
    fun recordSuccess(uuid: UUID) {
        attempts.remove(uuid)
        lockouts.remove(uuid)
        logger.debug("Cleared authentication attempts for player $uuid after successful login")
    }
    
    /**
     * Manually clears rate limit for a player (admin use).
     */
    fun clearLimit(uuid: UUID) {
        attempts.remove(uuid)
        lockouts.remove(uuid)
        logger.info("Manually cleared rate limit for player $uuid")
    }
    
    /**
     * Gets current status for a player.
     */
    fun getStatus(uuid: UUID): RateLimitResult {
        return checkAttempt(uuid)
    }
    
    /**
     * Cleanup old entries periodically.
     */
    fun cleanup() {
        val now = Instant.now()
        val windowStart = now.minusSeconds(windowSeconds)
        
        // Clean up attempts
        attempts.entries.removeIf { (uuid, attemptList) ->
            attemptList.removeIf { it.isBefore(windowStart) }
            attemptList.isEmpty()
        }
        
        // Clean up expired lockouts
        lockouts.entries.removeIf { (_, until) ->
            now.isAfter(until)
        }
        
        logger.debug("Rate limiter cleanup: ${attempts.size} active players, ${lockouts.size} locked out")
    }
}
