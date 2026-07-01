package com.jollywhoppers.screen

import com.jollywhoppers.socialsyncClient
import com.jollywhoppers.atproto.oauth.OAuthManager
import com.jollywhoppers.config.ClientPreferences
import com.jollywhoppers.config.PreferencesManager
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
 * ModMenu configuration screen for SocialSync.
 * Exposes all client preferences: authentication, sync consent,
 * sync frequency, UI preferences, and privacy settings.
 *
 * All user-facing config goes through this screen — no chat commands for config.
 */
class AtProtoConfigScreen(private val parent: Screen?) : Screen(Component.literal("SocialSync Settings")) {

    private val logger = LoggerFactory.getLogger("atproto-connect-ui")
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    // Session state
    private val sessionManager = socialsyncClient.sessionManager
    private val oAuthManager = socialsyncClient.oAuthManager

    // Auth fields (only shown when not logged in)
    private var handleField: EditBox? = null
    private var passwordField: EditBox? = null
    private var statusText: Component? = null
    private var isAuthenticating = false

    // Frequency step values
    private val frequencySteps = intArrayOf(1, 5, 10, 15, 30, 60, 120, 240)

    // Layout spacing (compact mode reduces vertical spacing)
    private val isCompact: Boolean
        get() = PreferencesManager.get().compactModMenuLayout

    private val buttonHeight: Int
        get() = if (isCompact) 18 else 20

    private val rowSpacing: Int
        get() = if (isCompact) 20 else 24

    private val sectionSpacing: Int
        get() = if (isCompact) 24 else 30

    // Section header Y positions (tracked during init, used in render)
    private var authHeaderY = 0
    private var syncConsentHeaderY = 0
    private var syncFreqHeaderY = 0
    private var uiHeaderY = 0
    private var privacyHeaderY = 0

    // Scrolling support
    private var scrollOffset = 0
    private var contentHeight = 0

    override fun init() {
        super.init()

        val centerX = width / 2
        val prefs = PreferencesManager.get()
        var y = if (isCompact) 30 else 40

        // Track section header positions
        authHeaderY = y
        y += if (isCompact) 16 else 20

        // ── Authentication ──────────────────────────────────────
        y = addAuthSection(centerX, y, prefs)

        // ── Sync Consent ────────────────────────────────────────
        syncConsentHeaderY = y
        y += if (isCompact) 16 else 20
        y = addSyncConsentSection(centerX, y, prefs)

        // ── Sync Frequency ───────────────────────────────────────
        syncFreqHeaderY = y
        y += if (isCompact) 16 else 20
        y = addSyncFrequencySection(centerX, y, prefs)

        // ── UI Preferences ──────────────────────────────────────
        uiHeaderY = y
        y += if (isCompact) 16 else 20
        y = addUISection(centerX, y, prefs)

        // ── Privacy ─────────────────────────────────────────────
        privacyHeaderY = y
        y += if (isCompact) 16 else 20
        y = addPrivacySection(centerX, y, prefs)

        contentHeight = y + 40

        // Clamp scroll offset
        val maxScroll = maxScrollOffset()
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)

        // ── Bottom bar ──────────────────────────────────────────
        addRenderableWidget(
            Button.builder(
                Component.literal("Done"),
                Button.OnPress { onClose() }
            )
                .bounds(centerX - 155, height - 28, 150, buttonHeight)
                .build()
        )

        addRenderableWidget(
            Button.builder(
                Component.literal("Reset Defaults"),
                Button.OnPress { onResetDefaults() }
            )
                .bounds(centerX + 5, height - 28, 150, buttonHeight)
                .build()
        )
    }

    // ── Section builders ────────────────────────────────────────

    private fun addAuthSection(centerX: Int, startY: Int, prefs: ClientPreferences): Int {
        var y = startY
        val hasSession = sessionManager.hasSession()

        if (hasSession) {
            // Show current session info + logout
            val isOAuth = sessionManager.isOAuthSession()
            val authLabel = if (isOAuth) "§bOAuth" else "§eApp Password"

            coroutineScope.launch {
                try {
                    val session = sessionManager.getSession().getOrThrow()
                    minecraft?.execute {
                        statusText = Component.literal("§aLogged in ($authLabel§a)")
                            .append(Component.literal("\n§7Handle: §f${session.handle}"))
                            .append(Component.literal("\n§7DID: §f${session.did}"))
                    }
                } catch (_: Exception) {
                    minecraft?.execute {
                        statusText = Component.literal("§eSession may be expired")
                    }
                }
            }

            addRenderableWidget(
                Button.builder(
                    Component.literal("Logout"),
                    Button.OnPress { onLogoutClicked() }
                )
                    .bounds(centerX - 75, scrolledY(y + 36), 150, buttonHeight)
                    .build()
            )

            y += if (isCompact) 60 else 70
        } else {
            // Login fields
            handleField = EditBox(
                minecraft!!.font,
                centerX - 150, scrolledY(y + 12),
                300, 20,
                Component.literal("Handle or DID")
            ).apply {
                setHint(Component.literal("alice.bsky.social or did:plc:..."))
                setMaxLength(256)
            }
            addRenderableWidget(handleField!!)

            passwordField = EditBox(
                minecraft!!.font,
                centerX - 150, scrolledY(y + 42),
                300, 20,
                Component.literal("App Password")
            ).apply {
                setHint(Component.literal("App Password (for fallback login)"))
                setMaxLength(256)
            }
            addRenderableWidget(passwordField!!)

            addRenderableWidget(
                Button.builder(
                    Component.literal("OAuth Login"),
                    Button.OnPress { onOAuthClicked() }
                )
                    .bounds(centerX - 155, scrolledY(y + 70), 150, 20)
                    .build()
            )

            addRenderableWidget(
                Button.builder(
                    Component.literal("App Password Login"),
                    Button.OnPress { onLoginClicked() }
                )
                    .bounds(centerX + 5, scrolledY(y + 70), 150, 20)
                    .build()
            )

            y += 100
        }

        return y
    }

    private fun addSyncConsentSection(centerX: Int, startY: Int, prefs: ClientPreferences): Int {
        var y = startY

        addRenderableWidget(
            Button.builder(
                Component.literal("Stats: ${if (prefs.syncStatsEnabled) "§aOn" else "§cOff"}"),
                Button.OnPress { toggleSyncConsent("stats") }
            )
                .bounds(centerX - 155, scrolledY(y), 150, buttonHeight)
                .build()
        )

        addRenderableWidget(
            Button.builder(
                Component.literal("Sessions: ${if (prefs.syncSessionsEnabled) "§aOn" else "§cOff"}"),
                Button.OnPress { toggleSyncConsent("sessions") }
            )
                .bounds(centerX + 5, scrolledY(y), 150, buttonHeight)
                .build()
        )

        y += rowSpacing

        addRenderableWidget(
            Button.builder(
                Component.literal("Achievements: ${if (prefs.syncAchievementsEnabled) "§aOn" else "§cOff"}"),
                Button.OnPress { toggleSyncConsent("achievements") }
            )
                .bounds(centerX - 155, scrolledY(y), 150, buttonHeight)
                .build()
        )

        addRenderableWidget(
            Button.builder(
                Component.literal("Server Status: ${if (prefs.syncServerStatusEnabled) "§aOn" else "§cOff"}"),
                Button.OnPress { toggleSyncConsent("server-status") }
            )
                .bounds(centerX + 5, scrolledY(y), 150, buttonHeight)
                .build()
        )

        return y + sectionSpacing
    }

    private fun addSyncFrequencySection(centerX: Int, startY: Int, prefs: ClientPreferences): Int {
        var y = startY

        addRenderableWidget(
            Button.builder(
                Component.literal("Stats: ${prefs.statsSyncFrequency}m"),
                Button.OnPress { cycleFrequency("stats") }
            )
                .bounds(centerX - 155, scrolledY(y), 150, buttonHeight)
                .build()
        )

        addRenderableWidget(
            Button.builder(
                Component.literal("Sessions: ${prefs.sessionSyncFrequency}m"),
                Button.OnPress { cycleFrequency("sessions") }
            )
                .bounds(centerX + 5, scrolledY(y), 150, buttonHeight)
                .build()
        )

        y += rowSpacing

        addRenderableWidget(
            Button.builder(
                Component.literal("Achievements: ${prefs.achievementSyncFrequency}m"),
                Button.OnPress { cycleFrequency("achievements") }
            )
                .bounds(centerX - 155, scrolledY(y), 150, buttonHeight)
                .build()
        )

        return y + sectionSpacing
    }

    private fun addUISection(centerX: Int, startY: Int, prefs: ClientPreferences): Int {
        var y = startY

        addRenderableWidget(
            Button.builder(
                Component.literal("Notifications: ${if (prefs.showSyncNotifications) "§aOn" else "§cOff"}"),
                Button.OnPress { togglePreference("showSyncNotifications") }
            )
                .bounds(centerX - 155, scrolledY(y), 150, buttonHeight)
                .build()
        )

        addRenderableWidget(
            Button.builder(
                Component.literal("F3 Status: ${if (prefs.showStatusInF3) "§aOn" else "§cOff"}"),
                Button.OnPress { togglePreference("showStatusInF3") }
            )
                .bounds(centerX + 5, scrolledY(y), 150, buttonHeight)
                .build()
        )

        y += rowSpacing

        addRenderableWidget(
            Button.builder(
                Component.literal("Compact Layout: ${if (prefs.compactModMenuLayout) "§aOn" else "§cOff"}"),
                Button.OnPress { togglePreference("compactModMenuLayout") }
            )
                .bounds(centerX - 155, scrolledY(y), 150, buttonHeight)
                .build()
        )

        return y + sectionSpacing
    }

    private fun addPrivacySection(centerX: Int, startY: Int, prefs: ClientPreferences): Int {
        var y = startY

        addRenderableWidget(
            Button.builder(
                Component.literal("Encrypt Storage: ${if (prefs.encryptedLocalStorage) "§aOn" else "§cOff"}"),
                Button.OnPress { togglePreference("encryptedLocalStorage") }
            )
                .bounds(centerX - 155, scrolledY(y), 150, buttonHeight)
                .build()
        )

        addRenderableWidget(
            Button.builder(
                Component.literal("Clear on Logout: ${if (prefs.clearLocalCacheOnLogout) "§aOn" else "§cOff"}"),
                Button.OnPress { togglePreference("clearLocalCacheOnLogout") }
            )
                .bounds(centerX + 5, scrolledY(y), 150, buttonHeight)
                .build()
        )

        return y + sectionSpacing
    }

    // ── Rendering ────────────────────────────────────────────────

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics, mouseX, mouseY, partialTick)

        // Title (fixed at top, not scrolled)
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF)

        // Enable scissor for scrollable content area
        val scrollAreaTop = 28
        val scrollAreaBottom = height - 35
        graphics.enableScissor(0, scrollAreaTop, width, scrollAreaBottom)

        // Section headers — positions tracked from init()
        graphics.drawCenteredString(font, "§nAuthentication", width / 2, authHeaderY - scrollOffset, 0xA0A0A0)
        graphics.drawCenteredString(font, "§nSync Consent", width / 2, syncConsentHeaderY - scrollOffset, 0xA0A0A0)
        graphics.drawCenteredString(font, "§nSync Frequency", width / 2, syncFreqHeaderY - scrollOffset, 0xA0A0A0)
        graphics.drawCenteredString(font, "§nUI", width / 2, uiHeaderY - scrollOffset, 0xA0A0A0)
        graphics.drawCenteredString(font, "§nPrivacy", width / 2, privacyHeaderY - scrollOffset, 0xA0A0A0)

        // Auth status text
        statusText?.let { status ->
            val lines = font.split(status, width - 40)
            var y = authHeaderY - scrollOffset + 20
            for (line in lines) {
                graphics.drawCenteredString(font, line, width / 2, y, 0xFFFFFF)
                y += 12
            }
        }

        // Labels for auth fields (when not logged in)
        if (!sessionManager.hasSession()) {
            graphics.drawString(font, "Handle or DID:", width / 2 - 150, authHeaderY - scrollOffset + 12, 0xA0A0A0)
            graphics.drawString(font, "App Password:", width / 2 - 150, authHeaderY - scrollOffset + 42, 0xA0A0A0)
        }

        graphics.disableScissor()

        // Security notice (fixed at bottom, not scrolled)
        val securityNotice = if (sessionManager.isOAuthSession()) {
            Component.literal("§aOAuth session active")
        } else if (sessionManager.hasSession()) {
            Component.literal("§7App-password session — password never leaves your computer")
        } else {
            Component.literal("§eOAuth is recommended — no password needed")
        }
        graphics.drawCenteredString(font, securityNotice, width / 2, height - 48, 0x40FF40)

        super.render(graphics, mouseX, mouseY, partialTick)
    }

    // ── Scrolling ────────────────────────────────────────────────

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val maxScroll = maxScrollOffset()
        if (maxScroll > 0) {
            scrollOffset = (scrollOffset - scrollY.toInt() * 10).coerceIn(0, maxScroll)
            rebuildWidgets()
        }
        return true
    }

    private fun scrolledY(y: Int): Int = y - scrollOffset

    private fun maxScrollOffset(): Int = (contentHeight - (height - 35)).coerceAtLeast(0)

    // ── Auth actions ─────────────────────────────────────────────

    private fun onOAuthClicked() {
        val handle = handleField?.value?.trim() ?: ""
        if (handle.isEmpty()) {
            statusText = Component.literal("§cEnter your handle or DID")
            return
        }

        isAuthenticating = true
        statusText = Component.literal("§eOpening browser for OAuth...")

        coroutineScope.launch {
            try {
                val result = oAuthManager.authorize(handle).getOrThrow()
                val session = result.session

                val packet = AtProtoPackets.AuthenticatePacket(
                    did = session.did,
                    handle = session.handle,
                    pdsUrl = session.pdsUrl,
                    accessJwt = session.accessToken,
                    refreshJwt = session.refreshToken,
                    authType = "oauth",
                )
                ClientPlayNetworking.send(packet)
                sessionManager.storeOAuthSession(session, result.dpopKeyPair)

                minecraft?.execute {
                    statusText = Component.literal("§aOAuth authorisation successful!")
                        .append(Component.literal("\n§7Handle: §f${session.handle}"))
                        .append(Component.literal("\n§eWaiting for server confirmation..."))
                    passwordField?.value = ""
                    rebuildWidgets()
                    logger.info("OAuth authenticated as ${session.handle}")
                }
            } catch (e: Exception) {
                minecraft?.execute {
                    statusText = Component.literal("§cOAuth failed: ${e.message ?: "Unknown error"}")
                    rebuildWidgets()
                    logger.error("OAuth failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun onLoginClicked() {
        val handle = handleField?.value?.trim() ?: ""
        val password = passwordField?.value ?: ""

        if (handle.isEmpty()) {
            statusText = Component.literal("§cEnter your handle or DID")
            return
        }
        if (password.isEmpty()) {
            statusText = Component.literal("§cEnter your app password")
            return
        }

        isAuthenticating = true
        statusText = Component.literal("§eAuthenticating...")

        coroutineScope.launch {
            try {
                val session = sessionManager.createSession(handle, password).getOrThrow()

                val packet = AtProtoPackets.AuthenticatePacket(
                    did = session.did,
                    handle = session.handle,
                    pdsUrl = session.pdsUrl,
                    accessJwt = session.accessJwt,
                    refreshJwt = session.refreshJwt,
                    authType = "app_password",
                )
                ClientPlayNetworking.send(packet)

                minecraft?.execute {
                    statusText = Component.literal("§aAuthenticated!")
                        .append(Component.literal("\n§7Handle: §f${session.handle}"))
                        .append(Component.literal("\n§eWaiting for server confirmation..."))
                    passwordField?.value = ""
                    rebuildWidgets()
                    logger.info("Authenticated as ${session.handle}")
                }
            } catch (e: Exception) {
                minecraft?.execute {
                    statusText = Component.literal("§cAuthentication failed: ${e.message ?: "Unknown error"}")
                    rebuildWidgets()
                    logger.error("Auth failed: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private fun onLogoutClicked() {
        sessionManager.deleteSession()
        ClientPlayNetworking.send(AtProtoPackets.LogoutPacket())
        statusText = Component.literal("§aLogged out")
        handleField?.value = ""
        passwordField?.value = ""
        rebuildWidgets()
        logger.info("Logged out")
    }

    // ── Preference toggles ───────────────────────────────────────

    private fun toggleSyncConsent(category: String) {
        val prefs = PreferencesManager.get()
        val newValue = when (category) {
            "stats" -> !prefs.syncStatsEnabled
            "sessions" -> !prefs.syncSessionsEnabled
            "achievements" -> !prefs.syncAchievementsEnabled
            "server-status" -> !prefs.syncServerStatusEnabled
            else -> return
        }

        when (category) {
            "stats" -> PreferencesManager.updateSyncConsent(stats = newValue)
            "sessions" -> PreferencesManager.updateSyncConsent(sessions = newValue)
            "achievements" -> PreferencesManager.updateSyncConsent(achievements = newValue)
            "server-status" -> PreferencesManager.updateSyncConsent(serverStatus = newValue)
        }

        sendPreferencesToServer()
        rebuildWidgets()
    }

    private fun togglePreference(key: String) {
        val prefs = PreferencesManager.get()
        val updated = when (key) {
            "showSyncNotifications" -> prefs.copy(showSyncNotifications = !prefs.showSyncNotifications)
            "showStatusInF3" -> prefs.copy(showStatusInF3 = !prefs.showStatusInF3)
            "compactModMenuLayout" -> prefs.copy(compactModMenuLayout = !prefs.compactModMenuLayout)
            "encryptedLocalStorage" -> prefs.copy(encryptedLocalStorage = !prefs.encryptedLocalStorage)
            "clearLocalCacheOnLogout" -> prefs.copy(clearLocalCacheOnLogout = !prefs.clearLocalCacheOnLogout)
            else -> return
        }
        PreferencesManager.update(updated)
        rebuildWidgets()
    }

    private fun cycleFrequency(category: String) {
        val prefs = PreferencesManager.get()
        val current = when (category) {
            "stats" -> prefs.statsSyncFrequency
            "sessions" -> prefs.sessionSyncFrequency
            "achievements" -> prefs.achievementSyncFrequency
            else -> return
        }

        // Find next step, wrapping around
        val nextIndex = (frequencySteps.indexOfFirst { it > current }.takeIf { it >= 0 } ?: 0)
        val nextValue = frequencySteps[nextIndex]

        val updated = when (category) {
            "stats" -> prefs.copy(statsSyncFrequency = nextValue)
            "sessions" -> prefs.copy(sessionSyncFrequency = nextValue)
            "achievements" -> prefs.copy(achievementSyncFrequency = nextValue)
            else -> return
        }
        PreferencesManager.update(updated)
        sendPreferencesToServer()
        rebuildWidgets()
    }

    private fun sendPreferencesToServer() {
        val prefs = PreferencesManager.get()
        val packet = AtProtoPackets.SyncPreferencesPacket(
            syncStatsEnabled = prefs.syncStatsEnabled,
            syncSessionsEnabled = prefs.syncSessionsEnabled,
            syncAchievementsEnabled = prefs.syncAchievementsEnabled,
            syncServerStatusEnabled = prefs.syncServerStatusEnabled,
            statsSyncFrequency = prefs.statsSyncFrequency,
            sessionSyncFrequency = prefs.sessionSyncFrequency,
            achievementSyncFrequency = prefs.achievementSyncFrequency,
        )
        ClientPlayNetworking.send(packet)
    }

    private fun onResetDefaults() {
        PreferencesManager.reset()
        sendPreferencesToServer()
        rebuildWidgets()
        logger.info("Preferences reset to defaults")
    }

    // ── Screen lifecycle ─────────────────────────────────────────

    override fun onClose() {
        minecraft?.setScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false
}
