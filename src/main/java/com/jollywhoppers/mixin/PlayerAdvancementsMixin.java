package com.jollywhoppers.mixin;

import com.jollywhoppers.atproto.server.AchievementSyncService;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin that intercepts advancement criterion awards to sync achievements
 * to AT Protocol when a player earns a full advancement.
 *
 * We inject after the award method returns to check if the advancement
 * is now fully completed (all criteria satisfied).
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("atproto-connect");

    @Shadow
    private ServerPlayer player;

    /**
     * Injects after award to detect when an advancement is fully completed.
     * When a player earns all criteria for an advancement, we notify the
     * AchievementSyncService to create an AT Protocol record.
     */
    @Inject(method = "award", at = @At("RETURN"))
    private void onAward(AdvancementHolder advancement, String criterionName, CallbackInfoReturnable<Boolean> cir) {
        try {
            // Only process if the criterion was actually awarded
            if (!cir.getReturnValueZ()) return;

            // Check if the advancement is now fully completed
            PlayerAdvancements self = (PlayerAdvancements) (Object) this;
            AdvancementProgress progress = self.getOrStartProgress(advancement);

            if (progress != null && progress.isDone()) {
                // The advancement is fully completed — notify the sync service
                AchievementSyncService.INSTANCE.onAdvancementCompleted(
                    this.player,
                    advancement
                );
            }
        } catch (Exception e) {
            LOGGER.error("Error in advancement tracking mixin", e);
        }
    }
}
