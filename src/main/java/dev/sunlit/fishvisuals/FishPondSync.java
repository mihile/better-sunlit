package dev.sunlit.fishvisuals;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Syncs nearby society:fish_pond block entity fish type and population to clients
 * so the 3x4 pond water behind each fish pond can render live swimming fish visuals.
 */
public final class FishPondSync {
    public static final ResourceLocation FISH_POND_BLOCK_ID = new ResourceLocation("society", "fish_pond");
    private static final int SCAN_INTERVAL_TICKS = 10;
    private static final int CHUNK_RADIUS = 2;
    private static final double MAX_SYNC_DIST_SQ = 36.0D * 36.0D;
    private static final int NBT_ANY_NUMERIC = 99;
    private static final String PROTOCOL = "1";

    private static final class NetChannel {
        private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(SunlitFishVisuals.ID, "fish_pond"))
                .networkProtocolVersion(() -> PROTOCOL)
                .clientAcceptedVersions(FishPondSync::acceptsVersion)
                .serverAcceptedVersions(FishPondSync::acceptsVersion)
                .simpleChannel();
    }

    private static volatile Consumer<PondPacket> clientReceiver;
    private static final Map<UUID, Map<BlockPos, PondSnapshot>> SENT_BY_PLAYER = new HashMap<>();
    private static final Map<UUID, ResourceLocation> DIMENSION_BY_PLAYER = new HashMap<>();
    private static final Map<UUID, Integer> IMMEDIATE_UNTIL_TICK = new HashMap<>();
    private static volatile Class<?> cachedKjsBeClass;
    private static volatile Field cachedKjsDataField;
    private static boolean registered;

    private FishPondSync() {}

    public static boolean acceptsVersion(String version) {
        return PROTOCOL.equals(version)
                || NetworkRegistry.ABSENT.version().equals(version)
                || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }

    public static void setClientReceiver(Consumer<PondPacket> receiver) {
        clientReceiver = receiver;
    }

    public static void register() {
        if (registered) return;
        registered = true;
        NetChannel.CHANNEL.registerMessage(0, PondPacket.class, PondPacket::encode, PondPacket::decode,
                PondPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(event.getLevel().m_8055_(event.getPos()).m_60734_());
        if (FISH_POND_BLOCK_ID.equals(blockId)) {
            IMMEDIATE_UNTIL_TICK.put(player.m_20148_(), player.f_19797_ + 8);
        }
    }

    private static void onBreakBlock(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(event.getState().m_60734_());
        if (FISH_POND_BLOCK_ID.equals(blockId)) {
            IMMEDIATE_UNTIL_TICK.put(player.m_20148_(), player.f_19797_ + 4);
        }
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().m_20148_();
        SENT_BY_PLAYER.remove(id);
        DIMENSION_BY_PLAYER.remove(id);
        IMMEDIATE_UNTIL_TICK.remove(id);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        SENT_BY_PLAYER.clear();
        DIMENSION_BY_PLAYER.clear();
        IMMEDIATE_UNTIL_TICK.clear();
    }

    private static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (!NetChannel.CHANNEL.isRemotePresent(player.f_8906_.f_9742_)) return;
        if (!(player.m_9236_() instanceof ServerLevel level)) return;

        UUID uuid = player.m_20148_();
        ResourceLocation dim = level.m_46472_().m_135782_();
        ResourceLocation prevDim = DIMENSION_BY_PLAYER.put(uuid, dim);
        boolean dimChanged = !dim.equals(prevDim);
        if (dimChanged) {
            SENT_BY_PLAYER.remove(uuid);
        }

        int immediateUntil = IMMEDIATE_UNTIL_TICK.getOrDefault(uuid, -1);
        boolean immediate = dimChanged || player.f_19797_ <= immediateUntil;
        if (player.f_19797_ > immediateUntil && immediateUntil >= 0) {
            IMMEDIATE_UNTIL_TICK.remove(uuid);
        }
        if (!immediate && player.f_19797_ % SCAN_INTERVAL_TICKS != 0) return;

        Map<BlockPos, PondSnapshot> cache = SENT_BY_PLAYER.computeIfAbsent(uuid, k -> new HashMap<>());
        Set<BlockPos> seen = new HashSet<>();
        List<PondSnapshot> updates = new ArrayList<>();

        int pcx = player.m_146902_().f_45578_;
        int pcz = player.m_146902_().f_45579_;
        double px = player.m_20185_(), py = player.m_20186_(), pz = player.m_20189_();
        ServerChunkCache chunkSource = level.m_7726_();

        for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
            for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
                LevelChunk chunk = chunkSource.m_7131_(pcx + dx, pcz + dz);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.m_62954_().values()) {
                    if (be == null || be.m_58901_()) continue;
                    ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(be.m_58900_().m_60734_());
                    if (!FISH_POND_BLOCK_ID.equals(blockId)) continue;
                    BlockPos pos = be.m_58899_();
                    double ddx = (pos.m_123341_() + 0.5D) - px;
                    double ddy = (pos.m_123342_() + 0.5D) - py;
                    double ddz = (pos.m_123343_() + 0.5D) - pz;
                    if (ddx * ddx + ddy * ddy + ddz * ddz > MAX_SYNC_DIST_SQ) continue;

                    PondSnapshot snap = capturePond(pos, readPondData(be));
                    seen.add(pos);
                    PondSnapshot prev = cache.get(pos);
                    if (!snap.equals(prev)) {
                        cache.put(pos.m_7949_(), snap);
                        updates.add(snap);
                    }
                }
            }
        }

        List<BlockPos> removed = new ArrayList<>();
        var it = cache.keySet().iterator();
        while (it.hasNext()) {
            BlockPos oldPos = it.next();
            if (!seen.contains(oldPos)) {
                it.remove();
                removed.add(oldPos);
            }
        }

        if (!updates.isEmpty() || !removed.isEmpty()) {
            NetChannel.CHANNEL.sendTo(new PondPacket(dim, updates, removed),
                    player.f_8906_.f_9742_, NetworkDirection.PLAY_TO_CLIENT);
        }
    }

    private static CompoundTag readPondData(BlockEntity be) {
        Class<?> cls = be.getClass();
        Field field = cachedKjsDataField;
        if (cachedKjsBeClass != cls) {
            try {
                Field f = cls.getField("data");
                if (CompoundTag.class.isAssignableFrom(f.getType())) {
                    cachedKjsBeClass = cls;
                    cachedKjsDataField = f;
                    field = f;
                }
            } catch (NoSuchFieldException ignored) {
                field = null;
            }
        }
        if (field != null && cachedKjsBeClass == cls) {
            try {
                Object value = field.get(be);
                if (value instanceof CompoundTag tag) return tag;
            } catch (IllegalAccessException ignored) {
                // Fall back to NBT serialization below.
            }
        }
        CompoundTag saved = be.m_187482_();
        return saved.m_128441_("data") ? saved.m_128469_("data") : saved;
    }

    public static PondSnapshot capturePond(BlockPos pos, CompoundTag data) {
        if (data == null || data.m_128456_()) {
            return new PondSnapshot(pos.m_7949_(), "", 0, 3);
        }
        String type = data.m_128461_("type").trim();
        int population = readFlexibleInt(data, "population", 0);
        int maxPopulation = Math.max(1, readFlexibleInt(data, "max_population", 3));
        return new PondSnapshot(pos.m_7949_(), type, Math.max(0, Math.min(32, population)), Math.min(32, maxPopulation));
    }

    private static int readFlexibleInt(CompoundTag tag, String key, int fallback) {
        if (!tag.m_128441_(key)) return fallback;
        if (tag.m_128425_(key, NBT_ANY_NUMERIC)) {
            return (int) Math.floor(tag.m_128459_(key));
        }
        if (tag.m_128425_(key, 8)) {
            try {
                return (int) Math.floor(Double.parseDouble(tag.m_128461_(key).trim()));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public record PondSnapshot(BlockPos pos, String fishId, int population, int maxPopulation) {}

    public record PondPacket(ResourceLocation dimension, List<PondSnapshot> updates, List<BlockPos> removed) {
        public static void encode(PondPacket packet, FriendlyByteBuf out) {
            out.m_130085_(packet.dimension);
            out.writeInt(packet.updates.size());
            for (PondSnapshot snap : packet.updates) {
                out.m_130064_(snap.pos);
                out.m_130070_(snap.fishId);
                out.writeByte(snap.population);
                out.writeByte(snap.maxPopulation);
            }
            out.writeInt(packet.removed.size());
            for (BlockPos pos : packet.removed) {
                out.m_130064_(pos);
            }
        }

        public static PondPacket decode(FriendlyByteBuf in) {
            ResourceLocation dim = in.m_130281_();
            int updateCount = Math.max(0, Math.min(512, in.readInt()));
            List<PondSnapshot> updates = new ArrayList<>(updateCount);
            for (int i = 0; i < updateCount; i++) {
                updates.add(new PondSnapshot(
                        in.m_130135_(),
                        in.m_130136_(128),
                        in.readUnsignedByte(),
                        in.readUnsignedByte()
                ));
            }
            int removeCount = Math.max(0, Math.min(512, in.readInt()));
            List<BlockPos> removed = new ArrayList<>(removeCount);
            for (int i = 0; i < removeCount; i++) {
                removed.add(in.m_130135_());
            }
            return new PondPacket(dim, updates, removed);
        }

        public static void handle(PondPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            Consumer<PondPacket> receiver = clientReceiver;
            if (receiver != null) {
                context.enqueueWork(() -> receiver.accept(packet));
            }
            context.setPacketHandled(true);
        }
    }
}
