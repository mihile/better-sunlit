package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.fishvisuals.client.FishPondVisuals;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "snownee.jade.impl.ObjectDataCenter", remap = false)
public abstract class ClientJadePondDataMixin {
    @Inject(method = "setServerData(Lnet/minecraft/nbt/CompoundTag;)V", at = @At("HEAD"), require = 0)
    private static void sunlit$observeExistingPondData(CompoundTag tag, CallbackInfo ci) {
        FishPondVisuals.receiveJadeData(tag);
    }
}
