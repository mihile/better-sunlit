package dev.sunlit.bettersunlit.client;

import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/** A local dawn effect for nearby sprinklers with farmland in their watering area. */
@Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, value = Dist.CLIENT)
public final class LocalSprinklerEffects {
    private static final int VIEW_RADIUS = 26;
    private static final int SOUND_RADIUS = 14;
    private static final int DURATION = 18;
    private static final long DAY_TICKS = 24_000;
    private static final ResourceLocation SPRAY_SOUND = new ResourceLocation("doapi", "water_sprinkler");
    private static final ResourceLocation FALLBACK_SOUND = new ResourceLocation("minecraft", "item.bottle.empty");
    private static final List<TagKey<Block>> TIERS = IntStream.rangeClosed(1, 4)
            .mapToObj(tier -> TagKey.m_203882_(Registries.f_256747_,
                    new ResourceLocation("dew_drop_farmland_growth", "sprinkler_tier_" + tier)))
            .toList();
    private static ClientLevel currentLevel;
    private static long lastDayTime, lastDawn = -1;
    private static int age;
    private static List<Spray> sprays = List.of();

    private LocalSprinklerEffects() {}

    static boolean crossedDawn(long previous, long current) {
        return previous >= 0 && current > previous && current - previous <= 60
                && previous / DAY_TICKS < current / DAY_TICKS;
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft client = Minecraft.m_91087_();
        ClientLevel level = client.f_91073_;
        if (currentLevel != level) {
            currentLevel = level;
            lastDayTime = level == null ? -1 : level.m_46468_();
            lastDawn = -1;
            sprays = List.of();
            return;
        }
        if (level == null || client.f_91074_ == null) return;
        long time = level.m_46468_();
        var listener = client.m_91403_();
        if (crossedDawn(lastDayTime, time) && lastDawn != time / DAY_TICKS
                && listener != null && !SunlitFishVisuals.CHANNEL.isRemotePresent(listener.m_104910_())) {
            lastDawn = time / DAY_TICKS;
            collect(level, client.f_91074_.m_20183_());
        }
        lastDayTime = time;
        if (sprays.isEmpty()) return;
        if (age > DURATION + 3) {
            sprays = List.of();
            return;
        }
        for (Spray spray : sprays) {
            int frame = age - Math.floorMod(spray.seed, 4);
            if (frame >= 0 && frame <= DURATION && frame % 3 == 0) spray.emit(level, frame / (double) DURATION);
        }
        age++;
    }

    private static void collect(ClientLevel level, BlockPos center) {
        List<Spray> nearby = new ArrayList<>();
        if (TIERS.stream().allMatch(tag -> ForgeRegistries.BLOCKS.tags().getTag(tag).isEmpty())) return;
        for (BlockPos pos : BlockPos.m_121940_(center.m_7918_(-VIEW_RADIUS, -12, -VIEW_RADIUS),
                center.m_7918_(VIEW_RADIUS, 12, VIEW_RADIUS))) {
            double distance = pos.m_123331_(center);
            if (distance > VIEW_RADIUS * VIEW_RADIUS || !level.m_46805_(pos)) continue;
            var state = level.m_8055_(pos);
            for (int index = TIERS.size() - 1; index >= 0; index--) {
                if (!state.m_204336_(TIERS.get(index))) continue;
                int tier = index + 1;
                boolean raised = hasFarmland(level, pos, tier, -1);
                if (raised || hasFarmland(level, pos, tier, 0)) {
                    double top = state.m_60812_(level, pos).m_83297_(Direction.Axis.Y);
                    nearby.add(new Spray(pos.m_123341_() + 0.5, pos.m_123342_() + Math.max(raised ? 0.59 : 0.98, top + 0.025),
                            pos.m_123343_() + 0.5, tier, distance, Long.hashCode(pos.m_121878_())));
                }
                break;
            }
        }
        sprays = nearby.stream().sorted(Comparator.comparingDouble(Spray::distance)).limit(24).toList();
        age = 0;
        if (sprays.isEmpty()) return;
        Spray closest = sprays.get(0);
        if (closest.distance <= SOUND_RADIUS * SOUND_RADIUS) {
            var sound = ForgeRegistries.SOUND_EVENTS.getValue(SPRAY_SOUND);
            if (sound == null) sound = ForgeRegistries.SOUND_EVENTS.getValue(FALLBACK_SOUND);
            if (sound != null) level.m_7785_(closest.x, closest.y, closest.z, sound, SoundSource.BLOCKS, 0.42F, 1F, false);
        }
    }

    private static boolean hasFarmland(ClientLevel level, BlockPos sprinkler, int radius, int dy) {
        for (BlockPos pos : BlockPos.m_121940_(sprinkler.m_7918_(-radius, dy, -radius),
                sprinkler.m_7918_(radius, dy, radius))) {
            if (level.m_46805_(pos) && level.m_8055_(pos).m_60734_() instanceof FarmBlock) return true;
        }
        return false;
    }

    private record Spray(double x, double y, double z, int tier, double distance, int seed) {
        void emit(ClientLevel level, double progress) {
            double angle = (seed & 15) * Math.PI / 8 + progress * Math.PI;
            double speed = (0.12 + tier * 0.13) * (0.56 + 0.44 * Math.sin(Math.PI * progress));
            for (int stream = 0; stream < 4; stream++) {
                double direction = angle + (stream / 2) * Math.PI + (stream % 2) * 0.13;
                double dx = Math.cos(direction);
                double dz = Math.sin(direction);
                level.m_7106_(ParticleTypes.f_123769_, x + dx * 0.19, y, z + dz * 0.19, dx * speed, 0, dz * speed);
            }
        }
    }
}
