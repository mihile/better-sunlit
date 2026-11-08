package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve a separate reward entity while its landing/flop presentation is active. */
@Mixin(value=ItemEntity.class, remap=false)
public abstract class ItemEntityMixin {
    @Inject(method="m_32070_", at=@At("HEAD"), cancellable=true, require=1, remap=false)
    private void sunlit$holdCatchSeparate(CallbackInfoReturnable<Boolean> ci) {
        if (SunlitFishVisuals.isPresenting((ItemEntity)(Object)this)) ci.setReturnValue(false);
    }

    @Inject(method="m_6123_",at=@At("HEAD"),cancellable=true,require=1,remap=false)
    private void sunlit$waitUntilSpent(Player player,CallbackInfo ci) {
        ItemEntity item=(ItemEntity)(Object)this;
        if(SunlitFishVisuals.blocksPickup(item)) ci.cancel();
        else if(SunlitFishVisuals.isPresenting(item)) {
            if(!SunlitFishVisuals.hasRoom(player,item.m_32055_())) ci.cancel();
            else item.m_32010_(0);
        }
    }
}
