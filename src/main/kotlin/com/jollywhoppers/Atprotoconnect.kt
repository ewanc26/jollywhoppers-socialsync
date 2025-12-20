package com.jollywhoppers

import com.jollywhoppers.atproto.AtProtoClient
import com.jollywhoppers.atproto.AtProtoCommands
import com.jollywhoppers.atproto.AtProtoSessionManager
import com.jollywhoppers.atproto.PlayerIdentityStore
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory

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
    
    lateinit var commands: AtProtoCommands
        private set

    override fun onInitialize() {
        logger.info("Initializing atproto-connect mod")

        try {
            // Initialize AT Protocol client with Slingshot for PDS resolution
            atProtoClient = AtProtoClient(
                slingshotUrl = "https://slingshot.microcosm.blue",
                fallbackPdsUrl = "https://bsky.social"
            )
            logger.info("AT Protocol client initialized with Slingshot resolver")

            // Initialize identity store
            val configDir = FabricLoader.getInstance().configDir
            val identityStorePath = configDir.resolve("$MOD_ID/player-identities.json")
            identityStore = PlayerIdentityStore(identityStorePath)
            logger.info("Player identity store initialized at: $identityStorePath")

            // Initialize session manager
            val sessionStorePath = configDir.resolve("$MOD_ID/player-sessions.json")
            sessionManager = AtProtoSessionManager(sessionStorePath, atProtoClient)
            logger.info("Session manager initialized at: $sessionStorePath")

            // Initialize command handler
            commands = AtProtoCommands(atProtoClient, identityStore, sessionManager)

            // Register commands
            CommandRegistrationCallback.EVENT.register { dispatcher, _, _ ->
                commands.register(dispatcher)
                logger.info("AT Protocol commands registered")
            }

            logger.info("atproto-connect mod successfully initialized!")
            logger.info("Players can use /atproto help to see available commands")
        } catch (e: Exception) {
            logger.error("Failed to initialize atproto-connect mod", e)
        }
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
