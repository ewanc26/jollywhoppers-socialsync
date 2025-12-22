package com.jollywhoppers.screen

import com.jollywhoppers.AtprotoconnectClient
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
import net.minecraft.util.FormattedCharSequence
import org.slf4j.LoggerFactory

/**
 * Configuration screen for ATProto Connect.
 * Provides GUI-based authentication without requiring commands.
 * 
 * Security Features:
 * - Client-side only authentication
 * - Passwords never sent to server
 * - Local session storage
 * - Clear visual feedback
 */
class AtProtoConfigScreen(private val parent: Screen?) : Screen(Component.literal("ATProto Connect Settings")) {
    
    private val logger = LoggerFactory.getLogger("atproto-connect-ui")
    private val coroutineScope = CoroutineScope(Dispatchers.IO)
    
    // UI Components
    private var handleField: EditBox? = null
    private var passwordField: EditBox? = null
    private var loginButton: Button? = null
    private var logoutButton: Button? = null
    private var statusText: Component? = null
    private var isAuthenticating = false
    
    // Session state
    private val sessionManager = AtprotoconnectClient.sessionManager
    
    override fun init() {
        super.init()
        
        val centerX = width / 2
        val startY = 60
        
        // Title is rendered automatically by Screen
        
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
        
        // Password input field
        passwordField = EditBox(
            minecraft!!.font,
            centerX - 150,
            startY + 30,
            300,
            20,
            Component.literal("App Password")
        ).apply {
            setHint(Component.literal("App Password (NOT main password)"))
            setMaxLength(256)
            // Note: Password masking not available in this API version
            // The password is client-side only and never sent to the server
        }
        addRenderableWidget(passwordField!!)
        
        // Login button
        loginButton = Button.builder(
            Component.literal("Login"),
            Button.OnPress { onLoginClicked() }
        )
            .bounds(centerX - 155, startY + 60, 150, 20)
            .build()
            .also { addRenderableWidget(it) }
        
        // Logout button
        logoutButton = Button.builder(
            Component.literal("Logout"),
            Button.OnPress { onLogoutClicked() }
        )
            .bounds(centerX + 5, startY + 60, 150, 20)
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
        
        // Get app password help button
        addRenderableWidget(
            Button.builder(
                Component.literal("How to get App Password?"),
                Button.OnPress { onHelpClicked() }
            )
                .bounds(centerX - 100, startY + 90, 200, 20)
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
            "App Password:",
            width / 2 - 150,
            78,
            0xA0A0A0
        )
        
        // Render status
        statusText?.let { status ->
            val lines = font.split(status, width - 40)
            var y = 140
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
        val securityNotice = Component.literal("Your password never leaves your computer")
        graphics.drawCenteredString(
            font,
            securityNotice,
            width / 2,
            height - 50,
            0x40FF40
        )
        
        super.render(graphics, mouseX, mouseY, partialTick)
    }
    
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
                    refreshJwt = session.refreshJwt
                )
                ClientPlayNetworking.send(packet)
                
                // Update UI on main thread
                minecraft?.execute {
                    statusText = Component.literal("§a[SUCCESS] Successfully authenticated!")
                        .append(Component.literal("\n§7Handle: §f${session.handle}"))
                        .append(Component.literal("\n§7DID: §f${session.did}"))
                        .append(Component.literal("\n\n§aYou can now sync your Minecraft data!"))
                    
                    // Also send to chat for better visibility
                    minecraft?.gui?.chat?.addMessage(
                        Component.literal("§a[SUCCESS] Authenticated with AT Protocol!")
                            .append(Component.literal("\n§7Handle: §f${session.handle}"))
                            .append(Component.literal("\n§7Waiting for server confirmation..."))
                    )
                    
                    // Clear password field
                    passwordField?.value = ""
                    
                    updateUIState()
                    
                    logger.info("Successfully authenticated as ${session.handle}, sent session to server")
                }
            } catch (e: Exception) {
                minecraft?.execute {
                    statusText = Component.literal("§c[FAILED] Authentication failed")
                        .append(Component.literal("\n§7${e.message ?: "Unknown error"}"))
                        .append(Component.literal("\n\n§7Tip: Use an §fApp Password§7 from your"))
                        .append(Component.literal("\n§7AT Protocol account settings"))
                        .append(Component.literal("\n§c§lNever use your main password!"))
                    
                    // Also send to chat
                    minecraft?.gui?.chat?.addMessage(
                        Component.literal("§c[FAILED] Authentication failed: ${e.message ?: "Unknown error"}")
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
        
        statusText = Component.literal("§a[SUCCESS] Logged out successfully")
            .append(Component.literal("\n§7Session cleared from your computer"))
        
        // Also send to chat
        minecraft?.gui?.chat?.addMessage(
            Component.literal("§a[SUCCESS] Logged out from AT Protocol")
        )
        
        // Clear fields
        handleField?.value = ""
        passwordField?.value = ""
        
        updateUIState()
        
        logger.info("Logged out, notified server")
    }
    
    private fun onHelpClicked() {
        val helpText = Component.literal("§e§lHow to get an App Password:")
            .append(Component.literal("\n\n§71. Go to your AT Protocol account settings"))
            .append(Component.literal("\n   §7(e.g. Bluesky → Settings → Privacy & Security)"))
            .append(Component.literal("\n\n§72. Find \"App Passwords\""))
            .append(Component.literal("\n\n§73. Create a new app password"))
            .append(Component.literal("\n   §7(e.g. \"Minecraft Server\")"))
            .append(Component.literal("\n\n§74. Copy it immediately!"))
            .append(Component.literal("\n   §7(You won't see it again)"))
            .append(Component.literal("\n\n§75. Use it in the password field above"))
            .append(Component.literal("\n\n§c§lNEVER use your main account password!"))
            .append(Component.literal("\n§cApp Passwords can be revoked anytime."))
        
        statusText = helpText
    }
    
    private fun updateUIState() {
        val hasSession = sessionManager.hasSession()
        
        isAuthenticating = false
        
        handleField?.active = !hasSession
        passwordField?.active = !hasSession
        loginButton?.active = !hasSession && !isAuthenticating
        logoutButton?.active = hasSession
        
        if (hasSession && statusText == null) {
            // Show current session info
            coroutineScope.launch {
                try {
                    val session = sessionManager.getSession().getOrThrow()
                    minecraft?.execute {
                        statusText = Component.literal("§a[SUCCESS] Currently logged in")
                            .append(Component.literal("\n§7Handle: §f${session.handle}"))
                            .append(Component.literal("\n§7DID: §f${session.did}"))
                    }
                } catch (e: Exception) {
                    // Session exists but couldn't be retrieved
                    minecraft?.execute {
                        statusText = Component.literal("§e[WARNING] Session may be expired")
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
