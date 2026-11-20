package dev.sunlit.fishvisuals;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.registries.ForgeRegistries;

public final class BalloonTracking {
    private static final ResourceLocation BALLOON = new ResourceLocation("balloonbox", "balloonbox");

    private BalloonTracking() {}

    public static boolean isBalloon(Entity entity) {
        return BALLOON.equals(ForgeRegistries.ENTITY_TYPES.getKey(entity.m_6095_()));
    }
}
