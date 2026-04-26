package com.jollywhoppers.mixin.client

import com.jollywhoppers.socialsyncClient
import com.jollywhoppers.config.PreferencesManager
import net.minecraft.client.gui.components.DebugScreenOverlay
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

/**
 * Adds AT Protocol status to the F3 debug screen.
 */
@Mixin(DebugScreenOverlay::class)
class DebugScreenMixin {
    @Inject(method = ["getSystemInformation"], at = [At("RETURN")])
    private fun addAtProtoStatus(ci: CallbackInfoReturnable<MutableList<String>>) {
        if (!PreferencesManager.get().showStatusInF3) return

        val list = ci.returnValue
        list.add("")
        list.add("§6[AT Protocol]§r Social Sync")

        val sessionManager = socialsyncClient.sessionManager
        if (sessionManager.hasSession()) {
            val session = try {
                sessionManager.getSession().getOrNull()
            } catch (_: Exception) {
                null
            }

            if (session != null) {
                list.add("  §a✓ Authenticated§r as ${session.handle}")
                list.add("  Auth type: ${session.authType}")
            } else {
                list.add("  §e⚠ Session needs refresh§r")
            }
        } else {
            list.add("  §c✗ Not authenticated§r")
        }

        val prefs = PreferencesManager.get()
        val syncTypes = mutableListOf<String>()
        if (prefs.syncStatsEnabled) syncTypes.add("stats")
        if (prefs.syncSessionsEnabled) syncTypes.add("sessions")
        if (prefs.syncAchievementsEnabled) syncTypes.add("achievements")
        if (prefs.syncServerStatusEnabled) syncTypes.add("server")
        list.add("  Sync: ${if (syncTypes.isEmpty()) "§7none§r" else syncTypes.joinToString(", ")}")
    }
}
