package dev.sunlit.fishvisuals.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = FishingHook.class, remap = false)
public interface ClientFishingBiteAccessor {
    @Accessor("f_37102_")
    static EntityDataAccessor<Boolean> sunlit$biting() {
        throw new AssertionError();
    }
}
