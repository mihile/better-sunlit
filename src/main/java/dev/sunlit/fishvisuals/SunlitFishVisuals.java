package dev.sunlit.fishvisuals;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** A presentation layer over the ORIGINAL Stardew Fishing reward ItemEntity. */
@Mod(SunlitFishVisuals.ID)
public final class SunlitFishVisuals {
    public static final String ID = "sunlit_fish_visuals";
    public static final Logger LOG = LogManager.getLogger(ID);
    public static final String CATCH_DATA = "sunlit_fishing_visual";
    public static final int MAX_AIR_TICKS = 100;
    private static final Map<UUID, CatchState> CATCHES = new HashMap<>();
    public static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
            .named(new ResourceLocation(ID, "catch"))
            .networkProtocolVersion(() -> "3")
            .clientAcceptedVersions(SunlitFishVisuals::acceptsVersion)
            .serverAcceptedVersions(SunlitFishVisuals::acceptsVersion)
            .simpleChannel();

    // These item/entity names differ in Unusual Fish. Other fish use matching IDs.
    private static final Map<String, String> ALIASES = Map.of(
            "unusualfishmod:blind_sailfin", "unusualfishmod:blindsailfin",
            "unusualfishmod:blizzard_tuna", "unusualfishmod:blizzardfin",
            "unusualfishmod:copperflame_anthias", "unusualfishmod:copperflame",
            "unusualfishmod:hatchetfish", "unusualfishmod:hatchet_fish",
            "unusualfishmod:circus_fish", "unusualfishmod:circus",
            "unusualfishmod:snowflake", "unusualfishmod:snowflaketail"
    );

    private static boolean acceptsVersion(String version) {
        return "3".equals(version) || NetworkRegistry.ABSENT.equals(version) || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }

    public SunlitFishVisuals() {
        CHANNEL.registerMessage(0, CatchPacket.class, CatchPacket::encode, CatchPacket::decode,
                CatchPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(SunlitFishVisuals::tick);
        MinecraftForge.EVENT_BUS.addListener(SunlitFishVisuals::stopped);
        MinecraftForge.EVENT_BUS.addListener(SunlitFishVisuals::joined);
        MinecraftForge.EVENT_BUS.addListener(SunlitFishVisuals::left);
        MinecraftForge.EVENT_BUS.addListener(SunlitFishVisuals::tracking);
        MinecraftForge.EVENT_BUS.addListener(FishingSettings::register);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.LOWEST,
                dev.sunlit.bettersunlit.perf.BlockOcclusionPerf::onTagsUpdated);
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
                .addListener(dev.sunlit.bettersunlit.perf.LogBegonePerf::onConfigEvent);
        FishingSettings.load();
        LOG.info("Sunlit Fishing Visuals 1.2.4: pickup allowed when fading begins; {}",FishingSettings.current().description());
    }

    public static ResourceLocation modelFor(ItemStack stack) {
        if (stack.m_41619_() || !stack.m_204117_(ItemTags.f_13156_)) return null;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        if (itemId == null) return null;
        String normalizedId = itemId.toString().replace(":raw_", ":");
        ResourceLocation entityId = new ResourceLocation(ALIASES.getOrDefault(normalizedId, normalizedId));
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(entityId);
        return type != null ? entityId : null;
    }

    /** Called at the final entity-spawn instruction, AFTER loot, quality and all item-fished handlers. */
    public static boolean addReward(ServerLevel level, Entity entity, ServerPlayer angler) {
        // Always spawn the unmodified vanilla reward first. Visual failures must never consume a reward.
        boolean added = level.m_7967_(entity);
        if (!added || !(entity instanceof ItemEntity item)) return added;
        try {
            ResourceLocation model = modelFor(item.m_32055_());
            if (model == null) return true;
            // Vanilla's pull relies on pickup mid-flight; with delayed pickup it can overshoot.
            // Aim a physical arc at nearby dry ground, without moving/replacing the reward stack.
            aimAtShore(level, item, angler);
            item.m_32010_(2);
            CatchState state = new CatchState(item, model, level.m_46467_(),FishingSettings.current());
            // Long command-configured animations must not outlast the original item's despawn timer.
            item.lifespan=Math.max(item.lifespan,item.m_32059_()+MAX_AIR_TICKS+state.timing.totalTicks()+6000);
            CATCHES.put(item.m_20148_(), state);
            save(state);
            broadcast(level, state);
        } catch (RuntimeException exception) {
            LOG.warn("Fish presentation unavailable; the original fishing reward remains in the world", exception);
        }
        return true;
    }

    private static void aimAtShore(ServerLevel level, ItemEntity item, ServerPlayer angler) {
        double dx = item.m_20185_() - angler.m_20185_(), dz = item.m_20189_() - angler.m_20189_();
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance < 0.01) return;
        double fx = dx / distance, fz = dz / distance;
        // Prefer ground in view; avoid water, then try the player's feet and adjacent shore.
        double[][] offsets = {{0.8,0.4},{0.8,-0.4},{0.4,0},{0,0},{0,0.7},{0,-0.7},{-0.5,0}};
        for (double[] offset : offsets) {
            double x = angler.m_20185_() + fx * offset[0] - fz * offset[1];
            double z = angler.m_20189_() + fz * offset[0] + fx * offset[1];
            var hit = level.m_45547_(new ClipContext(new Vec3(x, angler.m_20186_() + 0.7, z),
                    new Vec3(x, angler.m_20186_() - 1.5, z), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, angler));
            if (hit.m_6662_() != HitResult.Type.BLOCK || hit.m_82434_() != Direction.UP) continue;
            Vec3 target = hit.m_82450_();
            if (!level.m_6425_(BlockPos.m_274561_(x,target.f_82480_ + 0.04,z)).m_76178_()) continue;
            int ticks = Math.max(12, Math.min(24, (int)(12 + distance * 0.25)));
            double drag = 0.98, sum = (1 - Math.pow(drag,ticks)) / (1 - drag);
            double vy = (target.f_82480_ + 0.03 - item.m_20186_()
                    + 0.04 / (1 - drag) * (ticks - drag * sum)) / sum;
            item.m_20256_(new Vec3((x-item.m_20185_())/sum,vy,(z-item.m_20189_())/sum));
            item.f_19812_ = true;
            return;
        }
    }

    public static boolean isPresenting(ItemEntity item) {
        return !item.m_9236_().f_46443_ && CATCHES.containsKey(item.m_20148_());
    }

    public static boolean blocksPickup(ItemEntity item) {
        if (item.m_9236_().f_46443_) return false;
        CatchState state=CATCHES.get(item.m_20148_());
        return state!=null && (state.landedAt<0 || item.m_9236_().m_46467_()-state.landedAt<state.timing.freshTicks());
    }

    public static boolean hasRoom(Player player,ItemStack reward) {
        int capacity=0;
        int maximum=Math.min(player.m_150109_().m_6893_(),reward.m_41741_());
        for(ItemStack slot:player.m_150109_().f_35974_) {
            if(slot.m_41619_()) capacity+=maximum;
            else if(ItemStack.m_150942_(slot,reward)) capacity+=Math.max(0,maximum-slot.m_41613_());
            if(capacity>=reward.m_41613_()) return true;
        }
        // Vanilla pickup may top up a matching offhand stack, but never fills an empty offhand.
        for(ItemStack slot:player.m_150109_().f_35976_)
            if(!slot.m_41619_() && ItemStack.m_150942_(slot,reward)) capacity+=Math.max(0,maximum-slot.m_41613_());
        return capacity>=reward.m_41613_();
    }

    private static CatchState restore(ItemEntity item) {
        if (item.m_9236_().f_46443_ || !item.getPersistentData().m_128441_(CATCH_DATA)) return null;
        try {
            CompoundTag tag=item.getPersistentData().m_128469_(CATCH_DATA);
            CatchState state=new CatchState(item,new ResourceLocation(tag.m_128461_("model")),tag.m_128454_("startedAt"),
                    new FishTiming(tag.m_128451_("freshTicks"),tag.m_128451_("fadingTicks"),tag.m_128451_("stepTicks")));
            state.landedAt=tag.m_128454_("landedAt");state.kickAt=tag.m_128454_("kickAt");state.nextKickAt=tag.m_128454_("nextKickAt");
            state.yaw=tag.m_128457_("yaw");state.kickStrength=tag.m_128457_("kickStrength");
            state.anchor=new Vec3(tag.m_128459_("anchorX"),tag.m_128459_("anchorY"),tag.m_128459_("anchorZ"));
            CATCHES.put(item.m_20148_(),state);
            return state;
        } catch (RuntimeException ex) {
            item.getPersistentData().m_128473_(CATCH_DATA);
            item.m_32010_(0);
            LOG.warn("Invalid saved fish presentation; keeping the original reward item",ex);
            return null;
        }
    }

    private static void save(CatchState state) {
        CompoundTag tag=new CompoundTag();
        tag.m_128359_("model",state.model.toString());
        tag.m_128356_("startedAt",state.startedAt);tag.m_128356_("landedAt",state.landedAt);
        tag.m_128356_("kickAt",state.kickAt);tag.m_128356_("nextKickAt",state.nextKickAt);
        tag.m_128405_("freshTicks",state.timing.freshTicks());tag.m_128405_("fadingTicks",state.timing.fadingTicks());tag.m_128405_("stepTicks",state.timing.stepTicks());
        tag.m_128350_("yaw",state.yaw);tag.m_128350_("kickStrength",state.kickStrength);
        tag.m_128347_("anchorX",state.anchor.f_82479_);tag.m_128347_("anchorY",state.anchor.f_82480_);tag.m_128347_("anchorZ",state.anchor.f_82481_);
        // Entity metadata, never ItemStack NBT. Forge saves this with the original ItemEntity.
        state.item.getPersistentData().m_128365_(CATCH_DATA,tag);
    }

    private static void joined(EntityJoinLevelEvent event) {
        if (!event.getLevel().f_46443_ && event.getEntity() instanceof ItemEntity item) restore(item);
    }
    private static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel().f_46443_ || !(event.getEntity() instanceof ItemEntity item)) return;
        CatchState state=CATCHES.get(item.m_20148_());
        if (state!=null && state.item==item) CATCHES.remove(item.m_20148_());
    }
    private static void tracking(PlayerEvent.StartTracking event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof ItemEntity item)) return;
        CatchState state=CATCHES.get(item.m_20148_());
        if (state==null) state=restore(item);
        if (state!=null) send(player,state);
    }

    private static void stopped(ServerStoppedEvent event) { CATCHES.clear(); }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var iterator = CATCHES.values().iterator();
        while (iterator.hasNext()) {
            CatchState state = iterator.next();
            ItemEntity item = state.item;
            if (item.m_213877_() || !(item.m_9236_() instanceof ServerLevel level)) {
                iterator.remove();
                continue;
            }
            long now = level.m_46467_();
            if (state.landedAt < 0 && ((item.m_20096_() && !item.m_20069_()) || now-state.startedAt>=MAX_AIR_TICKS)) {
                state.landedAt = now;
                state.anchor=item.m_20182_();
                state.nextKickAt=now+1;
                item.m_20256_(Vec3.f_82478_);
                item.f_19812_ = true;
                save(state);
                broadcast(level, state);
            }
            item.m_32010_(blocksPickup(item) ? 2 : 0);
            if (state.landedAt >= 0) {
                float strength=state.timing.strength(now-state.landedAt);
                long remaining=state.timing.totalTicks()-(now-state.landedAt);
                if (strength>0 && remaining>=7 && item.m_20096_() && now>=state.nextKickAt && !item.m_20069_()) {
                    hop(level,state,strength,now);
                } else if (item.m_20096_()) {
                    // No item sliding/bobbing between kicks; a spent fish remains a fish until pickup.
                    // Tell clients when a hop's remaining sideways velocity is cancelled, so they
                    // do not keep extrapolating the old motion and snap back between flops.
                    Vec3 velocity=item.m_20184_();
                    if (velocity.f_82479_*velocity.f_82479_+velocity.f_82481_*velocity.f_82481_>1.0e-8)
                        item.f_19812_=true;
                    item.m_20256_(Vec3.f_82478_);
                }
            }
        }
    }

    private static void hop(ServerLevel level, CatchState state, float strength, long now) {
        ItemEntity item=state.item;
        var random=level.f_46441_;
        double vx=0,vz=0;
        double height=Math.max(0.14,(0.21+random.m_188500_()*0.04)*strength);
        double flightTicks=Math.max(2,2*height/0.04);
        double travel=(0.35+random.m_188500_()*0.35)*strength;
        for (int attempt=0;attempt<8;attempt++) {
            double angle=random.m_188500_()*Math.PI*2;
            double x=item.m_20185_()+Math.cos(angle)*travel,z=item.m_20189_()+Math.sin(angle)*travel;
            double ax=x-state.anchor.f_82479_,az=z-state.anchor.f_82481_;
            if (ax*ax+az*az>1.4*1.4) continue;
            var hit=level.m_45547_(new ClipContext(new Vec3(x,item.m_20186_()+0.6,z),new Vec3(x,item.m_20186_()-0.6,z),
                    ClipContext.Block.COLLIDER,ClipContext.Fluid.ANY,item));
            if (hit.m_6662_()!=HitResult.Type.BLOCK || hit.m_82434_()!=Direction.UP) continue;
            if (!level.m_6425_(BlockPos.m_274561_(x,hit.m_82450_().f_82480_+0.03,z)).m_76178_()) continue;
            vx=(x-item.m_20185_())/flightTicks;vz=(z-item.m_20189_())/flightTicks;
            break;
        }
        item.m_20256_(new Vec3(vx,height,vz));
        item.f_19812_=true;
        state.kickAt=now;state.kickStrength=strength;
        state.nextKickAt=now+Math.round(11+32*(1-strength))+random.m_188503_(3);
        if (vx*vx+vz*vz>0.00001) {
            float desired=(float)Math.toDegrees(Math.atan2(-vx,vz));
            float delta=(((desired-state.yaw)%360+540)%360)-180;
            state.yaw+=Math.max(-28,Math.min(28,delta));
        }
        save(state);
        broadcast(level,state);
        var sound=ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation("minecraft:entity.cod.flop"));
        if(sound!=null) level.m_6263_(null,item.m_20185_(),item.m_20186_(),item.m_20189_(),sound,SoundSource.NEUTRAL,0.35F*strength,0.85F+0.2F*strength);
    }

    private static void send(ServerPlayer player,CatchState state) {
        if(CHANNEL.isRemotePresent(player.f_8906_.f_9742_)) CHANNEL.sendTo(packet(state),player.f_8906_.f_9742_,NetworkDirection.PLAY_TO_CLIENT);
    }
    private static CatchPacket packet(CatchState state) {
        return new CatchPacket(state.item.m_20148_(),state.item.m_19879_(),state.model,
                state.item.m_9236_().m_46472_().m_135782_(),state.landedAt,state.kickAt,state.yaw,state.kickStrength,state.timing);
    }
    private static void broadcast(ServerLevel level, CatchState state) {
        for (ServerPlayer player : level.m_6907_()) {
            if (player.m_20280_(state.item) <= 128.0 * 128.0) send(player,state);
        }
    }

    private static final class CatchState {
        final ItemEntity item;
        final ResourceLocation model;
        final long startedAt;
        final FishTiming timing;
        float yaw;
        long landedAt = -1;
        long kickAt=-1,nextKickAt;
        float kickStrength;
        Vec3 anchor;
        CatchState(ItemEntity item, ResourceLocation model, long now,FishTiming timing) {
            this.item = item; this.model = model; this.startedAt = now;this.timing=timing;this.anchor=item.m_20182_();
            Vec3 motion = item.m_20184_();
            this.yaw = (float)Math.toDegrees(Math.atan2(-motion.f_82479_, motion.f_82481_));
        }
    }

    public record CatchPacket(UUID itemUuid,int entityId,ResourceLocation model, ResourceLocation dimension,
                              long landedAt,long kickAt,float yaw,float kickStrength,FishTiming timing) {
        public static void encode(CatchPacket packet, FriendlyByteBuf out) {
            out.m_130077_(packet.itemUuid);
            out.writeInt(packet.entityId);
            out.m_130085_(packet.model);
            out.m_130085_(packet.dimension);
            out.writeLong(packet.landedAt);
            out.writeLong(packet.kickAt);
            out.writeFloat(packet.yaw);
            out.writeFloat(packet.kickStrength);
            out.writeInt(packet.timing.freshTicks());out.writeInt(packet.timing.fadingTicks());out.writeInt(packet.timing.stepTicks());
        }
        public static CatchPacket decode(FriendlyByteBuf in) {
            return new CatchPacket(in.m_130259_(),in.readInt(),in.m_130281_(),in.m_130281_(),in.readLong(),in.readLong(),in.readFloat(),in.readFloat(),
                    new FishTiming(in.readInt(),in.readInt(),in.readInt()));
        }
        public static void handle(CatchPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> dev.sunlit.fishvisuals.client.ClientFishVisuals.receive(packet)));
            context.setPacketHandled(true);
        }
    }
}
