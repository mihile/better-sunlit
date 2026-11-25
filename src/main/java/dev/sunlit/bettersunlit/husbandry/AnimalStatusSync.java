package dev.sunlit.bettersunlit.husbandry;

import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Syncs hidden animal harvest cooldown and readiness state from KubeJS persistentData
 * to nearby clients holding tool-harvest items (milk pail, magic shears, glass bottle).
 */
public final class AnimalStatusSync {
    public static final long TICKS_PER_DAY = 24000L;
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final double SCAN_RADIUS = 20.0D;

    public static final ResourceLocation MILK_PAIL_ID = new ResourceLocation("society", "milk_pail");
    public static final ResourceLocation MAGIC_SHEARS_ID = new ResourceLocation("society", "magic_shears");
    public static final ResourceLocation GLASS_BOTTLE_ID = new ResourceLocation("minecraft", "glass_bottle");

    public static final ResourceLocation WOOLY_COW_ID = new ResourceLocation("meadow", "wooly_cow");
    public static final ResourceLocation MAMMUTILATION_ID = new ResourceLocation("species", "mammutilation");

    private static final class EntityTags {
        private static final ResourceKey<Registry<EntityType<?>>> ENTITY_TYPE_REGISTRY =
                ResourceKey.m_135788_(new ResourceLocation("entity_type"));
        private static final TagKey<EntityType<?>> HUSBANDRY =
                TagKey.m_203882_(ENTITY_TYPE_REGISTRY, new ResourceLocation("society", "husbandry_animal"));
        private static final TagKey<EntityType<?>> MILKABLE =
                TagKey.m_203882_(ENTITY_TYPE_REGISTRY, new ResourceLocation("society", "milkable_animal"));
    }

    public static final int FLAG_MILKABLE = 1 << 0;
    public static final int FLAG_HUSBANDRY = 1 << 1;
    public static final int FLAG_WARPED = 1 << 2;
    public static final int FLAG_HAS_FED = 1 << 3;
    public static final int FLAG_HAS_MILKED = 1 << 4;
    public static final int FLAG_HAS_MAGIC = 1 << 5;

    private static final int NBT_ANY_NUMERIC = 99;
    private static final String PROTOCOL = "2";
    private static final class NetChannel {
        private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(SunlitFishVisuals.ID, "animal_status"))
                .networkProtocolVersion(() -> PROTOCOL)
                .clientAcceptedVersions(AnimalStatusSync::acceptsVersion)
                .serverAcceptedVersions(AnimalStatusSync::acceptsVersion)
                .simpleChannel();
    }

    private static volatile Consumer<StatusPacket> clientReceiver;
    private static final Map<UUID, Map<Integer, AnimalSnapshot>> SENT_BY_PLAYER = new HashMap<>();
    private static final Map<UUID, Integer> IMMEDIATE_UNTIL_TICK = new HashMap<>();
    private static final Map<UUID, Boolean> LAST_HELD_TOOL = new HashMap<>();
    private static final Map<UUID, Long> LAST_SENT_DAY_TIME = new HashMap<>();
    private static volatile boolean kjsLookupAttempted;
    private static volatile MethodHandle kjsRawPersistentDataHandle;
    private static boolean registered;

    private AnimalStatusSync() {}

    public static boolean acceptsVersion(String version) {
        return PROTOCOL.equals(version)
                || NetworkRegistry.ABSENT.version().equals(version)
                || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }

    public static void setClientReceiver(Consumer<StatusPacket> receiver) {
        clientReceiver = receiver;
    }

    public static void register() {
        if (registered) return;
        registered = true;
        NetChannel.CHANNEL.registerMessage(0, StatusPacket.class, StatusPacket::encode, StatusPacket::decode,
                StatusPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(AnimalStatusSync::onPlayerTick);
        MinecraftForge.EVENT_BUS.addListener(AnimalStatusSync::onEntityInteract);
        MinecraftForge.EVENT_BUS.addListener(AnimalStatusSync::onPlayerLogout);
        MinecraftForge.EVENT_BUS.addListener(AnimalStatusSync::onServerStopped);
    }

    public static boolean isWatchedTool(ItemStack stack) {
        if (stack == null || stack.m_41619_()) return false;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return MILK_PAIL_ID.equals(id) || MAGIC_SHEARS_ID.equals(id) || GLASS_BOTTLE_ID.equals(id);
    }

    private static boolean isHoldingWatchedTool(ServerPlayer player) {
        return isWatchedTool(player.m_21205_()) || isWatchedTool(player.m_21206_());
    }

    private static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        Entity target = event.getTarget();
        if (!(target instanceof LivingEntity living)) return;
        EntityType<?> type = living.m_6095_();
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(type);
        if (type.m_204039_(EntityTags.HUSBANDRY) || type.m_204039_(EntityTags.MILKABLE) || MAMMUTILATION_ID.equals(typeId)) {
            IMMEDIATE_UNTIL_TICK.put(player.m_20148_(), player.f_19797_ + 6);
        }
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().m_20148_();
        SENT_BY_PLAYER.remove(id);
        IMMEDIATE_UNTIL_TICK.remove(id);
        LAST_HELD_TOOL.remove(id);
        LAST_SENT_DAY_TIME.remove(id);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        SENT_BY_PLAYER.clear();
        IMMEDIATE_UNTIL_TICK.clear();
        LAST_HELD_TOOL.clear();
        LAST_SENT_DAY_TIME.clear();
    }

    private static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (!NetChannel.CHANNEL.isRemotePresent(player.f_8906_.f_9742_)) return;

        UUID uuid = player.m_20148_();
        boolean holding = isHoldingWatchedTool(player);
        boolean wasHolding = Boolean.TRUE.equals(LAST_HELD_TOOL.put(uuid, holding));
        if (!holding) {
            if (wasHolding) {
                SENT_BY_PLAYER.remove(uuid);
                LAST_SENT_DAY_TIME.remove(uuid);
            }
            return;
        }

        int immediateUntil = IMMEDIATE_UNTIL_TICK.getOrDefault(uuid, -1);
        boolean immediate = !wasHolding || player.f_19797_ <= immediateUntil;
        if (player.f_19797_ > immediateUntil && immediateUntil >= 0) {
            IMMEDIATE_UNTIL_TICK.remove(uuid);
        }
        if (!immediate && player.f_19797_ % SCAN_INTERVAL_TICKS != 0) return;

        if (!(player.m_9236_() instanceof ServerLevel level)) return;
        long rawDayTime = level.m_46468_();
        long dayTime = rawDayTime < 0L ? -rawDayTime : rawDayTime;
        long currentDay = Math.floorDiv(dayTime, TICKS_PER_DAY) + 1L;
        AABB box = player.m_20191_().m_82400_(SCAN_RADIUS);
        List<LivingEntity> animals = level.m_6443_(LivingEntity.class, box, e -> {
            if (!e.m_6084_()) return false;
            EntityType<?> t = e.m_6095_();
            return t.m_204039_(EntityTags.HUSBANDRY)
                    || t.m_204039_(EntityTags.MILKABLE)
                    || MAMMUTILATION_ID.equals(ForgeRegistries.ENTITY_TYPES.getKey(t));
        });

        Map<Integer, AnimalSnapshot> cache = SENT_BY_PLAYER.computeIfAbsent(uuid, k -> new HashMap<>());
        List<AnimalSnapshot> changed = new ArrayList<>();
        for (LivingEntity animal : animals) {
            AnimalSnapshot snap = captureSnapshot(animal, dayTime, currentDay);
            AnimalSnapshot prev = cache.get(snap.entityId());
            if (!snap.equals(prev)) {
                cache.put(snap.entityId(), snap);
                changed.add(snap);
            }
        }
        Long lastSent = LAST_SENT_DAY_TIME.get(uuid);
        boolean timeJumped = lastSent == null || Math.abs(dayTime - lastSent) > 100L;
        if (!changed.isEmpty() || timeJumped) {
            LAST_SENT_DAY_TIME.put(uuid, dayTime);
            NetChannel.CHANNEL.sendTo(new StatusPacket(level.m_46472_().m_135782_(), dayTime, changed),
                    player.f_8906_.f_9742_, NetworkDirection.PLAY_TO_CLIENT);
        }
        if (cache.size() > 512) {
            cache.clear();
        }
    }

    private static CompoundTag readKubePersistentData(LivingEntity animal) {
        if (!kjsLookupAttempted) {
            kjsLookupAttempted = true;
            try {
                kjsRawPersistentDataHandle = MethodHandles.publicLookup().findVirtual(
                        Entity.class, "kjs$getRawPersistentData", MethodType.methodType(CompoundTag.class));
            } catch (Throwable ignored) {
                kjsRawPersistentDataHandle = null;
            }
        }
        MethodHandle handle = kjsRawPersistentDataHandle;
        if (handle == null) return null;
        try {
            return (CompoundTag) handle.invoke((Entity) animal);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean hasNumericTag(CompoundTag primary, CompoundTag secondary, String key) {
        return (primary != null && primary.m_128425_(key, NBT_ANY_NUMERIC))
                || (secondary != null && secondary.m_128425_(key, NBT_ANY_NUMERIC));
    }

    private static int readNumericInt(CompoundTag primary, CompoundTag secondary, String key) {
        if (primary != null && primary.m_128425_(key, NBT_ANY_NUMERIC)) {
            return (int) Math.floor(primary.m_128459_(key));
        }
        if (secondary != null && secondary.m_128425_(key, NBT_ANY_NUMERIC)) {
            return (int) Math.floor(secondary.m_128459_(key));
        }
        return 0;
    }

    private static AnimalSnapshot captureSnapshot(LivingEntity animal, long dayTime, long currentDay) {
        EntityType<?> type = animal.m_6095_();
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(type);
        boolean isBaby = animal.m_6162_();
        CompoundTag saved = (WOOLY_COW_ID.equals(typeId) || (MAMMUTILATION_ID.equals(typeId) && !isBaby))
                ? animal.m_20240_(new CompoundTag())
                : null;
        return captureFromTags(
                animal.m_19879_(),
                type.m_204039_(EntityTags.MILKABLE),
                type.m_204039_(EntityTags.HUSBANDRY),
                typeId,
                isBaby,
                readKubePersistentData(animal),
                animal.getPersistentData(),
                saved,
                dayTime,
                currentDay
        );
    }

    /** Builds an animal status snapshot from KubeJS persistentData with ForgeData fallback. */
    public static AnimalSnapshot captureFromTags(
            int entityId,
            boolean milkable,
            boolean husbandry,
            ResourceLocation typeId,
            boolean isBaby,
            CompoundTag kjsData,
            CompoundTag forgeData,
            CompoundTag savedNbt,
            long dayTime,
            long currentDay
    ) {
        // KubeJS stores animal.persistentData in KubeJSPersistentData via EntityMixin rather than ForgeData.
        boolean hasFed = hasNumericTag(kjsData, forgeData, "ageLastFed");
        boolean hasMilked = hasNumericTag(kjsData, forgeData, "ageLastMilked");
        boolean hasMagic = hasNumericTag(kjsData, forgeData, "ageLastMagicHarvested");

        int ageLastFed = hasFed ? readNumericInt(kjsData, forgeData, "ageLastFed") : 0;
        int ageLastMilked = hasMilked ? readNumericInt(kjsData, forgeData, "ageLastMilked") : 0;
        int ageLastMagic = hasMagic ? readNumericInt(kjsData, forgeData, "ageLastMagicHarvested") : 0;

        CompoundTag stampTarget = forgeData != null ? forgeData : kjsData;
        long timeLastMilked = resolveActionTick(stampTarget, "sunlitMilkDay", "sunlitMilkTick", hasMilked, ageLastMilked, currentDay, dayTime);
        long timeLastMagic = resolveActionTick(stampTarget, "sunlitMagicDay", "sunlitMagicTick", hasMagic, ageLastMagic, currentDay, dayTime);

        int affection = readNumericInt(kjsData, forgeData, "affection");
        int hearts = Math.max(0, Math.min(10, affection / 100));

        boolean warped = false;
        byte ichorState = -1;
        if (WOOLY_COW_ID.equals(typeId) && savedNbt != null) {
            warped = savedNbt.m_128451_("Variant") == 2;
        } else if (MAMMUTILATION_ID.equals(typeId) && !isBaby && savedNbt != null) {
            int count = savedNbt.m_128451_("IchorCount");
            int cd = savedNbt.m_128451_("IchorCooldown");
            ichorState = (byte) ((count > 0 && cd <= 0) ? 3 : 0);
        }

        int flags = 0;
        if (milkable) flags |= FLAG_MILKABLE;
        if (husbandry) flags |= FLAG_HUSBANDRY;
        if (warped) flags |= FLAG_WARPED;
        if (hasFed) flags |= FLAG_HAS_FED;
        if (hasMilked) flags |= FLAG_HAS_MILKED;
        if (hasMagic) flags |= FLAG_HAS_MAGIC;

        return new AnimalSnapshot(
                entityId, (byte) flags,
                ageLastMilked, timeLastMilked,
                ageLastMagic, timeLastMagic,
                ageLastFed, (byte) hearts, ichorState
        );
    }

    private static long resolveActionTick(CompoundTag data, String dayKey, String tickKey,
                                        boolean hasDay, int actionDay, long currentDay, long dayTime) {
        if (!hasDay || actionDay <= 0) return 0L;
        if (data == null) {
            return (actionDay == currentDay) ? dayTime : (actionDay - 1L) * TICKS_PER_DAY;
        }
        if (data.m_128441_(dayKey) && data.m_128451_(dayKey) == actionDay) {
            return data.m_128454_(tickKey);
        }
        long stampedTick = (actionDay == currentDay) ? dayTime : (actionDay - 1L) * TICKS_PER_DAY;
        data.m_128405_(dayKey, actionDay);
        data.m_128356_(tickKey, stampedTick);
        return stampedTick;
    }

    public record AnimalSnapshot(
            int entityId,
            byte flags,
            int ageLastMilked,
            long timeLastMilked,
            int ageLastMagic,
            long timeLastMagic,
            int ageLastFed,
            byte hearts,
            byte ichorState
    ) {
        public boolean isMilkable() { return (flags & FLAG_MILKABLE) != 0; }
        public boolean isHusbandry() { return (flags & FLAG_HUSBANDRY) != 0; }
        public boolean isWarped() { return (flags & FLAG_WARPED) != 0; }
        public boolean hasFedTag() { return (flags & FLAG_HAS_FED) != 0; }
        public boolean hasMilkedTag() { return (flags & FLAG_HAS_MILKED) != 0; }
        public boolean hasMagicTag() { return (flags & FLAG_HAS_MAGIC) != 0; }
    }

    public record StatusPacket(ResourceLocation dimension, long serverDayTime, List<AnimalSnapshot> entries) {
        public static void encode(StatusPacket packet, FriendlyByteBuf out) {
            out.m_130085_(packet.dimension);
            out.writeLong(packet.serverDayTime);
            out.writeInt(packet.entries.size());
            for (AnimalSnapshot e : packet.entries) {
                out.writeInt(e.entityId);
                out.writeByte(e.flags);
                out.writeInt(e.ageLastMilked);
                out.writeLong(e.timeLastMilked);
                out.writeInt(e.ageLastMagic);
                out.writeLong(e.timeLastMagic);
                out.writeInt(e.ageLastFed);
                out.writeByte(e.hearts);
                out.writeByte(e.ichorState);
            }
        }

        public static StatusPacket decode(FriendlyByteBuf in) {
            ResourceLocation dim = in.m_130281_();
            long serverDayTime = in.readLong();
            int count = Math.max(0, Math.min(1024, in.readInt()));
            List<AnimalSnapshot> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                entries.add(new AnimalSnapshot(
                        in.readInt(),
                        in.readByte(),
                        in.readInt(),
                        in.readLong(),
                        in.readInt(),
                        in.readLong(),
                        in.readInt(),
                        in.readByte(),
                        in.readByte()
                ));
            }
            return new StatusPacket(dim, serverDayTime, entries);
        }

        public static void handle(StatusPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            Consumer<StatusPacket> receiver = clientReceiver;
            if (receiver != null) {
                context.enqueueWork(() -> receiver.accept(packet));
            }
            context.setPacketHandled(true);
        }
    }
}
