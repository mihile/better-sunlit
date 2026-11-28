package dev.sunlit.bettersunlit;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

public final class SprinklerEffects {
    private static final int COLLECT_TICKS = 4;
    private static final int SPRAY_TICKS = 18;
    private static final int STAGGER_TICKS = 3;
    private static final int MAX_VISIBLE_SPRINKLERS = 24;
    private static final int VISIBLE_DISTANCE_SQUARED = 26 * 26;
    private static final int AUDIBLE_DISTANCE_SQUARED = 14 * 14;
    private static final ResourceLocation SPRAY_SOUND = new ResourceLocation("doapi:water_sprinkler");
    private static final ResourceLocation FALLBACK_SOUND = new ResourceLocation("minecraft:item.bottle.empty");
    private static final List<TagKey<Block>> TIERS = IntStream.rangeClosed(1, 4)
            .mapToObj(tier -> TagKey.m_203882_(Registries.f_256747_,
                    new ResourceLocation("dew_drop_farmland_growth:sprinkler_tier_" + tier)))
            .toList();
    private static final Map<ServerLevel, Morning> MORNINGS = new IdentityHashMap<>();

    private SprinklerEffects() {}

    public static void watered(ServerLevel level, BlockPos sprinkler, BlockPos farmland) {
        if (level.m_6907_().isEmpty()) return;

        long day = Math.floorDiv(level.m_46468_(), 24_000L);
        Morning morning = MORNINGS.get(level);
        if (morning == null || morning.day != day) {
            morning = new Morning(day);
            MORNINGS.put(level, morning);
        }
        long position = sprinkler.m_121878_();
        if (!morning.seen.add(position)) return;

        BlockState state = level.m_8055_(sprinkler);
        for (int index = TIERS.size() - 1; index >= 0; index--) {
            if (state.m_204336_(TIERS.get(index))) {
                if (morning.pending.isEmpty()) morning.collectUntil = level.m_46467_() + COLLECT_TICKS;
                boolean raised = sprinkler.m_123342_() > farmland.m_123342_();
                double shapeTop = state.m_60812_(level, sprinkler).m_83297_(Direction.Axis.Y);
                // Keep the first droplets clear of the head, the stick and same-level farmland.
                double height = Math.max(raised ? 0.59 : 0.98, shapeTop + 0.025);
                morning.pending.add(new Spray(sprinkler.m_123341_() + 0.5, sprinkler.m_123342_() + height,
                        sprinkler.m_123343_() + 0.5, index + 1, raised,
                        Long.hashCode(position)));
                return;
            }
        }
    }

    @SubscribeEvent
    public static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        Morning morning = MORNINGS.get(level);
        if (morning == null) return;

        long time = level.m_46467_();
        if (!morning.pending.isEmpty() && time >= morning.collectUntil) {
            dispatch(level, morning, time);
        }
        if (!morning.audiences.isEmpty()) {
            morning.audiences.removeIf(audience -> !audience.emit(level, time));
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) MORNINGS.remove(level);
    }

    private static void dispatch(ServerLevel level, Morning morning, long time) {
        Holder<SoundEvent> sound = ForgeRegistries.SOUND_EVENTS.getHolder(SPRAY_SOUND)
                .or(() -> ForgeRegistries.SOUND_EVENTS.getHolder(FALLBACK_SOUND)).orElse(null);
        for (ServerPlayer player : level.m_6907_()) {
            List<Spray> nearby = morning.pending.stream()
                    .filter(spray -> spray.distanceSquared(player) <= VISIBLE_DISTANCE_SQUARED)
                    .sorted(Comparator.comparingDouble(spray -> spray.distanceSquared(player)))
                    .limit(MAX_VISIBLE_SPRINKLERS)
                    .toList();
            if (nearby.isEmpty()) continue;
            morning.audiences.add(new Audience(player, nearby, time));

            Spray closest = nearby.get(0);
            if (sound != null && closest.distanceSquared(player) <= AUDIBLE_DISTANCE_SQUARED
                    && morning.heard.add(player.m_20148_())) {
                player.f_8906_.m_9829_(new ClientboundSoundPacket(sound, SoundSource.BLOCKS,
                        closest.x, closest.y, closest.z, 0.42F, 1.0F, closest.seed));
            }
        }
        morning.pending.clear();
    }

    private static final class Morning {
        final long day;
        final LongOpenHashSet seen = new LongOpenHashSet();
        final Set<UUID> heard = new HashSet<>();
        final List<Spray> pending = new ArrayList<>();
        final List<Audience> audiences = new ArrayList<>();
        long collectUntil;

        Morning(long day) {
            this.day = day;
        }
    }

    private record Audience(ServerPlayer player, List<Spray> sprays, long start) {
        boolean emit(ServerLevel level, long time) {
            long age = time - start;
            if (age > SPRAY_TICKS + STAGGER_TICKS || player.m_9236_() != level || player.m_213877_()) return false;
            for (Spray spray : sprays) {
                long step = age - Math.floorMod(spray.seed, STAGGER_TICKS + 1);
                if (step >= 0 && step <= SPRAY_TICKS && step % 3 == 0
                        && spray.distanceSquared(player) <= VISIBLE_DISTANCE_SQUARED) {
                    spray.emit(player, step / (double) SPRAY_TICKS);
                }
            }
            return true;
        }
    }

    private record Spray(double x, double y, double z, int radius, boolean raised, int seed) {
        double distanceSquared(ServerPlayer player) {
            return player.m_20275_(x, y, z);
        }

        void emit(ServerPlayer player, double progress) {
            double angle = (seed & 15) * (Math.PI / 8) + progress * Math.PI;
            double flow = 0.56 + 0.44 * Math.sin(Math.PI * progress);
            double speed = (0.12 + radius * (raised ? 0.105 : 0.14)) * flow;
            droplet(player, angle, speed);
            droplet(player, angle + 0.13, speed * 0.80);
            droplet(player, angle + Math.PI, speed);
            droplet(player, angle + Math.PI + 0.13, speed * 0.80);
        }

        private void droplet(ServerPlayer player, double angle, double speed) {
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            // A single directional splash uses zero Y to preserve the vanilla lateral velocity.
            player.f_8906_.m_9829_(new ClientboundLevelParticlesPacket(ParticleTypes.f_123769_, false,
                    x + dx * 0.19, y, z + dz * 0.19, (float) (dx * speed), 0, (float) (dz * speed), 1, 0));
        }
    }
}
