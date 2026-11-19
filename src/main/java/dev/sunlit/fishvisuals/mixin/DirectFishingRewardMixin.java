package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.util.FakePlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(value = FishingHook.class, targets = {
        "com.teammetallurgy.aquaculture.entity.AquaFishingBobberEntity",
        "com.scouter.netherdepthsupgrade.entity.entities.LavaFishingBobberEntity"
}, remap = false)
public abstract class DirectFishingRewardMixin {
    // Stardew cancels native retrieval when it opens the minigame. With its toggle off, the native
    // hook creates the item normally. Aqua 2.5.7 puts that spawn in a separate method.
    @Redirect(method = {
            "m_37156_(Lnet/minecraft/world/item/ItemStack;)I",
            "spawnLoot(Lnet/minecraft/world/entity/player/Player;Ljava/util/List;)V"
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;m_7967_(Lnet/minecraft/world/entity/Entity;)Z",
            ordinal = 0), require = 0)
    private boolean sunlit$presentDirectReward(Level level, Entity entity) {
        boolean added = level.m_7967_(entity);
        FishingHook hook = (FishingHook)(Object)this;
        if (added && level instanceof ServerLevel serverLevel
                && hook.m_19749_() instanceof ServerPlayer angler && !(angler instanceof FakePlayer)) {
            SunlitFishVisuals.presentReward(serverLevel, entity, angler);
        }
        return added;
    }
}
