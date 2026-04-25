package com.jollywhoppers.atproto.client

import com.jollywhoppers.Atprotoconnect
import com.jollywhoppers.atproto.oauth.OAuthManager
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
 * Supports both OAuth (browser-based) and app-password login.
 */
class ClientAtProtoCommands(
    private val sessionManager: ClientSessionManager,
    private val oAuthManager: OAuthManager,
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
                        // OAuth login: /atproto login <handle>
                        .then(
                            ClientCommandManager.argument("identifier", StringArgumentType.string())
                                .then(
                                    ClientCommandManager.argument("password", StringArgumentType.greedyString())
                                        .executes { context -> loginWithAppPassword(context) }
                                )
                                .executes { context -> loginWithOAuth(context) }
                        )
                )
                .then(
                    ClientCommandManager.literal("oauth")
                        .then(
                            ClientCommandManager.argument("identifier", StringArgumentType.string())
                                .executes { context -> loginWithOAuth(context) }
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
     * OAuth browser-based login.
     * Opens the user's browser for ATProto OAuth authorization.
     * No password is typed in Minecraft — authentication happens entirely in the browser.
     */
    private fun loginWithOAuth(context: CommandContext<FabricClientCommandSource>): Int {
        val identifier = StringArgumentType.getString(context, "identifier")

        context.source.sendFeedback(
            Component.literal("§eOpening browser for AT Protocol OAuth...")
                .append(Component.literal("\n§7Authorising as: §f$identifier"))
                .append(Component.literal("\n§7Please complete login in your browser"))
        )

        coroutineScope.launch {
            try {
                val result = oAuthManager.authorize(identifier).getOrThrow()
                val session = result.session

                // Send authenticated session to server for verification
                val packet = AtProtoPackets.AuthenticatePacket(
                    did = session.did,
                    handle = session.handle,
                    pdsUrl = session.pdsUrl,
                    accessJwt = session.accessToken,
                    refreshJwt = session.refreshToken,
                    authType = "oauth",
                )

                ClientPlayNetworking.send(packet)

                // Store OAuth session locally
                sessionManager.storeOAuthSession(session, result.dpopKeyPair)

                Minecraft.getInstance().execute {
                    Minecraft.getInstance().gui.chat.addMessage(
                        Component.literal("§a✓ OAuth authorisation successful!")
                            .append(Component.literal("\n§7Handle: §f${session.handle}"))
                            .append(Component.literal("\n§7DID: §f${session.did}"))
                            .append(Component.literal("\n§7PDS: §f${session.pdsUrl}"))
                            .append(Component.literal("\n§7Scope: §f${session.scope}"))
                            .append(Component.literal("\n§eWaiting for server confirmation..."))
                    )
                }

                logger.info("OAuth authenticated as ${session.handle}, sent session to server")
            } catch (e: Exception) {
                Minecraft.getInstance().execute {
                    Minecraft.getInstance().gui.chat.addMessage(
                        Component.literal("§c✗ OAuth authorisation failed")
                            .append(Component.literal("\n§7${e.message ?: "Unknown error"}"))
                            .append(Component.literal("\n\n§7Try: §f/atproto login <handle> <app-password>"))
                            .append(Component.literal("\n§7to use an app password instead"))
                    )
                }
                logger.error("OAuth authorisation failed: ${e.javaClass.simpleName} - ${e.message}")
            }
        }

        return 1
    }

    /**
     * App-password login (fallback).
     * Authenticates directly with AT Protocol servers using an app password.
     */
    private fun loginWithAppPassword(context: CommandContext<FabricClientCommandSource>): Int {
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
                    refreshJwt = session.refreshJwt,
                )

                ClientPlayNetworking.send(packet)

                Minecraft.getInstance().execute {
                    Minecraft.getInstance().gui.chat.addMessage(
                        Component.literal("§a✓ Authenticated locally!")
                            .append(Component.literal("\n§7Handle: §f${session.handle}"))
                            .append(Component.literal("\n§7DID: §f${session.did}"))
                            .append(Component.literal("\n§7Waiting for server confirmation..."))
                    )
                }

                logger.info("Authenticated as ${session.handle}, sent session to server")
            } catch (e: Exception) {
                Minecraft.getInstance().execute {
                    Minecraft.getInstance().gui.chat.addMessage(
                        Component.literal("§c✗ Authentication failed")
                            .append(Component.literal("\n§7${e.message ?: "Unknown error"}"))
                            .append(Component.literal("\n\n§7Tip: Use an §fApp Password§7 from your AT Protocol account"))
                            .append(Component.literal("\n§cNever use your main account password!"))
                            .append(Component.literal("\n\n§7Or try OAuth: §f/atproto login <handle>"))
                    )
                }
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
        val isOAuth = sessionManager.isOAuthSession()

        context.source.sendFeedback(
            Component.literal("§b━━━ AT Protocol Status ━━━")
                .append(
                    if (hasSession) {
                        val authType = if (isOAuth) "OAuth" else "App Password"
                        Component.literal("\n§aAuthentication: §f✓ Logged in ($authType)")
                            .append(Component.literal("\n§7Session stored on your computer"))
                    } else {
                        Component.literal("\n§cAuthentication: §f✗ Not logged in")
                            .append(Component.literal("\n§7Use §f/atproto login <handle>§7 for OAuth"))
                            .append(Component.literal("\n§7Use §f/atproto login <handle> <password>§7 for app password"))
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
                .append(Component.literal("\n§f/atproto login <handle>"))
                .append(Component.literal("\n  §7OAuth browser login (Recommended!)"))
                .append(Component.literal("\n  §7Opens your browser for secure authorisation"))
                .append(Component.literal("\n  §7No password needed in Minecraft"))
                .append(Component.literal("\n  §7Example: §f/atproto login alice.bsky.social"))
                .append(Component.literal("\n"))
                .append(Component.literal("\n§f/atproto login <handle> <app-password>"))
                .append(Component.literal("\n  §7App password login (fallback)"))
                .append(Component.literal("\n  §7Example: §f/atproto login alice.bsky.social abcd-1234"))
                .append(Component.literal("\n  §c§lIMPORTANT: Use an App Password, not your main password!"))
                .append(Component.literal("\n"))
                .append(Component.literal("\n§f/atproto oauth <handle>"))
                .append(Component.literal("\n  §7Explicit OAuth login command"))
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
