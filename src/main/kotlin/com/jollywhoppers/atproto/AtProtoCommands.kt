package com.jollywhoppers.atproto

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

/**
 * Handles AT Protocol-related commands for players.
 * Provides commands to link, authenticate, and manage AT Protocol identities.
 */
class AtProtoCommands(
    private val client: AtProtoClient,
    private val identityStore: PlayerIdentityStore,
    private val sessionManager: AtProtoSessionManager
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
                    Commands.literal("login")
                        .then(
                            Commands.argument("identifier", StringArgumentType.string())
                                .then(
                                    Commands.argument("password", StringArgumentType.greedyString())
                                        .executes { context -> login(context) }
                                )
                        )
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
                        .append(Component.literal("\n\n§eNote: Use §f/atproto login§e to authenticate and sync data"))
                )

                logger.info("Player ${player.name.string} (${player.uuid}) linked to ${profile.handle}")
            } catch (e: Exception) {
                player.sendSystemMessage(
                    Component.literal("§c✗ Failed to link AT Protocol identity")
                        .append(Component.literal("\n§7${e.message ?: "Unknown error"}"))
                )
                logger.error("Failed to link identity for player ${player.name.string}", e)
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
     * Authenticates a player with their AT Protocol credentials.
     * Uses app passwords for security.
     */
    private fun login(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        val identifier = StringArgumentType.getString(context, "identifier")
        val password = StringArgumentType.getString(context, "password")

        context.source.sendSuccess(
            { Component.literal("§eAuthenticating with AT Protocol...") },
            false
        )

        coroutineScope.launch {
            try {
                // Create session
                val session = sessionManager.createSession(player.uuid, identifier, password).getOrThrow()

                // Link identity if not already linked
                if (!identityStore.isLinked(player.uuid)) {
                    identityStore.linkIdentity(player.uuid, session.did, session.handle)
                }

                player.sendSystemMessage(
                    Component.literal("§a✓ Successfully authenticated!")
                        .append(Component.literal("\n§7Handle: §f${session.handle}"))
                        .append(Component.literal("\n§7DID: §f${session.did}"))
                        .append(Component.literal("\n§7PDS: §f${session.pdsUrl}"))
                        .append(Component.literal("\n\n§aYou can now sync your Minecraft data to AT Protocol!"))
                )

                logger.info("Player ${player.name.string} (${player.uuid}) authenticated as ${session.handle}")
            } catch (e: Exception) {
                player.sendSystemMessage(
                    Component.literal("§c✗ Authentication failed")
                        .append(Component.literal("\n§7${e.message ?: "Unknown error"}"))
                        .append(Component.literal("\n\n§7Tip: Use an §fApp Password§7 from your AT Protocol account settings"))
                        .append(Component.literal("\n§7Never use your main account password!"))
                )
                logger.error("Failed to authenticate player ${player.name.string}", e)
            }
        }

        return 1
    }

    /**
     * Logs out a player (removes their authentication session).
     */
    private fun logout(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.playerOrException
        
        return if (sessionManager.hasSession(player.uuid)) {
            sessionManager.deleteSession(player.uuid)
            context.source.sendSuccess(
                {
                    Component.literal("§a✓ Logged out successfully")
                        .append(Component.literal("\n§7Your identity link remains active"))
                        .append(Component.literal("\n§7Use §f/atproto login§7 to authenticate again"))
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
                                Component.literal("\n§aAuthentication: §f✓ Active")
                                    .append(Component.literal("\n§7You can sync data to AT Protocol"))
                            } else {
                                Component.literal("\n§cAuthentication: §f✗ Not logged in")
                                    .append(Component.literal("\n§7Use §f/atproto login§7 to authenticate"))
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
                logger.error("Error in whois command", e)
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
                            Component.literal("\n\n§eUse §f/atproto login§e to authenticate")
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
     * Shows help information for AT Protocol commands.
     */
    private fun help(context: CommandContext<CommandSourceStack>): Int {
        context.source.sendSuccess(
            {
                Component.literal("§b━━━ AT Protocol Commands ━━━")
                    .append(Component.literal("\n§f/atproto link <handle or DID>"))
                    .append(Component.literal("\n  §7Link your Minecraft account to your AT Protocol identity"))
                    .append(Component.literal("\n  §7Example: §f/atproto link alice.bsky.social"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto login <handle> <app-password>"))
                    .append(Component.literal("\n  §7Authenticate to enable data syncing"))
                    .append(Component.literal("\n  §7§cUse an App Password, not your main password!"))
                    .append(Component.literal("\n  §7Get one from: Settings → App Passwords"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto logout"))
                    .append(Component.literal("\n  §7Log out (removes authentication, keeps identity link)"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto unlink"))
                    .append(Component.literal("\n  §7Unlink your AT Protocol identity completely"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto whoami"))
                    .append(Component.literal("\n  §7View your linked identity and authentication status"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto status"))
                    .append(Component.literal("\n  §7Check connection status"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto whois <player or handle>"))
                    .append(Component.literal("\n  §7Look up another player's AT Protocol identity"))
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
}
