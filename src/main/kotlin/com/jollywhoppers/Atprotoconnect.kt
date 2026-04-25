package com.jollywhoppers

import com.jollywhoppers.atproto.server.AtProtoClient
import com.jollywhoppers.atproto.server.AtProtoCommands
import com.jollywhoppers.atproto.server.AtProtoSessionManager
import com.jollywhoppers.atproto.server.PlayerIdentityStore
import com.jollywhoppers.atproto.server.PlayerStatSyncService
import com.jollywhoppers.atproto.server.RecordManager
import com.jollywhoppers.security.SecurityAuditor
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object Atprotoconnect : ModInitializer {
    private val logger = LoggerFactory.getLogger("atproto-connect")
    private const val MOD_ID = "atproto-connect"

    // AT Protocol components
    lateinit var atProtoClient: AtProtoClient
        private set
    
    lateinit var identityStore: PlayerIdentityStore
        private set
    
    lateinit var sessionManager: AtProtoSessionManager
        private set

    lateinit var recordManager: RecordManager
        private set

    lateinit var statSyncService: PlayerStatSyncService
        private set
    
    lateinit var commands: AtProtoCommands
        private set
    
    // Cleanup scheduler
    private val scheduler = Executors.newScheduledThreadPool(1) { r ->
        Thread(r, "atproto-cleanup").apply { isDaemon = true }
    }

    override fun onInitialize() {
        logger.info("Initializing atproto-connect mod v${getVersion()}")

        try {
            // Get config directory
            val configDir = FabricLoader.getInstance().configDir.resolve(MOD_ID)
            
            // Initialize security auditor first
            SecurityAuditor.initialize(configDir)
            logger.info("Security auditor initialized")

            // Initialize AT Protocol client with Slingshot for PDS resolution
            atProtoClient = AtProtoClient(
                slingshotUrl = "https://slingshot.microcosm.blue",
                fallbackPdsUrl = "https://bsky.social"
            )
            logger.info("AT Protocol client initialized with Slingshot resolver")

            // Initialize identity store
            val identityStorePath = configDir.resolve("player-identities.json")
            identityStore = PlayerIdentityStore(identityStorePath)
            logger.info("Player identity store initialized at: $identityStorePath")

            // Initialize session manager (with encryption)
            val sessionStorePath = configDir.resolve("player-sessions.json")
            sessionManager = AtProtoSessionManager(sessionStorePath, atProtoClient)
            logger.info("Session manager initialized with encryption at: $sessionStorePath")

            // Initialize record manager
            recordManager = RecordManager(sessionManager)
            logger.info("Record manager initialized")

            // Initialize automatic Minecraft stat syncing
            val statSyncStatePath = configDir.resolve("minecraft-stat-sync-state.json")
            statSyncService = PlayerStatSyncService(
                recordManager = recordManager,
                sessionManager = sessionManager,
                identityStore = identityStore,
                storageFile = statSyncStatePath
            )
            logger.info("Minecraft stat sync service initialized at: $statSyncStatePath")

            // Initialize command handler (with rate limiting and audit logging)
            commands = AtProtoCommands(atProtoClient, identityStore, sessionManager)

            // Register commands
            CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
                commands.register(dispatcher)
                logger.info("AT Protocol commands registered")
            }
            
            // Register network packet handlers
            com.jollywhoppers.network.ServerNetworkHandler.register()

            // Register periodic Minecraft stat sync checks
            ServerTickEvents.END_SERVER_TICK.register { server ->
                statSyncService.onServerTick(server)
            }
            logger.info("Minecraft stat sync tick handler registered")
            
            // Schedule periodic cleanup tasks
            setupCleanupTasks()
            
            // Register server lifecycle events
            ServerLifecycleEvents.SERVER_STOPPING.register { _ ->
                onServerStopping()
            }

            logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            logger.info("atproto-connect mod successfully initialized!")
            logger.info("Security features enabled:")
            logger.info("  ✓ Token encryption (AES-256-GCM)")
            logger.info("  ✓ File permissions (owner-only)")
            logger.info("  ✓ Rate limiting (3 attempts / 15 min)")
            logger.info("  ✓ Security audit logging")
            logger.info("  ✓ Enhanced SSRF protection")
            logger.info("  ✓ Automatic Minecraft stat syncing")
            logger.info("Players can use /atproto help to see available commands")
            logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        } catch (e: Exception) {
            logger.error("Failed to initialize atproto-connect mod", e)
            logger.error("The mod will be disabled. Check the error above for details.")
        }
    }
    
    /**
     * Sets up periodic cleanup tasks for security components.
     */
    private fun setupCleanupTasks() {
        // Cleanup rate limiter every hour
        scheduler.scheduleAtFixedRate({
            try {
                commands.cleanup()
                logger.debug("Rate limiter cleanup completed")
            } catch (e: Exception) {
                logger.error("Failed to cleanup rate limiter", e)
            }
        }, 1, 1, TimeUnit.HOURS)
        
        logger.info("Periodic cleanup tasks scheduled")
    }
    
    /**
     * Called when the server is stopping.
     */
    private fun onServerStopping() {
        logger.info("Server stopping, shutting down atproto-connect components")
        
        try {
            if (::statSyncService.isInitialized) {
                statSyncService.shutdown()
            }

            // Shutdown scheduler
            scheduler.shutdown()
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow()
            }
        } catch (e: Exception) {
            logger.error("Error during shutdown", e)
        }
        
        logger.info("atproto-connect mod shut down successfully")
    }

    /**
     * Gets the mod version from the metadata.
     */
    fun getVersion(): String {
        return FabricLoader.getInstance()
            .getModContainer(MOD_ID)
            .map { it.metadata.version.friendlyString }
            .orElse("unknown")
    }
}
