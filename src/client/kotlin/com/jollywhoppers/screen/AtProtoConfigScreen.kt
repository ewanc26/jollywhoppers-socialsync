package com.jollywhoppers.screen

import com.jollywhoppers.AtprotoconnectClient
import com.jollywhoppers.atproto.oauth.OAuthManager
import com.jollywhoppers.network.AtProtoPackets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

/**
 * Configuration screen for ATProto Connect.
 * Provides GUI-based authentication with both OAuth and app-password login.
 *
 * Security Features:
 * - Client-side only authentication
 * - Passwords never sent to server
 * - OAuth browser-based login (recommended)
 * - App-password fallback
 * - Local session storage
 * - Clear visual feedback
 */
class AtProtoConfigScreen(private val parent: Screen?) : Screen(Component.literal("SocialSync Settings")) {

    private val logger = LoggerFactory.getLogger("atproto-connect-ui")
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // UI Components
    private var handleField: EditBox? = null
    private var passwordField: EditBox? = null
    private var oauthButton: Button? = null
    private var loginButton: Button? = null
    private var logoutButton: Button? = null
    private var statusText: Component? = null
    private var isAuthenticating = false

    // Session state
    private val sessionManager = AtprotoconnectClient.sessionManager
    private val oAuthManager = AtprotoconnectClient.oAuthManager

    override fun init() {
        super.init()

        val centerX = width / 2
        val startY = 60

        // Handle input field
        handleField = EditBox(
            minecraft!!.font,
            centerX - 150,
            startY,
            300,
            20,
            Component.literal("Handle or DID")
        ).apply {
            setHint(Component.literal("alice.bsky.social or did:plc:..."))
            setMaxLength(256)

            if (!sessionManager.hasSession()) {
                value = ""
            }
        }
        addRenderableWidget(handleField!!)

        // Password input field (for app-password fallback)
        passwordField = EditBox(
            minecraft!!.font,
            centerX - 150,
            startY + 30,
            300,
            20,
            Component.literal("App Password")
        ).apply {
            setHint(Component.literal("App Password (optional — for fallback login)"))
            setMaxLength(256)
        }
        addRenderableWidget(passwordField!!)

        // OAuth Login button (recommended, primary)
        oauthButton = Button.builder(
            Component.literal("OAuth Login (Recommended)"),
            Button.OnPress { onOAuthClicked() }
        )
            .bounds(centerX - 155, startY + 60, 150, 20)
            .build()
            .also { addRenderableWidget(it) }

        // App-password Login button (fallback)
        loginButton = Button.builder(
            Component.literal("App Password Login"),
            Button.OnPress { onLoginClicked() }
        )
            .bounds(centerX + 5, startY + 60, 150, 20)
            .build()
            .also { addRenderableWidget(it) }

        // Logout button
        logoutButton = Button.builder(
            Component.literal("Logout"),
            Button.OnPress { onLogoutClicked() }
        )
            .bounds(centerX - 75, startY + 90, 150, 20)
            .build()
            .also { addRenderableWidget(it) }

        // Done button
        addRenderableWidget(
            Button.builder(
                Component.literal("Done"),
                Button.OnPress { onClose() }
            )
                .bounds(centerX - 75, height - 30, 150, 20)
                .build()
        )

        // Help button
        addRenderableWidget(
            Button.builder(
                Component.literal("Help"),
                Button.OnPress { onHelpClicked() }
            )
                .bounds(centerX + 85, height - 30, 70, 20)
                .build()
        )

        updateUIState()
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        // Render background
        renderBackground(graphics, mouseX, mouseY, partialTick)

        // Render title
        graphics.drawCenteredString(
            font,
            title,
            width / 2,
            15,
            0xFFFFFF
        )

        // Render labels
        graphics.drawString(
            font,
            "Handle or DID:",
            width / 2 - 150,
            48,
            0xA0A0A0
        )

        graphics.drawString(
            font,
            "App Password (optional):",
            width / 2 - 150,
            78,
            0xA0A0A0
        )

        // Render status
        statusText?.let { status ->
            val lines = font.split(status, width - 40)
            var y = 130
            for (line in lines) {
                graphics.drawCenteredString(
                    font,
                    line,
                    width / 2,
                    y,
                    0xFFFFFF
                )
                y += 12
            }
        }

        // Render security notice
        val securityNotice = if (sessionManager.isOAuthSession()) {
            Component.literal("OAuth session active — your data is secure")
        } else if (sessionManager.hasSession()) {
            Component.literal("App-password session — your password never leaves your computer")
        } else {
            Component.literal("OAuth is recommended — no password needed!")
        }
        graphics.drawCenteredString(
            font,
            securityNotice,
            width / 2,
            height - 50,
            0x40FF40
        )

        super.render(graphics, mouseX, mouseY, partialTick)
    }

    /**
     * OAuth browser-based login.
     * Opens the user's browser for ATProto OAuth authorization.
     */
    private fun onOAuthClicked() {
        val handle = handleField?.value?.trim() ?: ""

        if (handle.isEmpty()) {
            statusText = Component.literal("§cPlease enter your handle or DID")
            return
        }

        isAuthenticating = true
        oauthButton?.active = false
        loginButton?.active = false
        logoutButton?.active = false
        statusText = Component.literal("§eOpening browser for OAuth...")
            .append(Component.literal("\n§7Please complete login in your browser"))

        coroutineScope.launch {
            try {
                val result = oAuthManager.authorize(handle).getOrThrow()
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

                minecraft?.execute {
                    statusText = Component.literal("§aOAuth authorisation successful!")
                        .append(Component.literal("\n§7Handle: §f${session.handle}"))
                        .append(Component.literal("\n§7DID: §f${session.did}"))
                        .append(Component.literal("\n§7Scope: §f${session.scope}"))
                        .append(Component.literal("\n§eWaiting for server confirmation..."))

                    // Also send to chat for better visibility
                    minecraft?.gui?.chat?.addMessage(
                        Component.literal("§aOAuth login successful!")
                            .append(Component.literal("\n§7Handle: §f${session.handle}"))
                    )

                    passwordField?.value = ""
                    updateUIState()
                    logger.info("OAuth authenticated as ${session.handle}, sent session to server")
                }
            } catch (e: Exception) {
                minecraft?.execute {
                    statusText = Component.literal("§cOAuth authorisation failed")
                        .append(Component.literal("\n§7${e.message ?: "Unknown error"}"))
                        .append(Component.literal("\n\n§7Try app-password login instead"))

                    minecraft?.gui?.chat?.addMessage(
                        Component.literal("§cOAuth failed: ${e.message ?: "Unknown error"}")
                    )

                    updateUIState()
                    logger.error("OAuth authorisation failed: ${e.javaClass.simpleName} - ${e.message}")
                }
            }
        }
    }

    /**
     * App-password login (fallback).
     */
    private fun onLoginClicked() {
        val handle = handleField?.value?.trim() ?: ""
        val password = passwordField?.value ?: ""

        if (handle.isEmpty()) {
            statusText = Component.literal("§cPlease enter your handle or DID")
            return
        }

        if (password.isEmpty()) {
            statusText = Component.literal("§cPlease enter your app password")
            return
        }

        isAuthenticating = true
        oauthButton?.active = false
        loginButton?.active = false
        logoutButton?.active = false
        statusText = Component.literal("§eAuthenticating with AT Protocol...")

        coroutineScope.launch {
            try {
                // Authenticate with AT Protocol servers (client-side only)
                val session = sessionManager.createSession(handle, password).getOrThrow()

                // Send authenticated session to server for verification
                val packet = AtProtoPackets.AuthenticatePacket(
                    did = session.did,
                    handle = session.handle,
                    pdsUrl = session.pdsUrl,
                    accessJwt = session.accessJwt,
                    refreshJwt = session.refreshJwt,
                    authType = "app_password",
                )
                ClientPlayNetworking.send(packet)

                // Update UI on main thread
                minecraft?.execute {
                    statusText = Component.literal("§aAuthenticated successfully!")
                        .append(Component.literal("\n§7Handle: §f${session.handle}"))
                        .append(Component.literal("\n§7DID: §f${session.did}"))

                    minecraft?.gui?.chat?.addMessage(
                        Component.literal("§aAuthenticated with AT Protocol!")
                            .append(Component.literal("\n§7Waiting for server confirmation..."))
                    )

                    // Clear password field
                    passwordField?.value = ""

                    updateUIState()

                    logger.info("Successfully authenticated as ${session.handle}, sent session to server")
                }
            } catch (e: Exception) {
                minecraft?.execute {
                    statusText = Component.literal("§cAuthentication failed")
                        .append(Component.literal("\n§7${e.message ?: "Unknown error"}"))
                        .append(Component.literal("\n\n§7Tip: Use an §fApp Password§7 from your"))
                        .append(Component.literal("\n§7AT Protocol account settings"))
                        .append(Component.literal("\n§c§lNever use your main password!"))

                    minecraft?.gui?.chat?.addMessage(
                        Component.literal("§cAuthentication failed: ${e.message ?: "Unknown error"}")
                    )

                    updateUIState()

                    logger.error("Authentication failed: ${e.javaClass.simpleName} - ${e.message}")
                }
            }
        }
    }

    private fun onLogoutClicked() {
        sessionManager.deleteSession()

        // Notify server
        val packet = AtProtoPackets.LogoutPacket()
        ClientPlayNetworking.send(packet)

        statusText = Component.literal("§aLogged out successfully")
            .append(Component.literal("\n§7Session cleared from your computer"))

        minecraft?.gui?.chat?.addMessage(
            Component.literal("§aLogged out from SocialSync")
        )

        // Clear fields
        handleField?.value = ""
        passwordField?.value = ""

        updateUIState()

        logger.info("Logged out, notified server")
    }

    private fun onHelpClicked() {
        val helpText = Component.literal("§e§lSocialSync Login Help")
            .append(Component.literal("\n\n§b§lOAuth Login (Recommended)"))
            .append(Component.literal("\n§71. Enter your handle (e.g. alice.bsky.social)"))
            .append(Component.literal("\n§72. Click §fOAuth Login§7"))
            .append(Component.literal("\n§73. Complete login in your browser"))
            .append(Component.literal("\n§74. Return to Minecraft"))
            .append(Component.literal("\n\n§b§lApp Password Login (Fallback)"))
            .append(Component.literal("\n§71. Go to your AT Protocol account settings"))
            .append(Component.literal("\n   §7(e.g. Bluesky → Settings → Privacy & Security)"))
            .append(Component.literal("\n§72. Find \"App Passwords\""))
            .append(Component.literal("\n§73. Create a new app password"))
            .append(Component.literal("\n§74. Enter it in the password field above"))
            .append(Component.literal("\n\n§c§lNEVER use your main account password!"))
            .append(Component.literal("\n§cApp Passwords can be revoked anytime."))

        statusText = helpText
    }

    private fun updateUIState() {
        val hasSession = sessionManager.hasSession()

        isAuthenticating = false

        handleField?.active = !hasSession
        passwordField?.active = !hasSession
        oauthButton?.active = !hasSession && !isAuthenticating
        loginButton?.active = !hasSession && !isAuthenticating
        logoutButton?.active = hasSession

        if (hasSession && statusText == null) {
            // Show current session info
            coroutineScope.launch {
                try {
                    val session = sessionManager.getSession().getOrThrow()
                    val authLabel = if (sessionManager.isOAuthSession()) "§bOAuth" else "§eApp Password"
                    minecraft?.execute {
                        statusText = Component.literal("§aCurrently logged in ($authLabel§a)")
                            .append(Component.literal("\n§7Handle: §f${session.handle}"))
                            .append(Component.literal("\n§7DID: §f${session.did}"))
                    }
                } catch (e: Exception) {
                    minecraft?.execute {
                        statusText = Component.literal("§eSession may be expired")
                            .append(Component.literal("\n§7Try logging out and back in"))
                    }
                }
            }
        }
    }

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean {
        return true
    }
}
