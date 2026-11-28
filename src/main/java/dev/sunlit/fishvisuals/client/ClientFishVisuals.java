package dev.sunlit.fishvisuals.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.sunlit.fishvisuals.CatchModelIds;
import dev.sunlit.fishvisuals.SunlitFishVisuals;
import dev.sunlit.fishvisuals.FishTiming;
import dev.sunlit.fishvisuals.FishingSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-only model proxies. They are never spawned, ticked or saved as world mobs. */
@Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, value = Dist.CLIENT)
public final class ClientFishVisuals {
    private static final Map<UUID, Visual> VISUALS = new HashMap<>();
    private static ClientLevel currentLevel;
    private static long localTick;

    private static void syncLevel() {
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level != currentLevel) {
            VISUALS.clear();
            localTick = 0;
            if (currentLevel != null) {
                dev.sunlit.bettersunlit.client.EmfRenderPerf.clearLodTimers();
            }
            currentLevel = level;
        }
    }

    private static double localClockNow() {
        Minecraft mc = Minecraft.m_91087_();
        float pt = mc.m_91104_() ? 0.0f : Math.max(0.0f, Math.min(1.0f, mc.m_91296_()));
        return localTick + pt;
    }

    public static void receive(SunlitFishVisuals.CatchPacket packet) {
        syncLevel();
        if (currentLevel == null || !currentLevel.m_46472_().m_135782_().equals(packet.dimension())) return;
        ResourceLocation model = CatchModelIds.resolve(packet.model());
        if (model == null) {
            VISUALS.remove(packet.itemUuid());
            return;
        }
        // UUID keys allow the notification to arrive before the vanilla entity spawn packet.
        Visual visual = VISUALS.compute(packet.itemUuid(), (uuid, previous) ->
                previous == null || !model.equals(previous.model) ? new Visual(model) : previous);
        long serverNow = currentLevel.m_46467_();
        double localNow = Math.max(visual.lastNow, localClockNow());
        if (packet.landedAt() >= 0) {
            if (visual.landedAt < 0 || visual.localLandedAt < 0) {
                long landedAge = Math.max(0L, serverNow - packet.landedAt());
                visual.localLandedAt = landedAge <= 3L ? localNow : localNow - landedAge;
            }
        } else {
            visual.localLandedAt = -1;
        }
        if (packet.kickAt() >= 0 && packet.kickAt() != visual.kickAt) {
            if (visual.kickAt >= 0) {
                visual.flopSide = -visual.flopSide;
            }
            long kickAge = Math.max(0L, serverNow - packet.kickAt());
            visual.localKickAt = kickAge <= 3L ? localNow : localNow - kickAge;
        } else if (packet.kickAt() < 0) {
            visual.localKickAt = -1;
        }
        visual.landedAt = packet.landedAt();
        visual.kickAt = packet.kickAt();
        visual.kickStrength = packet.kickStrength();
        visual.timing = packet.timing();
        visual.entityId = packet.entityId();
        visual.receivedAt = localTick;
        if (!visual.hasYaw) {
            visual.fromYaw = packet.yaw();
            visual.hasYaw = true;
        } else {
            visual.fromYaw = visual.smoothedYaw(localNow);
        }
        if (visual.landedAt >= 0) {
            // A push may move the item diagonally without turning the whole fish with it.
            float turn = (((packet.yaw() - visual.fromYaw) % 360 + 540) % 360) - 180;
            visual.yaw = visual.fromYaw + Math.max(-12, Math.min(12, turn * 0.55f));
        } else {
            visual.yaw = packet.yaw();
        }
        visual.yawChangedAt = localNow;
        Entity entity = currentLevel.m_6815_(packet.entityId());
        if (entity != null && packet.itemUuid().equals(entity.m_20148_())) {
            syncClientMagnetLock(entity, visual, localNow);
        }
    }

    private static void syncClientMagnetLock(Entity entity, Visual visual, double localNow) {
        if (visual.localOnly) return;
        boolean lock = visual.localLandedAt < 0 || (localNow - visual.localLandedAt) < visual.timing.freshTicks();
        var data = entity.getPersistentData();
        if (lock) {
            if (!data.m_128441_(SunlitFishVisuals.PREVENT_REMOTE_MOVEMENT)) {
                data.m_128379_(SunlitFishVisuals.PREVENT_REMOTE_MOVEMENT, true);
                visual.managedMagnetLock = true;
            }
        } else if (visual.managedMagnetLock) {
            data.m_128473_(SunlitFishVisuals.PREVENT_REMOTE_MOVEMENT);
            visual.managedMagnetLock = false;
        }
    }

    public static boolean hasVisual(Entity entity) {return entity!=null && VISUALS.containsKey(entity.m_20148_());}

    public static void startLocalCatch(ItemEntity item) {
        syncLevel();
        ResourceLocation model = SunlitFishVisuals.modelFor(item.m_32055_());
        if (model == null || currentLevel != item.m_9236_() || VISUALS.containsKey(item.m_20148_())) return;
        Visual visual = new Visual(model);
        visual.localOnly = true;
        visual.timing = FishingSettings.current().timing();
        visual.entityId = item.m_19879_();
        visual.receivedAt = localTick;
        Vec3 velocity = item.m_20184_();
        visual.fromYaw = visual.yaw = (float) Math.toDegrees(Math.atan2(-velocity.f_82479_, velocity.f_82481_));
        VISUALS.put(item.m_20148_(), visual);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        syncLevel();
        if (currentLevel != null) {
            if (!Minecraft.m_91087_().m_91104_()) {
                localTick++;
            }
            long now = localTick;
            VISUALS.entrySet().removeIf(entry -> {
                Visual visual = entry.getValue();
                Entity entity = currentLevel.m_6815_(visual.entityId);
                if (entity != null && entry.getKey().equals(entity.m_20148_())) {
                    if (visual.localOnly && visual.localLandedAt < 0
                            && ((entity.m_20096_() && !entity.m_20069_())
                            || now - visual.receivedAt >= SunlitFishVisuals.MAX_AIR_TICKS)) {
                        visual.localLandedAt = now;
                        visual.nextLocalKick = now + 1;
                    }
                    if (visual.localOnly && visual.localLandedAt >= 0 && now >= visual.nextLocalKick) {
                        long elapsed = now - (long) visual.localLandedAt;
                        float strength = visual.timing.strength(elapsed);
                        if (strength > 0 && visual.timing.totalTicks() - elapsed >= 7) {
                            visual.localKickAt = now;
                            visual.kickStrength = strength;
                            visual.flopSide = -visual.flopSide;
                            visual.nextLocalKick = now + 17 + Math.round((1 - strength) * 30);
                        } else {
                            visual.nextLocalKick = Long.MAX_VALUE;
                        }
                    }
                    syncClientMagnetLock(entity, visual, now);
                }
                return now - visual.receivedAt > 200 && (entity == null || !entry.getKey().equals(entity.m_20148_()));
            });
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean render(ItemEntity item, float partialTick, PoseStack pose,
                                 MultiBufferSource buffers, int light) {
        syncLevel();
        Visual visual = VISUALS.get(item.m_20148_());
        if (visual == null) return false;
        double clampedPt = Math.max(0.0, Math.min(1.0, partialTick));
        double now = localTick + clampedPt;
        if (visual.lastNow >= 0 && now < visual.lastNow && (visual.lastNow - now) < 2.0) {
            now = visual.lastNow;
        }
        visual.lastNow = now;
        syncClientMagnetLock(item, visual, now);
        double rawX = item.f_19790_ + (item.m_20185_() - item.f_19790_) * clampedPt;
        double rawY = item.f_19791_ + (item.m_20186_() - item.f_19791_) * clampedPt;
        double rawZ = item.f_19792_ + (item.m_20189_() - item.f_19792_) * clampedPt;
        Vec3 vel = item.m_20184_();
        double speedPerTick = Math.sqrt(vel.f_82479_ * vel.f_82479_ + vel.f_82480_ * vel.f_82480_ + vel.f_82481_ * vel.f_82481_);
        visual.updateSmoothOffset(localTick, clampedPt, rawX, rawY, rawZ, speedPerTick, System.nanoTime());
        pose.m_85836_();
        try {
            if (visual.offX != 0.0 || visual.offY != 0.0 || visual.offZ != 0.0) {
                pose.m_85837_(visual.offX, visual.offY, visual.offZ);
            }
            float yaw = visual.smoothedYaw(now);
            boolean landed = visual.localLandedAt >= 0;
            double elapsed = landed ? Math.max(0, now - visual.localLandedAt) : 0;
            double remaining = visual.timing.totalTicks() - elapsed;
            double airAge = Math.max(visual.lastAirAge, item.f_19797_ + clampedPt);
            visual.lastAirAge = airAge;
            int side = (item.m_20148_().getLeastSignificantBits() & 1) == 0 ? 1 : -1;
            double sinceKick = visual.localKickAt < 0 ? -1 : now - visual.localKickAt;
            if (visual.localOnly && sinceKick >= 0 && sinceKick < 8) {
                pose.m_85837_(0, Math.sin(sinceKick * Math.PI / 8.0) * 0.16 * visual.kickStrength, 0);
            }
            if (CustomFishModels.has(visual.model)) {
                FishFlopMotion flop = FishFlopMotion.sample(landed, landed ? elapsed : airAge,
                        sinceKick, visual.localKickAt < 0 ? 0 : visual.kickStrength, remaining,
                        CustomFishModels.size(visual.model), landed ? side * visual.flopSide : side);
                FishMeshBend mesh = flop == FishFlopMotion.STILL ? null
                        : new FishMeshBend(buffers, pose.m_85850_().m_252922_(), yaw);
                CustomFishModels.render(visual.model, yaw, pose, mesh == null ? buffers : mesh, light);
                if (mesh != null) mesh.draw(flop);
                return true;
            }
            if (visual.proxy == null) {
                EntityType<?> type = CatchModelIds.entityType(visual.model);
                Entity proxy = type == null ? null : type.m_20615_(currentLevel);
                if (!(proxy instanceof LivingEntity)) {
                    VISUALS.remove(item.m_20148_());
                    return false;
                }
                visual.proxy = proxy;
            }
            Entity proxy = visual.proxy;
            float size = Math.max(proxy.m_20205_(), proxy.m_20206_());
            FishFlopMotion flop = FishFlopMotion.sample(landed, landed ? elapsed : airAge,
                    sinceKick, visual.localKickAt < 0 ? 0 : visual.kickStrength, remaining, size,
                    landed ? side * visual.flopSide : side);
            proxy.m_20219_(item.m_20182_());
            // The real item still moves. Its render proxy must not advance the normal swim cycle.
            proxy.m_20256_(Vec3.f_82478_);
            proxy.f_19797_ = 0;
            proxy.m_146922_(yaw);
            proxy.f_19859_ = yaw;
            proxy.m_146926_(0);
            proxy.f_19860_ = 0;
            proxy.m_6853_(item.m_20096_());
            LivingEntity living = (LivingEntity) proxy;
            living.f_20883_ = living.f_20884_ = yaw;
            living.f_20885_ = living.f_20886_ = yaw;
            EntityRenderer renderer = Minecraft.m_91087_().m_91290_().m_114382_(proxy);
            float groundRoll = landed ? visual.geoGroundRoll * (float)Math.min(1, elapsed / 3) : 0;
            FishMeshBend mesh = flop == FishFlopMotion.STILL ? null
                    : new FishMeshBend(buffers, pose.m_85850_().m_252922_(), yaw);
            CaughtFishPose previous = visual.modelPose.begin(groundRoll);
            try {
                renderer.m_7392_(proxy, yaw, 0, pose, mesh == null ? buffers : mesh, light);
                if (mesh != null) mesh.draw(flop);
            } finally {
                visual.modelPose.end(previous);
            }
            return true;
        } catch (RuntimeException exception) {
            VISUALS.remove(item.m_20148_());
            SunlitFishVisuals.LOG.warn("Cannot render fish model {}; using original item model", visual.model, exception);
            return false;
        } finally {
            pose.m_85849_();
        }
    }

    /** Exposed for offline smoothing verification tests. */
    public static final class SnapDampener {
        boolean hasPos;
        long lastTick = Long.MIN_VALUE;
        double lastPt = Double.NaN;
        double prevRawX, prevRawY, prevRawZ;
        public double offX, offY, offZ;
        long lastNanos;

        public void update(long tick, double pt, double rawX, double rawY, double rawZ,
                           double speedPerTick, long nowNanos) {
            if (!hasPos) {
                hasPos = true;
                lastTick = tick;
                lastPt = pt;
                prevRawX = rawX;
                prevRawY = rawY;
                prevRawZ = rawZ;
                offX = offY = offZ = 0.0;
                lastNanos = nowNanos;
                return;
            }
            // Same render frame (e.g. Iris/Oculus shadow pass + main pass): keep offset identical.
            if (tick == lastTick && Math.abs(pt - lastPt) < 1.0e-6) {
                return;
            }
            double dtSec = Math.max(0.0, Math.min(0.1, (nowNanos - lastNanos) * 1.0e-9));
            lastTick = tick;
            lastPt = pt;
            lastNanos = nowNanos;
            double dx = rawX - prevRawX;
            double dy = rawY - prevRawY;
            double dz = rawZ - prevRawZ;
            prevRawX = rawX;
            prevRawY = rawY;
            prevRawZ = rawZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq > 6.25) {
                offX = offY = offZ = 0.0;
                return;
            }
            // Absorb sudden server MoveEntityPacket teleports (ItemEntity.lerpTo hard-sets pos).
            double dtTicks = dtSec * 20.0;
            double maxNormalStep = (speedPerTick + 0.35) * dtTicks + 0.03;
            if (distSq > maxNormalStep * maxNormalStep) {
                offX = clampOffset(offX - dx);
                offY = clampOffset(offY - dy);
                offZ = clampOffset(offZ - dz);
            }
            if (dtSec > 0.0 && (offX != 0.0 || offY != 0.0 || offZ != 0.0)) {
                double decay = Math.exp(-dtSec * 22.0);
                offX *= decay;
                offY *= decay;
                offZ *= decay;
                if (offX * offX + offY * offY + offZ * offZ < 1.0e-6) {
                    offX = offY = offZ = 0.0;
                }
            }
        }

        private static double clampOffset(double v) {
            return Math.max(-0.75, Math.min(0.75, v));
        }
    }

    private static final class Visual {
        final ResourceLocation model;
        final float geoGroundRoll;
        int entityId;
        long receivedAt;
        boolean localOnly;
        long nextLocalKick;
        long landedAt = -1;
        long kickAt = -1;
        double localLandedAt = -1;
        double localKickAt = -1;
        double lastNow = -1;
        double lastAirAge = 0;
        float kickStrength;
        int flopSide = 1;
        FishTiming timing = FishTiming.DEFAULT;
        float yaw;
        float fromYaw;
        double yawChangedAt;
        boolean hasYaw;
        boolean managedMagnetLock;
        Entity proxy;
        final CaughtFishPose modelPose = new CaughtFishPose();
        final SnapDampener dampener = new SnapDampener();
        double offX, offY, offZ;

        void updateSmoothOffset(long tick, double pt, double rawX, double rawY, double rawZ,
                                double speedPerTick, long nowNanos) {
            dampener.update(tick, pt, rawX, rawY, rawZ, speedPerTick, nowNanos);
            offX = dampener.offX;
            offY = dampener.offY;
            offZ = dampener.offZ;
        }

        float smoothedYaw(double now) {
            double progress = Math.max(0, Math.min(1, (now - yawChangedAt) / Math.max(7, 12 * kickStrength)));
            progress = progress * progress * (3 - 2 * progress);
            float delta = (((yaw - fromYaw) % 360 + 540) % 360) - 180;
            return fromYaw + (float)(delta * progress);
        }

        Visual(ResourceLocation model) {
            this.model = model;
            this.geoGroundRoll = CaughtFishPose.groundRoll(model);
        }
    }
}
