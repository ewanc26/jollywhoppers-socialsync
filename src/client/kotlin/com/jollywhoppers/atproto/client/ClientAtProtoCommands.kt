package com.jollywhoppers.atproto.client

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.network.chat.Component

/**
 * Client-side AT Protocol commands.
 * All user-facing configuration (authentication, sync consent, frequencies,
 * privacy) is handled through the ModMenu config screen.
 * The only client-side command is help, which directs players to ModMenu
 * and lists server-side info/action commands.
 */
class ClientAtProtoCommands {
    fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(
            ClientCommandManager.literal("atproto")
                .then(
                    ClientCommandManager.literal("help")
                        .executes { context -> help(context) }
                )
                .executes { context -> help(context) }
        )
    }

    private fun help(context: CommandContext<FabricClientCommandSource>): Int {
        context.source.sendFeedback(
            Component.literal("§b━━━ AT Protocol ━━━")
                .append(Component.literal("\n§7All configuration (authentication, sync consent,")
                .append(Component.literal("\n§7frequencies, privacy) is in the ModMenu config screen.")))
                .append(Component.literal("\n§7Open ModMenu → find Social Sync → click the config button."))
                .append(Component.literal("\n"))
                .append(Component.literal("\n§b━━━ Server Commands ━━━"))
                .append(Component.literal("\n§f/atproto link <handle or DID>"))
                .append(Component.literal("\n  §7Link your Minecraft account to your AT Protocol identity"))
                .append(Component.literal("\n§f/atproto unlink"))
                .append(Component.literal("\n  §7Unlink your AT Protocol identity"))
                .append(Component.literal("\n§f/atproto whoami"))
                .append(Component.literal("\n  §7View your linked identity and auth status"))
                .append(Component.literal("\n§f/atproto status"))
                .append(Component.literal("\n  §7Check connection status"))
                .append(Component.literal("\n§f/atproto sync"))
                .append(Component.literal("\n  §7View your current sync consent settings"))
                .append(Component.literal("\n§f/atproto whois <player or handle>"))
                .append(Component.literal("\n  §7Look up another player's identity"))
                .append(Component.literal("\n§f/atproto profile <player>"))
                .append(Component.literal("\n  §7View a player's synced profile"))
                .append(Component.literal("\n§f/atproto export <player>"))
                .append(Component.literal("\n  §7Export a player's synced records as JSON"))
        )
        return 1
    }
}
