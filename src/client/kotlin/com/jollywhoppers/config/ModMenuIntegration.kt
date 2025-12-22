package com.jollywhoppers.config

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import com.jollywhoppers.screen.AtProtoConfigScreen

/**
 * Mod Menu integration for ATProto Connect.
 * Provides a configuration screen for authentication and settings.
 */
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent ->
            AtProtoConfigScreen(parent)
        }
    }
}
