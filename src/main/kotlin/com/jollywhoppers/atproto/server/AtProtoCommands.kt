package com.jollywhoppers.atproto.server

import com.jollywhoppers.security.SecurityAuditor
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/**
 * Handles AT Protocol-related commands for players.
 * Provides commands to link, authenticate, and manage AT Protocol identities.
 * 
 * SECURITY FEATURES:
 * - Rate limiting on authentication (3 attempts per 15 minutes)
 * - Security audit logging for all sensitive operations
 * - Sanitized error messages
 * - Secure password handling (no logging)
 */
class AtProtoCommands(
    private val client: AtProtoClient,
    private val identityStore: PlayerIdentityStore,
    private val sessionManager: AtProtoSessionManager,
    private val syncPreferencesStore: PlayerSyncPreferencesStore,
    private val profileService: PlayerProfileService? = null,
) {
    private val logger = LoggerFactory.getLogger("atproto-connect")
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    /**
     * Registers all AT Protocol commands.
     */
    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("atproto")
                .then(
                    Commands.literal("link")
                        .then(
                            Commands.argument("identifier", StringArgumentType.greedyString())
                                .executes { context -> linkIdentity(context) }
                        )
                )
                .then(
                    Commands.literal("unlink")
                        .executes { context -> unlinkIdentity(context) }
                )
                .then(
                    Commands.literal("logout")
                        .executes { context -> logout(context) }
                )
                .then(
                    Commands.literal("whoami")
                        .executes { context -> whoami(context) }
                )
                .then(
                    Commands.literal("whois")
                        .then(
                            Commands.argument("identifier", StringArgumentType.greedyString())
                                .executes { context -> whois(context) }
                        )
                )
                .then(
                    Commands.literal("status")
                        .executes { context -> status(context) }
                )
                .then(
                    Commands.literal("sync")
                        .executes { context -> syncConsentStatus(context) }
                        .then(
                            Commands.literal("stats")
                                .then(
                                    Commands.literal("on")
                                        .executes { context -> setSyncConsent(context, syncStats = true) }
                                )
                                .then(
                                    Commands.literal("off")
                                        .executes { context -> setSyncConsent(context, syncStats = false) }
                                )
                        )
                        .then(
                            Commands.literal("sessions")
                                .then(
                                    Commands.literal("on")
                                        .executes { context -> setSyncConsent(context, syncSessions = true) }
                                )
                                .then(
                                    Commands.literal("off")
                                        .executes { context -> setSyncConsent(context, syncSessions = false) }
                                )
                        )
                        .then(
                            Commands.literal("achievements")
                                .then(
                                    Commands.literal("on")
                                        .executes { context -> setSyncConsent(context, syncAchievements = true) }
                                )
                                .then(
                                    Commands.literal("off")
                                        .executes { context -> setSyncConsent(context, syncAchievements = false) }
                                )
                        )
                        .then(
                            Commands.literal("server-status")
                                .then(
                                    Commands.literal("on")
                                        .executes { context -> setSyncConsent(context, syncServerStatus = true) }
                                )
                                .then(
                                    Commands.literal("off")
                                        .executes { context -> setSyncConsent(context, syncServerStatus = false) }
                                )
                        )
                )
                .executes { context -> help(context) }
        )
    }

    /**
     * Links a player's Minecraft UUID to their AT Protocol identity (without authentication).
     */
    private fun linkIdentity(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val identifier = StringArgumentType.getString(context, "identifier")

        context.source.sendSuccess(
            { Component.literal("§eVerifying AT Protocol identity...") },
            false
        )

        coroutineScope.launch {
            try {
                // Resolve the identifier (handle or DID) to get DID, handle, and PDS
                val (did, handle, pdsUrl) = client.resolveIdentifier(identifier).getOrThrow()

                // Verify the identity exists by fetching the profile
                val profile = client.getProfile(did, pdsUrl).getOrThrow()

                // Link the identity
                identityStore.linkIdentity(player.uuid, profile.did, profile.handle)
                
                // Audit log
                SecurityAuditor.logIdentityLink(player.uuid, profile.handle, player.name.string)

                player.sendSystemMessage(
                    Component.literal("§a✓ Successfully linked to AT Protocol!")
                        .append(Component.literal("\n§7Handle: §f${profile.handle}"))
                        .append(Component.literal("\n§7DID: §f${profile.did}"))
                        .append(Component.literal("\n§7PDS: §f$pdsUrl"))
                        .apply {
                            profile.displayName?.let {
                                append(Component.literal("\n§7Display Name: §f$it"))
                            }
                        }
                        .append(Component.literal("\n\n§eNote: Use the mod config screen to authenticate and sync data"))
                )

                logger.info("Player ${player.name.string} (${player.uuid}) linked to ${profile.handle}")
            } catch (e: Exception) {
                player.sendSystemMessage(
                    Component.literal("§c✗ Failed to link AT Protocol identity")
                        .append(Component.literal("\n§7${sanitizeError(e)}"))
                )
                logger.error("Failed to link identity for player ${player.name.string}: ${e.javaClass.simpleName}")
            }
        }

        return 1
    }

    /**
     * Unlinks a player's Minecraft UUID from their AT Protocol identity.
     */
    private fun unlinkIdentity(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val identity = identityStore.getIdentity(player.uuid)

        return if (identity != null) {
            // Also logout if they're authenticated
            if (sessionManager.hasSession(player.uuid)) {
                sessionManager.deleteSession(player.uuid)
            }
            
            identityStore.unlinkIdentity(player.uuid)
            
            // Audit log
            SecurityAuditor.logIdentityUnlink(player.uuid, identity.handle, player.name.string)
            
            context.source.sendSuccess(
                {
                    Component.literal("§a✓ Unlinked from AT Protocol identity")
                        .append(Component.literal("\n§7Previously linked to: §f${identity.handle}"))
                },
                false
            )
            logger.info("Player ${player.name.string} (${player.uuid}) unlinked from ${identity.handle}")
            1
        } else {
            context.source.sendFailure(
                Component.literal("§c✗ You don't have a linked AT Protocol identity")
            )
            0
        }
    }

    /**
     * Logs out a player (removes their authentication session).
     */
    private fun logout(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val identity = identityStore.getIdentity(player.uuid)
        
        return if (sessionManager.hasSession(player.uuid)) {
            sessionManager.deleteSession(player.uuid)
            
            // Audit log
            if (identity != null) {
                SecurityAuditor.logLogout(player.uuid, identity.handle, player.name.string)
            }
            
            context.source.sendSuccess(
                {
                    Component.literal("§a✓ Logged out successfully")
                        .append(Component.literal("\n§7Your identity link remains active"))
                        .append(Component.literal("\n§7Use the mod config screen to authenticate again"))
                },
                false
            )
            logger.info("Player ${player.name.string} (${player.uuid}) logged out")
            1
        } else {
            context.source.sendFailure(
                Component.literal("§c✗ You are not logged in")
            )
            0
        }
    }

    /**
     * Shows the player their own linked AT Protocol identity and authentication status.
     */
    private fun whoami(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val identity = identityStore.getIdentity(player.uuid)

        return if (identity != null) {
            val linkedAgo = formatTimeSince(identity.linkedAt)
            val verifiedAgo = formatTimeSince(identity.lastVerified)
            val isAuthenticated = sessionManager.hasSession(player.uuid)
            val session = sessionManager.getAllSessions()[player.uuid]
            val authType = session?.authType ?: "none"

            context.source.sendSuccess(
                {
                    Component.literal("§b━━━ Your AT Protocol Identity ━━━")
                        .append(Component.literal("\n§7Handle: §f${identity.handle}"))
                        .append(Component.literal("\n§7DID: §f${identity.did}"))
                        .append(Component.literal("\n§7Linked: §f$linkedAgo ago"))
                        .append(Component.literal("\n§7Last Verified: §f$verifiedAgo ago"))
                        .append(Component.literal("\n"))
                        .append(
                            if (isAuthenticated) {
                                val typeLabel = when (authType) {
                                    "oauth" -> "§bOAuth"
                                    else -> "§eApp Password"
                                }
                                Component.literal("\n§aAuthentication: §f✓ Active ($typeLabel§f)")
                                    .append(Component.literal("\n§7You can sync data to AT Protocol"))
                            } else {
                                Component.literal("\n§cAuthentication: §f✗ Not logged in")
                                    .append(Component.literal("\n§7Use the mod config screen to authenticate"))
                            }
                        )
                },
                false
            )
            1
        } else {
            context.source.sendFailure(
                Component.literal("§c✗ You don't have a linked AT Protocol identity")
                    .append(Component.literal("\n§7Use §f/atproto link <handle or DID>§7 to link your identity"))
            )
            0
        }
    }

    /**
     * Shows information about another player's AT Protocol identity.
     */
    private fun whois(context: CommandContext<CommandSourceStack>): Int {
        val identifier = StringArgumentType.getString(context, "identifier")

        coroutineScope.launch {
            try {
                val player = context.source.playerOrException

                // Try to find by Minecraft username first
                val minecraftPlayer = context.source.server.playerList.players
                    .firstOrNull { it.name.string.equals(identifier, ignoreCase = true) }

                val identity = if (minecraftPlayer != null) {
                    identityStore.getIdentity(minecraftPlayer.uuid)
                } else {
                    // Try as AT Protocol handle or DID
                    val uuid = identityStore.getUuidByHandle(identifier)
                        ?: identityStore.getUuidByDid(identifier)
                    uuid?.let { identityStore.getIdentity(it) }
                }

                if (identity != null) {
                    val linkedAgo = formatTimeSince(identity.linkedAt)
                    player.sendSystemMessage(
                        Component.literal("§b━━━ AT Protocol Identity ━━━")
                            .append(Component.literal("\n§7Handle: §f${identity.handle}"))
                            .append(Component.literal("\n§7DID: §f${identity.did}"))
                            .append(Component.literal("\n§7Linked: §f$linkedAgo ago"))
                    )
                } else {
                    player.sendSystemMessage(
                        Component.literal("§c✗ No linked AT Protocol identity found for: $identifier")
                    )
                }
            } catch (e: Exception) {
                logger.error("Error in whois command: ${e.javaClass.simpleName}")
            }
        }

        return 1
    }

    /**
     * Shows authentication and connection status.
     */
    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val isLinked = identityStore.isLinked(player.uuid)
        val isAuthenticated = sessionManager.hasSession(player.uuid)

        context.source.sendSuccess(
            {
                Component.literal("§b━━━ AT Protocol Status ━━━")
                    .append(
                        if (isLinked) {
                            val identity = identityStore.getIdentity(player.uuid)!!
                            Component.literal("\n§aIdentity: §f✓ Linked to ${identity.handle}")
                        } else {
                            Component.literal("\n§cIdentity: §f✗ Not linked")
                        }
                    )
                    .append(
                        if (isAuthenticated) {
                            Component.literal("\n§aAuthentication: §f✓ Active session")
                        } else {
                            Component.literal("\n§cAuthentication: §f✗ Not logged in")
                        }
                    )
                    .append(
                        if (isLinked && isAuthenticated) {
                            Component.literal("\n\n§aReady to sync Minecraft data to AT Protocol!")
                        } else if (isLinked) {
                            Component.literal("\n\n§eUse the mod config screen to authenticate")
                        } else {
                            Component.literal("\n\n§eUse §f/atproto link <handle>§e to get started")
                        }
                    )
            },
            false
        )
        return 1
    }

    /**
     * Shows current sync consent settings.
     * AT Protocol data is always public — these controls determine whether
     * data is written to AT Protocol at all.
     */
    private fun syncConsentStatus(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException

        if (!identityStore.isLinked(player.uuid)) {
            context.source.sendFailure(
                Component.literal("§cYou are not linked to an AT Protocol identity")
                    .append(Component.literal("\n§7Use /atproto link <handle> to get started"))
            )
            return 0
        }

        val prefs = syncPreferencesStore.getOrDefault(player.uuid)

        context.source.sendSuccess(
            {
                Component.literal("§b━━━ Sync Consent ━━━")
                    .append(Component.literal("\n§7Note: AT Protocol data is §falways public§7."))
                    .append(Component.literal("\n§7These controls decide whether data is written at all."))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§7Stats syncing: ${if (prefs.syncStatsEnabled) "§aOn" else "§cOff"}"))
                    .append(Component.literal("\n§7Session syncing: ${if (prefs.syncSessionsEnabled) "§aOn" else "§cOff"}"))
                    .append(Component.literal("\n§7Achievement syncing: ${if (prefs.syncAchievementsEnabled) "§aOn" else "§cOff"}"))
                    .append(Component.literal("\n§7Server status syncing: ${if (prefs.syncServerStatusEnabled) "§aOn" else "§cOff"}"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§7Use §f/atproto sync stats <on|off>"))
                    .append(Component.literal("\n§7Use §f/atproto sync sessions <on|off>"))
                    .append(Component.literal("\n§7Use §f/atproto sync achievements <on|off>"))
                    .append(Component.literal("\n§7Use §f/atproto sync server-status <on|off>"))
            },
            false
        )
        return 1
    }

    /**
     * Sets a sync consent setting.
     */
    private fun setSyncConsent(
        context: CommandContext<CommandSourceStack>,
        syncStats: Boolean? = null,
        syncSessions: Boolean? = null,
        syncAchievements: Boolean? = null,
        syncServerStatus: Boolean? = null,
    ): Int {
        val player = context.source.playerOrException

        if (!identityStore.isLinked(player.uuid)) {
            context.source.sendFailure(
                Component.literal("§cYou are not linked to an AT Protocol identity")
            )
            return 0
        }

        syncPreferencesStore.update(
            playerId = player.uuid,
            stats = syncStats,
            sessions = syncSessions,
            achievements = syncAchievements,
            serverStatus = syncServerStatus,
        )

        val updated = syncPreferencesStore.getOrDefault(player.uuid)

        val changes = buildString {
            if (syncStats != null) {
                append("\n§7Stats syncing: ${if (updated.syncStatsEnabled) "§aOn" else "§cOff"}")
            }
            if (syncSessions != null) {
                append("\n§7Session syncing: ${if (updated.syncSessionsEnabled) "§aOn" else "§cOff"}")
            }
            if (syncAchievements != null) {
                append("\n§7Achievement syncing: ${if (updated.syncAchievementsEnabled) "§aOn" else "§cOff"}")
            }
            if (syncServerStatus != null) {
                append("\n§7Server status syncing: ${if (updated.syncServerStatusEnabled) "§aOn" else "§cOff"}")
            }
        }

        context.source.sendSuccess(
            {
                Component.literal("§a✓ Sync consent updated")
                    .append(Component.literal(changes))
            },
            false
        )

        SecurityAuditor.logSyncPreferenceChange(
            playerId = player.uuid,
            playerName = player.name.string,
            stats = updated.syncStatsEnabled,
            sessions = updated.syncSessionsEnabled,
            achievements = updated.syncAchievementsEnabled,
            serverStatus = updated.syncServerStatusEnabled,
        )

        return 1
    }

    /**
     * Shows help information for AT Protocol commands.
     */
    private fun help(context: CommandContext<CommandSourceStack>): Int {
        context.source.sendSuccess(
            {
                Component.literal("§b━━━ AT Protocol Commands (Server) ━━━")
                    .append(Component.literal("\n§f/atproto link <handle or DID>"))
                    .append(Component.literal("\n  §7Link your Minecraft account to your AT Protocol identity"))
                    .append(Component.literal("\n  §7Example: §f/atproto link alice.bsky.social"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto unlink"))
                    .append(Component.literal("\n  §7Unlink your AT Protocol identity completely"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto logout"))
                    .append(Component.literal("\n  §7Log out (removes authentication from server)"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto whoami"))
                    .append(Component.literal("\n  §7View your linked identity and authentication status"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto status"))
                    .append(Component.literal("\n  §7Check connection status"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto sync"))
                    .append(Component.literal("\n  §7View your sync consent settings"))
                    .append(Component.literal("\n§f/atproto sync stats <on|off>"))
                    .append(Component.literal("\n  §7Control whether your stats are synced"))
                    .append(Component.literal("\n§f/atproto sync sessions <on|off>"))
                    .append(Component.literal("\n  §7Control whether your sessions are synced"))
                    .append(Component.literal("\n§f/atproto sync achievements <on|off>"))
                    .append(Component.literal("\n  §7Control whether your achievements are synced"))
                    .append(Component.literal("\n§f/atproto sync server-status <on|off>"))
                    .append(Component.literal("\n  §7Control whether server status is synced"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§7Note: AT Protocol data is §falways public§7."))
                    .append(Component.literal("\n§7Turning sync off prevents data from being written."))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto whois <player or handle>"))
                    .append(Component.literal("\n  §7Look up another player's AT Protocol identity"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§e━━━ Client-Side Login (Type in Client) ━━━"))
                    .append(Component.literal("\n§eFor authentication, use the client-side commands:"))
                    .append(Component.literal("\n§f/atproto login <handle>"))
                    .append(Component.literal("\n  §7Login is handled client-side (use the mod config screen)"))
                    .append(Component.literal("\n§7Authentication happens on your computer"))
                    .append(Component.literal("\n§7Your password never goes to the server!"))
            },
            false
        )
        return 1
    }

    /**
     * Formats a timestamp into a human-readable "time since" string.
     */
    private fun formatTimeSince(timestamp: Long): String {
        val seconds = (System.currentTimeMillis() - timestamp) / 1000
        return when {
            seconds < 60 -> "$seconds seconds"
            seconds < 3600 -> "${seconds / 60} minutes"
            seconds < 86400 -> "${seconds / 3600} hours"
            else -> "${seconds / 86400} days"
        }
    }
    
    /**
     * Sanitizes error messages to avoid leaking sensitive information.
     */
    private fun sanitizeError(e: Exception): String {
        return when (e) {
            is java.net.UnknownHostException -> "Could not resolve hostname. Please check the handle or DID."
            is java.net.ConnectException -> "Connection failed. The server may be unavailable."
            is java.net.SocketTimeoutException -> "Connection timed out. Please try again."
            is javax.crypto.BadPaddingException -> "Authentication failed. Please check your credentials."
            is IllegalArgumentException -> {
                // Only show message if it's a safe validation error
                if (e.message?.contains("Invalid") == true || e.message?.contains("format") == true) {
                    e.message ?: "Invalid input"
                } else {
                    "Invalid request"
                }
            }
            else -> {
                // Log full error server-side only
                logger.error("Operation failed with ${e.javaClass.simpleName}: ${e.message}")
                "An error occurred. Please try again or contact an administrator."
            }
        }
    }
    
    /**
     * Builds a deterministic server ID from the server directory path.
     * Builds a deterministic server ID from the server directory path.
     * Matches the implementation in PlayerStatSyncService.
     */
    private fun buildServerId(server: net.minecraft.server.MinecraftServer): String {
        val serverPath = server.serverDirectory
            .toAbsolutePath()
            .normalize()
            .toString()
        val payload = "socialsync:$serverPath"
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}
