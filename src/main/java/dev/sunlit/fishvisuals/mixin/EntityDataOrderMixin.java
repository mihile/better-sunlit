package dev.sunlit.fishvisuals.mixin;

import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Wolf;
import org.spongepowered.asm.mixin.Mixin;

/** Runs the registration-order check after the other entity mixins merge. */
@Mixin(value = {Wolf.class, Cat.class}, priority = 2000)
public abstract class EntityDataOrderMixin {}
