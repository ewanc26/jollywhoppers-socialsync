package com.jollywhoppers.atproto.server

import com.jollywhoppers.security.SecurityAuditor
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory
import java.util.UUID

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
    private val recordManager: RecordManager? = null,
    private val appViewService: AppViewService? = null,
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
                )
                .then(
                    Commands.literal("profile")
                        .then(
                            Commands.argument("player", StringArgumentType.greedyString())
                                .executes { context -> viewPlayerProfile(context) }
                        )
                )
                .then(
                    Commands.literal("export")
                        .then(
                            Commands.argument("player", StringArgumentType.greedyString())
                                .executes { context -> exportPlayer(context) }
                        )
                )
                .then(
                    Commands.literal("import")
                        .requires { source -> source.hasPermission(4) }
                        .then(
                            Commands.argument("url", StringArgumentType.greedyString())
                                .executes { context -> importRecord(context) }
                        )
                )
                .then(
                    Commands.literal("admin")
                        .requires { source -> source.hasPermission(4) }
                        .then(
                            Commands.literal("list")
                                .executes { context -> adminList(context) }
                        )
                        .then(
                            Commands.literal("remove")
                                .then(
                                    Commands.argument("player", StringArgumentType.greedyString())
                                        .executes { context -> adminRemove(context) }
                                )
                        )
                        .then(
                            Commands.literal("status")
                                .executes { context -> adminStatus(context) }
                        )
                        .then(
                            Commands.literal("server-login")
                                .then(
                                    Commands.argument("identifier", StringArgumentType.string())
                                        .then(
                                            Commands.argument("appPassword", StringArgumentType.string())
                                                .executes { context -> adminServerLogin(context) }
                                        )
                                )
                        )
                        .then(
                            Commands.literal("sync-stats")
                                .executes { context -> adminForceSyncStats(context) }
                        )
                        .then(
                            Commands.literal("sync-server")
                                .executes { context -> adminForceSyncServer(context) }
                        )
                        .then(
                            Commands.literal("reload-stats-filter")
                                .executes { context -> adminReloadStatsFilter(context) }
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
                    val baseComponent = Component.literal("§b━━━ AT Protocol Identity ━━━")
                        .append(Component.literal("\n§7Handle: §f${identity.handle}"))
                        .append(Component.literal("\n§7DID: §f${identity.did}"))
                        .append(Component.literal("\n§7Linked: §f$linkedAgo ago"))

                    val appView = appViewService
                    if (appView != null) {
                        val profileResult = appView.getPlayerProfile(identity.uuid)
                        val profileWithStats = profileResult.getOrNull()
                        if (profileWithStats != null) {
                            profileWithStats.profile.bio?.let { bio ->
                                baseComponent.append(Component.literal("\n§7Bio: §d$bio"))
                            }
                            profileWithStats.latestStats?.let { stats ->
                                baseComponent.append(Component.literal("\n§7Level: §a${stats.level}"))
                                baseComponent.append(Component.literal("\n§7Playtime: §a${stats.playtimeMinutes}m"))
                                baseComponent.append(Component.literal("\n§7Gamemode: §a${stats.gamemode}"))
                                baseComponent.append(Component.literal("\n§7Server: §f${stats.server}"))
                            }
                        }
                    }
                    player.sendSystemMessage(baseComponent)
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
                    .append(Component.literal("\n§eTo change these settings, use the ModMenu config screen."))
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
                    .append(Component.literal("\n§f/atproto unlink"))
                    .append(Component.literal("\n  §7Unlink your AT Protocol identity completely"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto whoami"))
                    .append(Component.literal("\n  §7View your linked identity and authentication status"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto status"))
                    .append(Component.literal("\n  §7Check connection status"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto sync"))
                    .append(Component.literal("\n  §7View your current sync consent settings"))
                    .append(Component.literal("\n  §7§eTo change settings, use the ModMenu config screen"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§7Note: AT Protocol data is §falways public§7."))
                    .append(Component.literal("\n§7Turning sync off prevents data from being written."))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto whois <player or handle>"))
                    .append(Component.literal("\n  §7Look up another player's AT Protocol identity"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto profile <player>"))
                    .append(Component.literal("\n  §7View a player's synced profile"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto export <player>"))
                    .append(Component.literal("\n  §7Export a player's synced AT Protocol records as JSON"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§f/atproto import <at-uri>"))
                    .append(Component.literal("\n  §7Fetch and index an AT Protocol record (op level 4)"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§e━━━ Admin Commands (op level 4) ━━━"))
                    .append(Component.literal("\n§f/atproto admin server-login <identifier> <app-password>"))
                    .append(Component.literal("\n  §7Authenticate server as an AT Protocol identity"))
                    .append(Component.literal("\n§f/atproto admin list"))
                    .append(Component.literal("\n  §7List all linked players"))
                    .append(Component.literal("\n§f/atproto admin remove <player>"))
                    .append(Component.literal("\n  §7Remove a player's identity link"))
                    .append(Component.literal("\n§f/atproto admin status"))
                    .append(Component.literal("\n  §7Show system status"))
                    .append(Component.literal("\n§f/atproto admin sync-stats"))
                    .append(Component.literal("\n  §7Force immediate stat sync check"))
                    .append(Component.literal("\n§f/atproto admin sync-server"))
                    .append(Component.literal("\n  §7Force immediate server status sync"))
                    .append(Component.literal("\n§f/atproto admin reload-stats-filter"))
                    .append(Component.literal("\n  §7Reload stats filter config from disk"))
                    .append(Component.literal("\n"))
                    .append(Component.literal("\n§e━━━ Configuration ━━━"))
                    .append(Component.literal("\n§eAll user-facing config (authentication, sync consent,"))
                    .append(Component.literal("\n§efrequencies, privacy) is in the ModMenu config screen."))
                    .append(Component.literal("\n§eOpen ModMenu → find Social Sync → click the config button."))
            },
            false
        )
        return 1
    }

    /**
     * Lists all linked players (admin only).
     */
    private fun adminList(context: CommandContext<CommandSourceStack>): Int {
        val identities = identityStore.getAllIdentities()

        if (identities.isEmpty()) {
            context.source.sendSuccess(
                { Component.literal("§cNo linked players found") },
                false
            )
            return 1
        }

        val message = buildString {
            append("§b━━━ Linked Players (${identities.size}) ━━━")
            identities.forEach { (uuid, identity) ->
                append("\n§7UUID: §f$uuid")
                append("\n§7Handle: §f${identity.handle}")
                append("\n§7DID: §f${identity.did}")
                append("\n")
            }
        }

        context.source.sendSuccess(
            { Component.literal(message) },
            false
        )
        return 1
    }

    /**
     * Removes a player's link and session (admin only).
     */
    private fun adminRemove(context: CommandContext<CommandSourceStack>): Int {
        val identifier = StringArgumentType.getString(context, "player")

        val minecraftPlayer = context.source.server.playerList.players
            .firstOrNull { it.name.string.equals(identifier, ignoreCase = true) }

        val identity = if (minecraftPlayer != null) {
            identityStore.getIdentity(minecraftPlayer.uuid)
        } else {
            val uuid = identityStore.getUuidByHandle(identifier)
                ?: identityStore.getUuidByDid(identifier)
            uuid?.let { identityStore.getIdentity(it) }
        }

        return if (identity != null) {
            val uuid = UUID.fromString(identity.uuid)

            if (sessionManager.hasSession(uuid)) {
                sessionManager.deleteSession(uuid)
            }

            identityStore.unlinkIdentity(uuid)

            SecurityAuditor.logSecurityEvent(
                "admin_remove",
                uuid,
                "Admin removed player ${identity.handle}",
            )

            context.source.sendSuccess(
                {
                    Component.literal("§a✓ Removed player ${identity.handle}")
                        .append(Component.literal("\n§7UUID: §f${identity.uuid}"))
                        .append(Component.literal("\n§7DID: §f${identity.did}"))
                },
                true
            )
            logger.info("Admin removed player ${identity.handle}")
            1
        } else {
            context.source.sendFailure(
                Component.literal("§cNo linked player found for: $identifier")
            )
            0
        }
    }

    /**
     * Shows system status (admin only).
     */
    private fun adminStatus(context: CommandContext<CommandSourceStack>): Int {
        val linkedCount = identityStore.getAllIdentities().size
        val activeSessions = sessionManager.getAllSessions().size

        context.source.sendSuccess(
            {
                Component.literal("§b━━━ AT Protocol System Status ━━━")
                    .append(Component.literal("\n§7Linked Players: §f$linkedCount"))
                    .append(Component.literal("\n§7Active Sessions: §f$activeSessions"))
            },
            false
        )
        return 1
    }

    /**
     * Authenticates the server as an AT Protocol identity (admin only).
     * Stores the session persistently so server status sync works without needing an online player.
     */
    private fun adminServerLogin(context: CommandContext<CommandSourceStack>): Int {
        val identifier = StringArgumentType.getString(context, "identifier")
        val appPassword = StringArgumentType.getString(context, "appPassword")

        context.source.sendSuccess(
            { Component.literal("§eAuthenticating server account with AT Protocol...") },
            false
        )

        coroutineScope.launch {
            try {
                val session = client.createSession(identifier, appPassword).getOrThrow()

                val pdsUrl = session.didDoc?.service?.firstOrNull { it.type == "AtprotoPersonalDataServer" }?.serviceEndpoint
                ServerAccount.setSession(session.accessJwt, session.refreshJwt, session.did, session.handle, pdsUrl)

                val configDir = FabricLoader.getInstance().configDir.resolve("atproto-connect")
                ServerAccount.save(configDir)

                sessionManager.storeVerifiedSession(
                    uuid = ServerAccount.SERVER_PLAYER_UUID,
                    did = session.did,
                    handle = session.handle,
                    pdsUrl = pdsUrl ?: "https://bsky.social",
                    accessJwt = session.accessJwt,
                    refreshJwt = session.refreshJwt,
                    authType = "app_password",
                )

                context.source.sendSuccess(
                    {
                        Component.literal("§a✓ Server authenticated as ${session.handle}")
                            .append(Component.literal("\n§7DID: §f${session.did}"))
                            .append(Component.literal("\n§7Server status sync will use this account"))
                    },
                    true
                )
                logger.info("Server account authenticated as ${session.handle}")
            } catch (e: Exception) {
                context.source.sendFailure(
                    Component.literal("§c✗ Failed to authenticate server account")
                        .append(Component.literal("\n§7${sanitizeError(e)}"))
                )
                logger.error("Failed to authenticate server account: ${e.javaClass.simpleName}")
            }
        }
        return 1
    }

    /**
     * Diagnostic admin command to force immediate stats evaluation for all online players.
     */
    private fun adminForceSyncStats(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val server = source.server
        
        source.sendSuccess({ Component.literal("§e[Admin] Triggering immediate stat sync check for all online players...") }, true)
        try {
            // Retrieve stat sync service directly via the singleton/registered instance in socialsync initializer
            com.jollywhoppers.socialsync.statSyncService.onServerTick(server)
            source.sendSuccess({ Component.literal("§a✓ Stat sync check completed successfully.") }, true)
        } catch (e: Exception) {
            source.sendFailure(Component.literal("§c✗ Failed to execute stat sync: ${e.message}"))
            logger.error("Admin stats force sync failed", e)
        }
        return 1
    }

    /**
     * Diagnostic admin command to force immediate server status evaluation.
     */
    private fun adminForceSyncServer(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val server = source.server
        
        source.sendSuccess({ Component.literal("§e[Admin] Triggering immediate server status sync...") }, true)
        try {
            com.jollywhoppers.socialsync.serverStatusSyncService.onSyncTick(server)
            source.sendSuccess({ Component.literal("§a✓ Server status sync completed successfully.") }, true)
        } catch (e: Exception) {
            source.sendFailure(Component.literal("§c✗ Failed to execute server sync: ${e.message}"))
            logger.error("Admin server status force sync failed", e)
        }
        return 1
    }

    /**
     * Exports a player's synced AT Protocol records as JSON (shown in chat).
     */
    private fun exportPlayer(context: CommandContext<CommandSourceStack>): Int {
        val playerName = StringArgumentType.getString(context, "player")
        val source = context.source

        coroutineScope.launch {
            try {
                val player = source.server.playerList.players
                    .firstOrNull { it.name.string.equals(playerName, ignoreCase = true) }

                val uuid = player?.uuid
                    ?: identityStore.getUuidByHandle(playerName)
                    ?: run {
                        source.sendFailure(Component.literal("§c✗ Player not found: $playerName"))
                        return@launch
                    }

                if (!sessionManager.hasSession(uuid)) {
                    source.sendFailure(Component.literal("§c✗ Player has no active AT Protocol session"))
                    return@launch
                }

                if (recordManager == null) {
                    source.sendFailure(Component.literal("§c✗ Record manager not available"))
                    return@launch
                }

                val collections = listOf(
                    AtProtoCollections.PLAYER_PROFILE,
                    AtProtoCollections.PLAYER_STATS,
                    AtProtoCollections.ACHIEVEMENT,
                    AtProtoCollections.PLAYER_SESSION,
                )

                val exportJson = buildJsonObject {
                    put("exportedAt", java.time.Instant.now().toString())
                    put("player", playerName)
                    for (collection in collections) {
                        val records = recordManager.listAllRecords(uuid, collection).getOrNull()
                        if (records != null && records.isNotEmpty()) {
                            put(collection, buildJsonArray {
                                records.forEach { record ->
                                    add(record.value)
                                }
                            })
                        }
                    }
                }

                val json = Json { prettyPrint = true }
                val jsonString = json.encodeToString(JsonObject.serializer(), exportJson)

                val truncated = if (jsonString.length > 10000) {
                    jsonString.take(10000) + "\n... (truncated)"
                } else {
                    jsonString
                }

                source.sendSuccess(
                    { Component.literal("§b━━━ Exported Records for §f$playerName §b━━━\n§7$truncated") },
                    false
                )
                logger.info("Exported AT Protocol records for player $playerName")
            } catch (e: Exception) {
                source.sendFailure(
                    Component.literal("§c✗ Failed to export records")
                        .append(Component.literal("\n§7${sanitizeError(e)}"))
                )
                logger.error("Failed to export records", e)
            }
        }
        return 1
    }

    /**
     * Fetches an AT Protocol record by AT URI and indexes it in the local AppView (admin only).
     */
    private fun importRecord(context: CommandContext<CommandSourceStack>): Int {
        val url = StringArgumentType.getString(context, "url")
        val source = context.source

        coroutineScope.launch {
            try {
                val components = recordManager?.parseAtUri(url)
                    ?: run {
                        source.sendFailure(Component.literal("§c✗ Invalid AT URI format. Expected: at://did/collection/rkey"))
                        return@launch
                    }

                source.sendSuccess(
                    { Component.literal("§eFetching record: ${components.did}/${components.collection}/${components.rkey}...") },
                    false
                )

                val recordData = client.fetchRecord(components.did, components.collection, components.rkey).getOrThrow()

                val json = Json { prettyPrint = true }
                val recordJson = json.encodeToString(JsonObject.serializer(), recordData as JsonObject)

                val appView = appViewService
                if (appView != null) {
                    appView.indexPlayerProfile(url, recordData)
                    appView.indexPlayerStats(url, recordData)
                    appView.indexAchievement(url, recordData)
                }

                val truncated = if (recordJson.length > 5000) {
                    recordJson.take(5000) + "\n... (truncated)"
                } else {
                    recordJson
                }

                source.sendSuccess(
                    {
                        Component.literal("§a✓ Record imported successfully")
                            .append(Component.literal("\n§7URI: §f$url"))
                            .append(Component.literal("\n§7Indexed in AppView: §f${appView != null}"))
                            .append(Component.literal("\n§7Record:\n$truncated"))
                    },
                    true
                )
                logger.info("Imported record from $url")
            } catch (e: Exception) {
                source.sendFailure(
                    Component.literal("§c✗ Failed to import record")
                        .append(Component.literal("\n§7${sanitizeError(e)}"))
                )
                logger.error("Failed to import record from $url", e)
            }
        }
        return 1
    }

    /**
     * Queries and displays detailed profile bio and synced stats for a player in game chat.
     */
    private fun viewPlayerProfile(context: CommandContext<CommandSourceStack>): Int {
        val identifier = StringArgumentType.getString(context, "player")
        val source = context.source

        coroutineScope.launch {
            try {
                val minecraftPlayer = source.server.playerList.players
                    .firstOrNull { it.name.string.equals(identifier, ignoreCase = true) }

                val uuid = if (minecraftPlayer != null) {
                    minecraftPlayer.uuid
                } else {
                    identityStore.getUuidByHandle(identifier)
                        ?: identityStore.getUuidByDid(identifier)
                        ?: run {
                            source.sendFailure(Component.literal("§c✗ Player identity or link not found for: $identifier"))
                            return@launch
                        }
                }

                val identity = identityStore.getIdentity(uuid) ?: run {
                    source.sendFailure(Component.literal("§c✗ Player has no identity link"))
                    return@launch
                }

                val appView = appViewService
                if (appView == null) {
                    source.sendFailure(Component.literal("§c✗ AppView service not available"))
                    return@launch
                }

                val profileResult = appView.getPlayerProfile(uuid.toString())
                val profileWithStats = profileResult.getOrNull()

                if (profileWithStats != null) {
                    val component = Component.literal("§b━━━ Profile: ${profileWithStats.profile.displayName ?: identity.handle} ━━━")
                        .append(Component.literal("\n§7Handle: §f${identity.handle}"))
                        .append(Component.literal("\n§7DID: §f${identity.did}"))
                    
                    profileWithStats.profile.bio?.let { bio ->
                        component.append(Component.literal("\n§7Bio: §d$bio"))
                    }

                    profileWithStats.latestStats?.let { stats ->
                        component.append(Component.literal("\n§7Level: §a${stats.level}"))
                        component.append(Component.literal("\n§7Playtime: §a${stats.playtimeMinutes}m"))
                        component.append(Component.literal("\n§7Gamemode: §a${stats.gamemode}"))
                        component.append(Component.literal("\n§7Last Synced Server: §f${stats.server}"))
                        component.append(Component.literal("\n§7Synced At: §7${stats.syncedAt}"))
                    }

                    component.append(Component.literal("\n§7Synced Achievements: §e${profileWithStats.achievementCount}"))
                    source.sendSuccess({ component }, false)
                } else {
                    source.sendFailure(Component.literal("§c✗ No synced profile records found in AppView index for ${identity.handle}"))
                }
            } catch (e: Exception) {
                source.sendFailure(Component.literal("§c✗ Error viewing profile: ${sanitizeError(e)}"))
            }
        }

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
     * Reloads the stats filter config from disk at runtime (admin only).
     * Server operators can edit `config/atproto-connect/stats-filter-config.json`
     * and use this command to apply changes without a server restart.
     */
    private fun adminReloadStatsFilter(context: CommandContext<CommandSourceStack>): Int {
        val source = context.source
        val configDir = FabricLoader.getInstance().configDir.resolve("atproto-connect")
        try {
            com.jollywhoppers.socialsync.statSyncService.reloadFilterConfig(configDir)
            source.sendSuccess(
                { Component.literal("§a✓ Stats filter config reloaded from disk") },
                true
            )
            logger.info("Stats filter config reloaded by admin")
            return 1
        } catch (e: Exception) {
            source.sendFailure(
                Component.literal("§c✗ Failed to reload stats filter config: ${e.message}")
            )
            logger.error("Failed to reload stats filter config", e)
            return 0
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
}
