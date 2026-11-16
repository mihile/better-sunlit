package dev.sunlit.fishvisuals.mixin;

import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Anchor mixin for {@code Entity}. The actual fail-closed guard short-circuiting Horseman's
 * {@code onGetBoundingBox} {@code ForgeConfigSpec.get()} call for non-horse entities runs in
 * {@code EntryMixinPlugin.postApply}.
 */
@Mixin(value = Entity.class, priority = 2000)
public abstract class PerfEntityHorsemanMixin {
}
