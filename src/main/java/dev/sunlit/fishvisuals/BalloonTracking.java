package dev.sunlit.fishvisuals;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;

public final class BalloonTracking {
    private static final ResourceLocation BALLOON = new ResourceLocation("balloonbox", "balloonbox");
    private static EntityType<?> cachedBalloonType;
    private static volatile boolean balloonTypeMissing;

    private BalloonTracking() {}

    public static boolean isBalloon(Entity entity) {
        EntityType<?> cached = cachedBalloonType;
        if (cached != null) {
            return entity.m_6095_() == cached;
        }
        if (balloonTypeMissing) {
            return false;
        }
        return resolveAndCheck(entity);
    }

    private static boolean resolveAndCheck(Entity entity) {
        if (ForgeRegistries.ENTITY_TYPES != null && !ForgeRegistries.ENTITY_TYPES.getKeys().isEmpty()) {
            EntityType<?> resolved = ForgeRegistries.ENTITY_TYPES.getValue(BALLOON);
            if (resolved != null) {
                cachedBalloonType = resolved;
                return entity.m_6095_() == resolved;
            }
            balloonTypeMissing = true;
            return false;
        }
        return BALLOON.equals(ForgeRegistries.ENTITY_TYPES.getKey(entity.m_6095_()));
    }
}
