package com.jollywhoppers.network

import com.jollywhoppers.security.RateLimiter
import com.jollywhoppers.security.SecurityAuditor
import com.jollywhoppers.socialsync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory

/**
 * Server-side network packet handlers for AT Protocol communication.
 * Handles verification of client-authenticated sessions.
 */
object ServerNetworkHandler {
    private val logger = LoggerFactory.getLogger("atproto-connect-server")
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    private val rateLimiter = RateLimiter()

    /**
     * Registers all server-side packet handlers.
     */
    fun register() {
        // Register packet types
        PayloadTypeRegistry.playC2S().register(
            AtProtoPackets.AuthenticatePacket.TYPE,
            AtProtoPackets.AuthenticatePacket.CODEC
        )
        PayloadTypeRegistry.playC2S().register(
            AtProtoPackets.LogoutPacket.TYPE,
            AtProtoPackets.LogoutPacket.CODEC
        )
        PayloadTypeRegistry.playS2C().register(
            AtProtoPackets.AuthenticateResponsePacket.TYPE,
            AtProtoPackets.AuthenticateResponsePacket.CODEC
        )
        
        // Handle client authentication packet
        ServerPlayNetworking.registerGlobalReceiver(AtProtoPackets.AuthenticatePacket.TYPE) { packet, context ->
            val player = context.player()
            
            logger.info("Received authentication from player ${player.name.string} for handle ${packet.handle}")
            
            // Verify the session in a coroutine
            coroutineScope.launch {
                handleAuthentication(player, packet)
            }
        }
        
        // Handle client logout packet
        ServerPlayNetworking.registerGlobalReceiver(AtProtoPackets.LogoutPacket.TYPE) { packet, context ->
            val player = context.player()
            
            logger.info("Received logout from player ${player.name.string}")
            handleLogout(player)
        }
        
        logger.info("Server network packet handlers registered")
    }

    /**
     * Handles authentication by verifying the session with AT Protocol servers.
     */
    private suspend fun handleAuthentication(player: ServerPlayer, packet: AtProtoPackets.AuthenticatePacket) {
        val rateCheck = rateLimiter.checkAttempt(player.uuid)
        if (!rateCheck.allowed) {
            sendAuthResponse(player, false, "Too many authentication attempts. Please try again later.")
            logger.warn("Rate limited authentication attempt from player ${player.name.string}")
            return
        }

        try {
            // Verify the token is valid by making a test API call
            val verifyResult = socialsync.atProtoClient.xrpcRequest(
                method = "GET",
                endpoint = "com.atproto.server.getSession",
                accessJwt = packet.accessJwt,
                pdsUrl = packet.pdsUrl
            )

            if (verifyResult.isFailure) {
                rateLimiter.recordFailure(player.uuid)
                sendAuthResponse(player, false, "Token verification failed")
                SecurityAuditor.logAuthFailure(
                    player.uuid,
                    packet.handle,
                    "Token verification failed",
                    player.name.string
                )
                return
            }

            // Token is valid, store the session
            socialsync.sessionManager.storeVerifiedSession(
                uuid = player.uuid,
                did = packet.did,
                handle = packet.handle,
                pdsUrl = packet.pdsUrl,
                accessJwt = packet.accessJwt,
                refreshJwt = packet.refreshJwt,
                authType = packet.authType,
            )

            // Link identity if not already linked
            if (!socialsync.identityStore.isLinked(player.uuid)) {
                socialsync.identityStore.linkIdentity(player.uuid, packet.did, packet.handle)
                SecurityAuditor.logIdentityLink(player.uuid, packet.handle, player.name.string)
            }

            rateLimiter.recordSuccess(player.uuid)

            // Send success response to client
            sendAuthResponse(player, true, "Successfully authenticated as ${packet.handle}")
            
            SecurityAuditor.logAuthSuccess(player.uuid, packet.handle, player.name.string)
            logger.info("Player ${player.name.string} authenticated as ${packet.handle}")

        } catch (e: Exception) {
            rateLimiter.recordFailure(player.uuid)
            sendAuthResponse(player, false, "Verification error: ${e.message}")
            logger.error("Failed to verify authentication for ${player.name.string}", e)
        }
    }

    /**
     * Handles logout by removing the player's session.
     */
    private fun handleLogout(player: ServerPlayer) {
        val identity = socialsync.identityStore.getIdentity(player.uuid)
        socialsync.sessionManager.deleteSession(player.uuid)
        
        if (identity != null) {
            SecurityAuditor.logLogout(player.uuid, identity.handle, player.name.string)
        }
        
        player.sendSystemMessage(
            Component.literal("§a✓ Logged out on server")
        )
        logger.info("Player ${player.name.string} logged out")
    }

    /**
     * Sends an authentication response to the client.
     */
    private fun sendAuthResponse(player: ServerPlayer, success: Boolean, message: String) {
        val response = AtProtoPackets.AuthenticateResponsePacket(success, message)
        ServerPlayNetworking.send(player, response)
    }
}
