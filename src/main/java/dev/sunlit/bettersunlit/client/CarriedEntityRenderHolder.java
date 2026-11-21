package dev.sunlit.bettersunlit.client;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import tschipp.carryon.common.carry.CarryOnData;

public interface CarriedEntityRenderHolder {
    Entity sunlit$carriedRenderEntity(CarryOnData carry, Level level);
}
