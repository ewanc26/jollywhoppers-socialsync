package com.jollywhoppers.network

import kotlinx.serialization.Serializable
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

/**
 * Network packets for client-server AT Protocol communication.
 * 
 * SECURITY MODEL:
 * - Client handles all authentication directly with AT Protocol servers
 * - Client sends authenticated session to server for verification
 * - Server never sees passwords
 * - Server verifies tokens with AT Protocol servers before accepting
 */

object AtProtoPackets {
    // Packet identifiers
    val AUTHENTICATE_C2S_ID = ResourceLocation.fromNamespaceAndPath("atproto-connect", "authenticate")
    val AUTHENTICATE_RESPONSE_S2C_ID = ResourceLocation.fromNamespaceAndPath("atproto-connect", "authenticate_response")
    val LOGOUT_C2S_ID = ResourceLocation.fromNamespaceAndPath("atproto-connect", "logout")
    val SYNC_PREFERENCES_C2S_ID = ResourceLocation.fromNamespaceAndPath("atproto-connect", "sync_preferences")
    
    /**
     * Client -> Server: Authenticated session data
     * Sent after client successfully authenticates with AT Protocol
     */
    @Serializable
    data class AuthenticatePacket(
        val did: String,
        val handle: String,
        val pdsUrl: String,
        val accessJwt: String,
        val refreshJwt: String,
        val authType: String = "app_password",
    ) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

        companion object {
            val TYPE: CustomPacketPayload.Type<AuthenticatePacket> =
                CustomPacketPayload.Type(AUTHENTICATE_C2S_ID)

            val CODEC: StreamCodec<FriendlyByteBuf, AuthenticatePacket> = StreamCodec.of(
                { buf, packet ->
                    buf.writeUtf(packet.did)
                    buf.writeUtf(packet.handle)
                    buf.writeUtf(packet.pdsUrl)
                    buf.writeUtf(packet.accessJwt)
                    buf.writeUtf(packet.refreshJwt)
                    buf.writeUtf(packet.authType)
                },
                { buf ->
                    AuthenticatePacket(
                        did = buf.readUtf(),
                        handle = buf.readUtf(),
                        pdsUrl = buf.readUtf(),
                        accessJwt = buf.readUtf(),
                        refreshJwt = buf.readUtf(),
                        authType = buf.readUtf(),
                    )
                }
            )
        }
    }
    
    /**
     * Server -> Client: Authentication response
     * Confirms whether authentication was accepted
     */
    @Serializable
    data class AuthenticateResponsePacket(
        val success: Boolean,
        val message: String
    ) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
        
        companion object {
            val TYPE: CustomPacketPayload.Type<AuthenticateResponsePacket> = 
                CustomPacketPayload.Type(AUTHENTICATE_RESPONSE_S2C_ID)
            
            val CODEC: StreamCodec<FriendlyByteBuf, AuthenticateResponsePacket> = StreamCodec.of(
                { buf, packet ->
                    buf.writeBoolean(packet.success)
                    buf.writeUtf(packet.message)
                },
                { buf ->
                    AuthenticateResponsePacket(
                        success = buf.readBoolean(),
                        message = buf.readUtf()
                    )
                }
            )
        }
    }
    
    /**
     * Client -> Server: Logout request
     */
    @Serializable
    data class LogoutPacket(
        val placeholder: Boolean = true // Just for serialization
    ) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE
        
        companion object {
            val TYPE: CustomPacketPayload.Type<LogoutPacket> = 
                CustomPacketPayload.Type(LOGOUT_C2S_ID)
            
            val CODEC: StreamCodec<FriendlyByteBuf, LogoutPacket> = StreamCodec.of(
                { buf, packet ->
                    buf.writeBoolean(packet.placeholder)
                },
                { buf ->
                    buf.readBoolean()
                    LogoutPacket()
                }
            )
        }
    }

    /**
     * Client -> Server: Sync preferences update
     * Sent when player changes sync consent in ModMenu
     */
    @Serializable
    data class SyncPreferencesPacket(
        val syncStatsEnabled: Boolean,
        val syncSessionsEnabled: Boolean,
        val syncAchievementsEnabled: Boolean,
        val syncServerStatusEnabled: Boolean,
        val statsSyncFrequency: Int,
        val sessionSyncFrequency: Int,
        val achievementSyncFrequency: Int,
    ) : CustomPacketPayload {
        override fun type(): CustomPacketPayload.Type<out CustomPacketPayload> = TYPE

        companion object {
            val TYPE: CustomPacketPayload.Type<SyncPreferencesPacket> =
                CustomPacketPayload.Type(SYNC_PREFERENCES_C2S_ID)

            val CODEC: StreamCodec<FriendlyByteBuf, SyncPreferencesPacket> = StreamCodec.of(
                { buf, packet ->
                    buf.writeBoolean(packet.syncStatsEnabled)
                    buf.writeBoolean(packet.syncSessionsEnabled)
                    buf.writeBoolean(packet.syncAchievementsEnabled)
                    buf.writeBoolean(packet.syncServerStatusEnabled)
                    buf.writeInt(packet.statsSyncFrequency)
                    buf.writeInt(packet.sessionSyncFrequency)
                    buf.writeInt(packet.achievementSyncFrequency)
                },
                { buf ->
                    SyncPreferencesPacket(
                        syncStatsEnabled = buf.readBoolean(),
                        syncSessionsEnabled = buf.readBoolean(),
                        syncAchievementsEnabled = buf.readBoolean(),
                        syncServerStatusEnabled = buf.readBoolean(),
                        statsSyncFrequency = buf.readInt(),
                        sessionSyncFrequency = buf.readInt(),
                        achievementSyncFrequency = buf.readInt(),
                    )
                }
            )
        }
    }
}
