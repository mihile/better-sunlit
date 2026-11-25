package dev.sunlit.fishvisuals.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets client-only render proxies report being underwater so fish swim upright instead of flopping sideways. */
@Mixin(value = Entity.class, remap = false)
public interface EntityWaterStateAccessor {
    @Accessor("f_19798_")
    void sunlit$setWasTouchingWater(boolean inWater);
}
