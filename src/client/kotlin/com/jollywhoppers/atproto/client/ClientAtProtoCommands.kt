package com.jollywhoppers.atproto.client

import com.jollywhoppers.network.AtProtoPackets
import com.jollywhoppers.screen.AtProtoConfigScreen
import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/**
 * Client-side AT Protocol commands.
 * Handles authentication locally without sending passwords to the server.
 */
class ClientAtProtoCommands(
    private val sessionManager: ClientSessionManager
) {
    private val logger = LoggerFactory.getLogger("atproto-connect-client")
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    /**
     * Registers client-side commands.
     */
    fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(
            ClientCommandManager.literal("atproto")
                .then(
                    ClientCommandManager.literal("login")
                        .then(
                            ClientCommandManager.argument("identifier", StringArgumentType.string())
                                .then(
                                    ClientCommandManager.argument("password", StringArgumentType.greedyString())
                                        .executes { context -> login(context) }
                                )
                        )
                )
                .then(
                    ClientCommandManager.literal("logout")
                        .executes { context -> logout(context) }
                )
                .then(
                    ClientCommandManager.literal("status")
                        .executes { context -> status(context) }
                )
                .then(
                    ClientCommandManager.literal("help")
                        .executes { context -> help(context) }
                )
                .then(
                    ClientCommandManager.literal("config")
                        .executes { context -> openConfigScreen(context) }
                )
                .then(
                    ClientCommandManager.literal("gui")
                        .executes { context -> openConfigScreen(context) }
                )
                .executes { context -> help(context) }
        )
    }

    /**
     * Client-side login command.
     * Authenticates directly with AT Protocol servers, then sends session to Minecraft server.
     */
    private fun login(context: CommandContext<FabricClientCommandSource>): Int {
        val identifier = StringArgumentType.getString(context, "identifier")
        val password = StringArgumentType.getString(context, "password")

        context.source.sendFeedback(
            Component.literal("§eAuthenticating with AT Protocol...")
        )

        coroutineScope.launch {
            try {
                // Authenticate with AT Protocol servers (client-side only)
                val session = sessionManager.createSession(identifier, password).getOrThrow()

                // Send authenticated session to server for verification
                val packet = AtProtoPackets.AuthenticatePacket(
                    did = session.did,
                    handle = session.handle,
                    pdsUrl = session.pdsUrl,
                    accessJwt = session.accessJwt,
                    refreshJwt = session.refreshJwt
                )

                ClientPlayNetworking.send(packet)

                Minecraft.getInstance().gui.chat.addMessage(
                    Component.literal("§a✓ Authenticated locally!")
                        .append(Component.literal("\n§7Handle: §f${session.handle}"))
                        .append(Component.literal("\n§7DID: §f${session.did}"))
                        .append(Component.literal("\n§7Waiting for server confirmation..."))
                )

                logger.info("Authenticated as ${session.handle}, sent session to server")
            } catch (e: Exception) {
                Minecraft.getInstance().gui.chat.addMessage(
                    Component.literal("§c✗ Authentication failed")
                        .append(Component.literal("\n§7${e.message ?: "Unknown error"}"))
                        .append(Component.literal("\n\n§7Tip: Use an §fApp Password§7 from your AT Protocol account"))
                        .append(Component.literal("\n§cNever use your main account password!"))
                )
                logger.error("Authentication failed: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        return 1
    }

    /**
     * Client-side logout command.
     */
    private fun logout(context: CommandContext<FabricClientCommandSource>): Int {
        return if (sessionManager.hasSession()) {
            sessionManager.deleteSession()

            // Notify server
            val packet = AtProtoPackets.LogoutPacket()
            ClientPlayNetworking.send(packet)

            context.source.sendFeedback(
                Component.literal("§a✓ Logged out successfully")
                    .append(Component.literal("\n§7Session cleared from your computer"))
            )
            logger.info("Logged out")
            1
        } else {
            context.source.sendError(
                Component.literal("§c✗ You are not logged in")
            )
            0
        }
    }

    /**
     * Shows authentication status.
     */
    private fun status(context: CommandContext<FabricClientCommandSource>): Int {
        val hasSession = sessionManager.hasSession()

        context.source.sendFeedback(
            Component.literal("§b━━━ AT Protocol Status ━━━")
                .append(
                    if (hasSession) {
                        Component.literal("\n§aAuthentication: §f✓ Logged in locally")
                            .append(Component.literal("\n§7Session stored on your computer"))
                    } else {
                        Component.literal("\n§cAuthentication: §f✗ Not logged in")
                            .append(Component.literal("\n§7Use §f/atproto login§7 to authenticate"))
                    }
                )
        )
        return 1
    }

    /**
     * Opens the configuration/login screen.
     */
    private fun openConfigScreen(context: CommandContext<FabricClientCommandSource>): Int {
        Minecraft.getInstance().execute {
            Minecraft.getInstance().setScreen(AtProtoConfigScreen(Minecraft.getInstance().screen))
        }
        return 1
    }
    
    /**
     * Shows help information.
     */
    private fun help(context: CommandContext<FabricClientCommandSource>): Int {
        context.source.sendFeedback(
            Component.literal("§b━━━ AT Protocol Commands (Client-Side) ━━━")
                .append(Component.literal("\n§f/atproto config §7or §f/atproto gui"))
                .append(Component.literal("\n  §7Open the settings GUI (Recommended!)"))
                .append(Component.literal("\n  §7Easy login interface with no typing needed"))
                .append(Component.literal("\n"))
                .append(Component.literal("\n§f/atproto login <handle> <app-password>"))
                .append(Component.literal("\n  §7Authenticate via command (if you prefer)"))
                .append(Component.literal("\n  §7Example: §f/atproto login alice.bsky.social my-app-password"))
                .append(Component.literal("\n  §c§lIMPORTANT: Use an App Password, not your main password!"))
                .append(Component.literal("\n  §7Get one from: Settings → App Passwords → Add App Password"))
                .append(Component.literal("\n"))
                .append(Component.literal("\n§f/atproto logout"))
                .append(Component.literal("\n  §7Log out and clear your local session"))
                .append(Component.literal("\n"))
                .append(Component.literal("\n§f/atproto status"))
                .append(Component.literal("\n  §7Check your authentication status"))
                .append(Component.literal("\n"))
                .append(Component.literal("\n§e💡 Tip: You can also access settings via Mod Menu!"))
                .append(Component.literal("\n§eNote: Authentication happens entirely on your computer."))
                .append(Component.literal("\n§eYour password never leaves your machine!"))
        )
        return 1
    }
}
