package dev.sunlit.fishvisuals.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.sunlit.fishvisuals.SunlitFishVisuals;
import dev.sunlit.fishvisuals.FishTiming;
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
import net.minecraftforge.registries.ForgeRegistries;

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
            currentLevel = level;
        }
    }

    public static void receive(SunlitFishVisuals.CatchPacket packet) {
        syncLevel();
        if (currentLevel == null || !currentLevel.m_46472_().m_135782_().equals(packet.dimension())) return;
        // UUID keys allow the notification to arrive before the vanilla entity spawn packet.
        Visual visual = VISUALS.computeIfAbsent(packet.itemUuid(), uuid -> new Visual(packet.model()));
        double now=currentLevel.m_46467_();
        if (packet.kickAt() >= 0 && packet.kickAt() != visual.kickAt) {
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
        visual.entityId=packet.entityId();
        visual.receivedAt=currentLevel.m_46467_();
        if (!visual.hasYaw) {
            visual.fromYaw=packet.yaw();
            visual.hasYaw=true;
        } else visual.fromYaw=visual.smoothedYaw(now);
        visual.yaw = packet.yaw();
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
            if (visual.proxy == null) {
                EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(visual.model);
                Entity proxy = type == null ? null : type.m_20615_(currentLevel);
                if (!(proxy instanceof LivingEntity)) {
                    VISUALS.remove(item.m_20148_());
                    return false;
                }
                visual.proxy = proxy;
            }
            Entity proxy = visual.proxy;
            Vec3 motion = item.m_20184_();
            float yaw = visual.smoothedYaw(now);
            boolean resting=visual.landedAt>=0 && now-visual.landedAt>=visual.timing.totalTicks();
            double elapsed=visual.landedAt<0 ? 0 : Math.max(0,now-visual.landedAt);
            double settle=visual.smoothedSettle(now);
            if (visual.landedAt >= 0) {
                // Actual ItemEntity physics provide vertical AND lateral movement. Only body flex is cosmetic.
                double duration=Math.max(7,12*visual.kickStrength);
                double cycle=Math.max(0,Math.min(1,(now-visual.kickAt)/duration));
                double remaining=visual.timing.totalTicks()-elapsed;
                double endEnvelope=Math.max(0,Math.min(1,remaining/6.0));
                double kick=Math.sin(Math.PI*2*cycle)*Math.max(0.45,visual.kickStrength)*endEnvelope;
                double direction=(item.m_20148_().getLeastSignificantBits()&1)==0 ? 1 : -1;
                pose.m_252781_(Axis.f_252403_.m_252977_((float)(direction*kick*38)));
                pose.m_252781_(Axis.f_252529_.m_252977_((float)(kick*22)));
                pose.m_252781_(Axis.f_252403_.m_252977_((float)(-8*settle)));
                pose.m_252781_(Axis.f_252529_.m_252977_((float)(-4*settle)));
            }
            proxy.m_20219_(item.m_20182_());
            proxy.m_20256_(resting ? Vec3.f_82478_ : motion);
            // During fatigue the model's own swimming animation must pause between kicks too.
            // Integrate a smooth pulse instead of resetting its clock or letting it drift at rest.
            double animationAge=visual.animationAge(now,item.f_19797_+partialTick,elapsed,resting);
            proxy.f_19797_ = (int)Math.floor(animationAge);
            proxy.m_146922_(yaw);
            proxy.f_19859_ = yaw;
            proxy.m_146926_(0);
            proxy.f_19860_ = 0;
            proxy.m_6853_(item.m_20096_());
            LivingEntity living = (LivingEntity) proxy;
            living.f_20883_ = living.f_20884_ = yaw;
            living.f_20885_ = living.f_20886_ = yaw;
            EntityRenderer renderer = Minecraft.m_91087_().m_91290_().m_114382_(proxy);
            renderer.m_7392_(proxy, yaw, (float)(animationAge-Math.floor(animationAge)), pose, buffers, light);
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
        int entityId;
        long receivedAt;
        long landedAt = -1;
        long kickAt=-1;
        float kickStrength;
        FishTiming timing=FishTiming.DEFAULT;
        float yaw;
        float fromYaw;
        double yawChangedAt;
        boolean hasYaw;
        Entity proxy;
        double settleFrom,settleTarget,settleChangedAt;
        double modelAge,modelAgeAt;
        boolean hasModelAge;
        double smoothedSettle(double now) {
            double progress=Math.max(0,Math.min(1,(now-settleChangedAt)/7.0));
            progress=progress*progress*(3-2*progress);
            return settleFrom+(settleTarget-settleFrom)*progress;
        }
        double animationAge(double now,double vanillaAge,double elapsed,boolean resting) {
            if (!hasModelAge || now<modelAgeAt || landedAt<0 || elapsed<timing.freshTicks()) {
                modelAge=vanillaAge;
                modelAgeAt=now;
                hasModelAge=true;
                return modelAge;
            }
            double dt=Math.max(0,Math.min(2,now-modelAgeAt));
            double duration=Math.max(7,12*kickStrength);
            double phase=(now-kickAt)/duration;
            if (!resting && kickAt>=0 && phase>0 && phase<1) {
                double pulse=Math.sin(Math.PI*phase);
                modelAge+=dt*pulse*pulse*Math.max(0.45,kickStrength);
            }
            modelAgeAt=now;
            return modelAge;
        }
        float smoothedYaw(double now) {
            double progress=Math.max(0,Math.min(1,(now-yawChangedAt)/Math.max(7,12*kickStrength)));
            progress=progress*progress*(3-2*progress);
            float delta=(((yaw-fromYaw)%360+540)%360)-180;
            return fromYaw+(float)(delta*progress);
        }
        Visual(ResourceLocation model) {
            this.model = model;
        }
    }
}
