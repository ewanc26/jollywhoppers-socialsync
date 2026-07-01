package com.jollywhoppers.paper

import com.jollywhoppers.atproto.server.AtProtoClient
import com.jollywhoppers.atproto.server.AppViewHttpServer
import com.jollywhoppers.atproto.server.AppViewService
import com.jollywhoppers.atproto.server.FirehoseSubscriber
import com.jollywhoppers.atproto.server.AtProtoSessionManager
import com.jollywhoppers.atproto.server.AchievementSyncStore
import com.jollywhoppers.atproto.server.PlayerIdentityStore
import com.jollywhoppers.atproto.server.RecordManager
import com.jollywhoppers.atproto.server.ServerAccount
import com.jollywhoppers.security.SecurityAuditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

class SocialSyncPaperPlugin : JavaPlugin(), CommandExecutor, TabCompleter {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var atProtoClient: AtProtoClient
    private lateinit var identityStore: PlayerIdentityStore
    private lateinit var sessionManager: AtProtoSessionManager
    private lateinit var recordManager: RecordManager
    private lateinit var firehoseSubscriber: FirehoseSubscriber
    private lateinit var appViewServer: AppViewHttpServer

    override fun onEnable() {
        dataFolder.mkdirs()
        val dataPath = dataFolder.toPath()
        SecurityAuditor.initialize(dataPath)
        atProtoClient = AtProtoClient(
            slingshotUrl = "https://slingshot.microcosm.blue",
            fallbackPdsUrl = "https://bsky.social",
        )
        ServerAccount.load(dataPath)
        sessionManager = AtProtoSessionManager(dataPath.resolve("player-sessions.json"), atProtoClient)
        ServerAccount.getSession().onSuccess { session ->
            runCatching {
                runBlocking { atProtoClient.resolveIdentifier(session.did).getOrThrow().third }
            }.onSuccess { pdsUrl ->
                sessionManager.storeVerifiedSession(
                    uuid = ServerAccount.SERVER_PLAYER_UUID,
                    did = session.did,
                    handle = session.handle,
                    pdsUrl = pdsUrl,
                    accessJwt = session.accessJwt,
                    refreshJwt = session.refreshJwt,
                )
            }.onFailure { error ->
                logger.warning("Could not resolve the server account PDS through Slingshot: ${error.message}")
            }
        }
        recordManager = RecordManager(atProtoClient.xrpcClient, atProtoClient.json, sessionManager)
        identityStore = PlayerIdentityStore(
            dataPath.resolve("player-identities.json"),
            recordManager,
            sessionManager,
        )
        val appViewService = AppViewService(recordManager)
        firehoseSubscriber = FirehoseSubscriber(appViewService).also { it.start() }
        appViewServer = AppViewHttpServer(appViewService).also { it.start() }
        server.pluginManager.registerEvents(
            PaperAchievementTracker(
                plugin = this,
                scope = scope,
                recordManager = recordManager,
                syncStore = AchievementSyncStore(dataPath.resolve("achievement-sync-state.json")),
            ),
            this,
        )
        val serverDataTracker = PaperServerDataTracker(this, scope, recordManager)
        server.pluginManager.registerEvents(serverDataTracker, this)
        server.globalRegionScheduler.runAtFixedRate(
            this,
            { serverDataTracker.publishServerStatus() },
            20L,
            6000L,
        )
        server.globalRegionScheduler.runAtFixedRate(
            this,
            { serverDataTracker.publishPlayerStats() },
            1200L,
            72000L,
        )

        requireNotNull(getCommand("atproto")) { "atproto command missing from plugin.yml" }.also {
            it.setExecutor(this)
            it.tabCompleter = this
        }
        logger.info("Social Sync ${pluginMeta.version} enabled for Paper ${server.minecraftVersion}")
    }

    override fun onDisable() {
        if (::appViewServer.isInitialized) appViewServer.stop()
        if (::firehoseSubscriber.isInitialized) firehoseSubscriber.shutdown()
        scope.cancel("Plugin disabled")
        logger.info("Social Sync disabled")
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("socialsync.use")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED))
            return true
        }
        return when (args.firstOrNull()?.lowercase()) {
            "admin" -> admin(sender, args.drop(1))
            "link" -> link(sender, args.drop(1).joinToString(" ").trim())
            "unlink" -> unlink(sender)
            "whoami" -> whoami(sender)
            "status" -> status(sender)
            else -> help(sender)
        }
    }

    private fun admin(sender: CommandSender, args: List<String>): Boolean {
        if (!sender.hasPermission("socialsync.admin")) {
            sender.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED))
            return true
        }
        if (args.firstOrNull()?.lowercase() != "server-login" || args.size != 3) {
            sender.sendMessage(Component.text("Usage: /atproto admin server-login <handle or DID> <app-password>", NamedTextColor.YELLOW))
            return true
        }
        val identifier = args[1]
        val password = args[2]
        sender.sendMessage(Component.text("Authenticating the server AT Protocol account…", NamedTextColor.YELLOW))
        scope.launch {
            val result = atProtoClient.createSession(identifier, password).mapCatching { session ->
                val pdsUrl = atProtoClient.resolveIdentifier(session.did).getOrThrow().third
                ServerAccount.setSession(session.accessJwt, session.refreshJwt, session.did, session.handle, pdsUrl)
                ServerAccount.save(dataFolder.toPath())
                sessionManager.storeVerifiedSession(
                    ServerAccount.SERVER_PLAYER_UUID,
                    session.did,
                    session.handle,
                    pdsUrl,
                    session.accessJwt,
                    session.refreshJwt,
                )
                session
            }
            server.globalRegionScheduler.execute(this@SocialSyncPaperPlugin) {
                result.onSuccess {
                    sender.sendMessage(Component.text("Server account authenticated as ${it.handle}.", NamedTextColor.GREEN))
                }.onFailure {
                    sender.sendMessage(Component.text("Server account authentication failed.", NamedTextColor.RED))
                    logger.warning("Server AT Protocol login failed: ${it.javaClass.simpleName}")
                }
            }
        }
        return true
    }

    private fun link(sender: CommandSender, identifier: String): Boolean {
        val player = sender as? Player ?: return playerOnly(sender)
        if (identifier.isBlank()) {
            sender.sendMessage(Component.text("Usage: /atproto link <handle or DID>", NamedTextColor.YELLOW))
            return true
        }
        sender.sendMessage(Component.text("Verifying AT Protocol identity…", NamedTextColor.YELLOW))
        scope.launch {
            val result = atProtoClient.resolveIdentifier(identifier).mapCatching { (did, _, pdsUrl) ->
                val profile = atProtoClient.getProfile(did, pdsUrl).getOrThrow()
                identityStore.linkIdentity(player.uniqueId, profile.did, profile.handle)
                profile
            }
            server.globalRegionScheduler.execute(this@SocialSyncPaperPlugin) {
                result.onSuccess { profile ->
                    player.sendMessage(Component.text("Linked to ${profile.handle} (${profile.did})", NamedTextColor.GREEN))
                    SecurityAuditor.logIdentityLink(player.uniqueId, profile.handle, player.name)
                }.onFailure { error ->
                    player.sendMessage(Component.text("Unable to link identity: ${safeMessage(error)}", NamedTextColor.RED))
                    logger.warning("Identity link failed for ${player.uniqueId}: ${error.javaClass.simpleName}")
                }
            }
        }
        return true
    }

    private fun unlink(sender: CommandSender): Boolean {
        val player = sender as? Player ?: return playerOnly(sender)
        val identity = identityStore.getIdentity(player.uniqueId)
        if (identity == null) {
            sender.sendMessage(Component.text("No AT Protocol identity is linked.", NamedTextColor.YELLOW))
        } else {
            identityStore.unlinkIdentity(player.uniqueId)
            SecurityAuditor.logIdentityUnlink(player.uniqueId, identity.handle, player.name)
            sender.sendMessage(Component.text("Unlinked ${identity.handle}.", NamedTextColor.GREEN))
        }
        return true
    }

    private fun whoami(sender: CommandSender): Boolean {
        val player = sender as? Player ?: return playerOnly(sender)
        val identity = identityStore.getIdentity(player.uniqueId)
        sender.sendMessage(
            if (identity == null) Component.text("No AT Protocol identity is linked.", NamedTextColor.YELLOW)
            else Component.text("${identity.handle} (${identity.did})", NamedTextColor.AQUA)
        )
        return true
    }

    private fun status(sender: CommandSender): Boolean {
        sender.sendMessage(Component.text("Social Sync ${pluginMeta.version}", NamedTextColor.AQUA))
        sender.sendMessage(Component.text("Linked players: ${identityStore.getAllIdentities().size}", NamedTextColor.GRAY))
        sender.sendMessage(Component.text("Paper API target: 26.1.2", NamedTextColor.GRAY))
        return true
    }

    private fun help(sender: CommandSender): Boolean {
        sender.sendMessage(Component.text("/atproto link <handle or DID>", NamedTextColor.AQUA))
        sender.sendMessage(Component.text("/atproto unlink | whoami | status", NamedTextColor.AQUA))
        return true
    }

    private fun playerOnly(sender: CommandSender): Boolean {
        sender.sendMessage(Component.text("This command can only be used by a player.", NamedTextColor.RED))
        return true
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>,
    ): List<String> = if (args.size == 1) {
        listOf("link", "unlink", "whoami", "status", "help", "admin").filter { it.startsWith(args[0], true) }
    } else emptyList()

    private fun safeMessage(error: Throwable): String = when {
        error.message.isNullOrBlank() -> "request failed"
        error.message!!.length > 160 -> error.message!!.take(160)
        else -> error.message!!
    }
}
