package com.jollywhoppers

import com.jollywhoppers.atproto.client.ClientAtProtoClient
import com.jollywhoppers.atproto.client.ClientAtProtoCommands
import com.jollywhoppers.atproto.client.ClientSessionManager
import com.jollywhoppers.atproto.oauth.OAuthManager
import com.jollywhoppers.config.PreferencesManager
import com.jollywhoppers.network.AtProtoPackets
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

object socialsyncClient : ClientModInitializer {
	private val logger = LoggerFactory.getLogger("atproto-connect-client")
	
	// Client-side AT Protocol components
	lateinit var atProtoClient: ClientAtProtoClient
		private set

	lateinit var sessionManager: ClientSessionManager
		private set

	lateinit var oAuthManager: OAuthManager
		private set

	lateinit var commands: ClientAtProtoCommands
		private set

	override fun onInitializeClient() {
		logger.info("Initializing atproto-connect client-side components")
		
		try {
			// Initialize client-side AT Protocol client
			atProtoClient = ClientAtProtoClient(
				identityServiceUrl = "https://bsky.social"
			)
			logger.info("Client-side AT Protocol client initialized")
			
			// Initialize client-side session manager
			sessionManager = ClientSessionManager(atProtoClient)
			logger.info("Client-side session manager initialized")

			// Initialize OAuth manager (uses DiscoveryChain from kikin81 library)
			oAuthManager = OAuthManager()
			logger.info("OAuth manager initialized")

			// Initialize client-side commands (help only — all config via ModMenu)
			commands = ClientAtProtoCommands()
			
			// Register client-side commands
			ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
				commands.register(dispatcher)
				logger.info("Client-side AT Protocol commands registered")
			}
			
			// Register network packet receivers
			registerNetworkHandlers()
			
			logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
			logger.info("atproto-connect client successfully initialized!")
			logger.info("Security features:")
			logger.info("  [OK] Client-side authentication")
			logger.info("  [OK] Passwords never sent to server")
			logger.info("  [OK] Local session storage")
			logger.info("  [OK] OAuth browser-based login")
			logger.info("  [OK] DPoP proof of possession")
			logger.info("Use /atproto help to see available commands")
			logger.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
		} catch (e: Exception) {
			logger.error("Failed to initialize atproto-connect client", e)
		}
	}
	
	/**
	 * Registers handlers for server -> client packets.
	 */
	private fun registerNetworkHandlers() {
		// Register packet types
		PayloadTypeRegistry.playS2C().register(
			AtProtoPackets.AuthenticateResponsePacket.TYPE,
			AtProtoPackets.AuthenticateResponsePacket.CODEC
		)
		
		// Handle authentication response from server
		ClientPlayNetworking.registerGlobalReceiver(AtProtoPackets.AuthenticateResponsePacket.TYPE) { packet, context ->
			context.client().execute {
				if (packet.success) {
					if (PreferencesManager.get().showSyncNotifications) {
						Minecraft.getInstance().gui.chat.addMessage(
							Component.literal("§a[SUCCESS] Server confirmed authentication!")
								.append(Component.literal("\n§7${packet.message}"))
								.append(Component.literal("\n§aYou can now sync your Minecraft data to AT Protocol!"))
						)
					}
					logger.info("Server confirmed authentication: ${packet.message}")
				} else {
					if (PreferencesManager.get().showSyncNotifications) {
						Minecraft.getInstance().gui.chat.addMessage(
							Component.literal("§c[FAILED] Server rejected authentication")
								.append(Component.literal("\n§7${packet.message}"))
						)
					}
					logger.error("Server rejected authentication: ${packet.message}")
				}
			}
		}
		
		logger.info("Network packet handlers registered")
	}
}
