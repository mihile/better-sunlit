package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import com.bonker.stardewfishing.common.FishingHookLogic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "com.bonker.stardewfishing.common.FishingHookLogic", remap = false)
public abstract class StardewRewardMixin {
    @Redirect(method = "lambda$giveRewards$6", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;m_7967_(Lnet/minecraft/world/entity/Entity;)Z",
            ordinal = 0, remap = false), require = 1, remap = false)
    private static boolean sunlit$presentOriginalReward(ServerLevel level, Entity entity,
            boolean treasure, ServerPlayer angler, FishingHook hook, ItemStack rod, double score, FishingHookLogic logic) {
        return SunlitFishVisuals.addReward(level, entity, angler);
    }
}
