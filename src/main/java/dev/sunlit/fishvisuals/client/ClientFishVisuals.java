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
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Client-only model proxies. They are never spawned, ticked or saved as world mobs. */
@Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, value = Dist.CLIENT)
public final class ClientFishVisuals {
    private static final Map<UUID, Visual> VISUALS = new HashMap<>();
    private static ClientLevel currentLevel;

    private static void syncLevel() {
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level != currentLevel) {
            VISUALS.clear();
            if (currentLevel != null) {
                dev.sunlit.bettersunlit.client.EmfRenderPerf.clearLodTimers();
            }
            currentLevel = level;
        }
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
        double now=currentLevel.m_46467_();
        if (packet.kickAt() >= 0 && packet.kickAt() != visual.kickAt) {
            if (visual.kickAt >= 0) visual.flopSide = -visual.flopSide;
            visual.settleFrom=visual.smoothedSettle(now);
            double fading=packet.timing().fadingTicks();
            double kickElapsed=packet.kickAt()-packet.landedAt()-packet.timing().freshTicks();
            visual.settleTarget=fading<=0 ? 0 : Math.max(visual.settleFrom,
                    Math.min(1,Math.max(0,(kickElapsed+packet.timing().stepTicks())/fading)));
            visual.settleChangedAt=now;
        }
        visual.landedAt = packet.landedAt();
        visual.kickAt=packet.kickAt();
        visual.kickStrength=packet.kickStrength();
        visual.timing=packet.timing();
        visual.animationVersion = packet.animationVersion();
        visual.entityId=packet.entityId();
        visual.receivedAt=currentLevel.m_46467_();
        if (!visual.hasYaw) {
            visual.fromYaw=packet.yaw();
            visual.hasYaw=true;
        } else visual.fromYaw=visual.smoothedYaw(now);
        if (visual.animationVersion == 2 && visual.landedAt >= 0) {
            // A push may move the item diagonally without turning the whole fish with it.
            float turn = (((packet.yaw() - visual.fromYaw) % 360 + 540) % 360) - 180;
            visual.yaw = visual.fromYaw + Math.max(-12, Math.min(12, turn * 0.55f));
        } else {
            visual.yaw = packet.yaw();
        }
        visual.yawChangedAt=now;
    }

    public static boolean hasVisual(Entity entity) {return entity!=null && VISUALS.containsKey(entity.m_20148_());}

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        syncLevel();
        if (currentLevel != null) {
            long now = currentLevel.m_46467_();
            VISUALS.entrySet().removeIf(entry -> {
                Visual visual=entry.getValue();
                Entity entity=currentLevel.m_6815_(visual.entityId);
                return now-visual.receivedAt>200 && (entity==null || !entry.getKey().equals(entity.m_20148_()));
            });
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean render(ItemEntity item, float partialTick, PoseStack pose,
                                 MultiBufferSource buffers, int light) {
        syncLevel();
        Visual visual = VISUALS.get(item.m_20148_());
        if (visual == null) return false;
        double now = currentLevel.m_46467_() + partialTick;
        pose.m_85836_();
        try {
            if (visual.custom == null && visual.proxy == null) {
                EntityType<?> type = CatchModelIds.entityType(visual.model);
                Entity proxy = type == null ? null : type.m_20615_(currentLevel);
                if (!(proxy instanceof LivingEntity)) {
                    VISUALS.remove(item.m_20148_());
                    return false;
                }
                visual.proxy = proxy;
            }
            Entity proxy = visual.proxy;
            float yaw = visual.smoothedYaw(now);
            boolean landed = visual.landedAt >= 0;
            double elapsed = landed ? Math.max(0, now - visual.landedAt) : 0;
            double remaining = visual.timing.totalTicks() - elapsed;
            if (visual.animationVersion == 2 && CatchModelIds.LEECH.equals(visual.model)) {
                visual.custom.drawLeech(pose, buffers, light, yaw, item.f_19797_ + partialTick,
                        landed ? elapsed : -1, now - visual.kickAt, remaining, visual.kickStrength);
                return true;
            }
            int side = (item.m_20148_().getLeastSignificantBits() & 1) == 0 ? 1 : -1;
            // Version 1 flops the whole fish, so its individual model parts use their rest pose.
            FishFlopMotion flop = FishFlopMotion.STILL;
            if (visual.animationVersion == 1) {
                if (landed) {
                    double duration = Math.max(7, 12 * visual.kickStrength);
                    double cycle = Math.max(0, Math.min(1, (now - visual.kickAt) / duration));
                    double envelope = Math.max(0, Math.min(1, remaining / 6.0));
                    double kick = Math.sin(Math.PI * 2 * cycle) * Math.max(0.45, visual.kickStrength) * envelope;
                    double settle = visual.smoothedSettle(now);
                    float heading = (float)Math.toRadians(yaw);
                    // Orient the rigid version 1 wobble to the fish's heading.
                    pose.m_252781_(new Quaternionf().rotationY(-heading)
                            .rotateZ((float)Math.toRadians(side * kick * 38))
                            .rotateX((float)Math.toRadians(kick * 22))
                            .rotateZ((float)Math.toRadians(-8 * settle))
                            .rotateX((float)Math.toRadians(-4 * settle)).rotateY(heading));
                }
            } else {
                float size = visual.custom == null ? Math.max(proxy.m_20205_(), proxy.m_20206_()) : visual.custom.size();
                flop = FishFlopMotion.sample(landed, landed ? elapsed : item.f_19797_ + partialTick, now - visual.kickAt,
                        visual.kickAt < 0 ? 0 : visual.kickStrength, remaining, size, landed ? side * visual.flopSide : side);
            }
            if (visual.custom != null) {
                FishMeshBend mesh = flop == FishFlopMotion.STILL ? null
                        : new FishMeshBend(buffers, pose.m_85850_().m_252922_(), yaw);
                visual.custom.draw(pose, mesh == null ? buffers : mesh, light, yaw,
                        landed ? (float)Math.min(1, elapsed / 3) : 0);
                if (mesh != null) mesh.draw(flop);
                return true;
            }
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

    private static final class Visual {
        final ResourceLocation model;
        final CustomCatchModel custom;
        final float geoGroundRoll;
        int entityId;
        long receivedAt;
        long landedAt = -1;
        long kickAt=-1;
        float kickStrength;
        int flopSide = 1;
        FishTiming timing=FishTiming.DEFAULT;
        int animationVersion = FishingSettings.DEFAULT_ANIMATION_VERSION;
        float yaw;
        float fromYaw;
        double yawChangedAt;
        boolean hasYaw;
        Entity proxy;
        double settleFrom,settleTarget,settleChangedAt;
        final CaughtFishPose modelPose = new CaughtFishPose();
        double smoothedSettle(double now) {
            double progress=Math.max(0,Math.min(1,(now-settleChangedAt)/7.0));
            progress=progress*progress*(3-2*progress);
            return settleFrom+(settleTarget-settleFrom)*progress;
        }
        float smoothedYaw(double now) {
            double progress=Math.max(0,Math.min(1,(now-yawChangedAt)/Math.max(7,12*kickStrength)));
            progress=progress*progress*(3-2*progress);
            float delta=(((yaw-fromYaw)%360+540)%360)-180;
            return fromYaw+(float)(delta*progress);
        }
        Visual(ResourceLocation model) {
            this.model = model;
            this.custom = CustomCatchModel.get(model);
            this.geoGroundRoll = CaughtFishPose.groundRoll(model);
        }
    }
}
