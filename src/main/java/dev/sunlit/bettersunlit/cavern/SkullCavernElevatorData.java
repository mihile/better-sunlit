package dev.sunlit.bettersunlit.cavern;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-dimension SavedData storing every independent Skull Cavern mine shaft and its
 * discovered elevator stations (10F/20F/30F checkpoints and Copper Dungeons / Trial Chambers).
 */
public final class SkullCavernElevatorData extends SavedData {
    public static final String DATA_NAME = "sunlit_skull_elevators";
    public static final byte KIND_SURFACE = 0;
    public static final byte KIND_FLOOR = 1;
    public static final byte KIND_TRIAL_CHAMBER = 2;
    public static final String SURFACE_KEY = "surface";
    public static final int MINE_LINK_RADIUS_SQ = 48 * 48;

    public static final class ElevatorStop {
        public final String key;
        public final byte kind;
        public final int floor;
        public BlockPos elevatorPos;
        public BlockPos landingPos;
        public final String pioneer;

        public ElevatorStop(String key, byte kind, int floor, BlockPos elevatorPos, BlockPos landingPos, String pioneer) {
            this.key = key;
            this.kind = kind;
            this.floor = floor;
            this.elevatorPos = elevatorPos.m_7949_();
            this.landingPos = landingPos.m_7949_();
            this.pioneer = pioneer == null ? "" : pioneer;
        }
    }

    public static final class MineShaft {
        public final String id;
        public final BlockPos surfaceAnchor;
        public BlockPos surfaceElevatorPos;
        public BlockPos surfaceLandingPos;
        public final List<ElevatorStop> stops = new ArrayList<>();

        public MineShaft(String id, BlockPos surfaceAnchor) {
            this.id = id;
            this.surfaceAnchor = surfaceAnchor.m_7949_();
        }

        public ElevatorStop findStop(String key) {
            for (ElevatorStop stop : stops) {
                if (stop.key.equals(key)) return stop;
            }
            return null;
        }

        public List<ElevatorStop> sortedStops() {
            List<ElevatorStop> copy = new ArrayList<>(stops);
            copy.sort(Comparator.comparingInt((ElevatorStop s) -> s.kind == KIND_TRIAL_CHAMBER ? 1 : 0)
                    .thenComparingInt(s -> s.floor)
                    .thenComparing(s -> s.key));
            return copy;
        }
    }

    public record StationLookup(MineShaft mine, String stopKey, BlockPos elevatorPos, boolean isLantern) {}

    private final Map<String, MineShaft> minesById = new HashMap<>();
    private final Map<BlockPos, StationLookup> stationByBlock = new HashMap<>();

    public SkullCavernElevatorData() {}

    public static SkullCavernElevatorData get(ServerLevel cavernLevel) {
        return cavernLevel.m_8895_().m_164861_(SkullCavernElevatorData::load, SkullCavernElevatorData::new, DATA_NAME);
    }

    public static SkullCavernElevatorData load(CompoundTag root) {
        SkullCavernElevatorData data = new SkullCavernElevatorData();
        ListTag mineList = root.m_128437_("mines", 10);
        for (int i = 0; i < mineList.size(); i++) {
            CompoundTag mt = mineList.m_128728_(i);
            String id = mt.m_128461_("id");
            if (id.isEmpty()) continue;
            BlockPos anchor = readPos(mt, "anchor");
            if (anchor == null) continue;
            MineShaft mine = new MineShaft(id, anchor);
            mine.surfaceElevatorPos = readPos(mt, "surfElev");
            mine.surfaceLandingPos = readPos(mt, "surfLand");
            ListTag stopList = mt.m_128437_("stops", 10);
            for (int j = 0; j < stopList.size(); j++) {
                CompoundTag st = stopList.m_128728_(j);
                String key = st.m_128461_("key");
                BlockPos elevPos = readPos(st, "elev");
                BlockPos landPos = readPos(st, "land");
                if (key.isEmpty() || elevPos == null || landPos == null) continue;
                byte kind = st.m_128445_("kind");
                int floor = st.m_128451_("floor");
                String pioneer = st.m_128461_("pioneer");
                mine.stops.add(new ElevatorStop(key, kind, floor, elevPos, landPos, pioneer));
            }
            data.minesById.put(mine.id, mine);
        }
        data.rebuildIndex();
        return data;
    }

    @Override
    public CompoundTag m_7176_(CompoundTag root) {
        ListTag mineList = new ListTag();
        for (MineShaft mine : minesById.values()) {
            CompoundTag mt = new CompoundTag();
            mt.m_128359_("id", mine.id);
            writePos(mt, "anchor", mine.surfaceAnchor);
            if (mine.surfaceElevatorPos != null) writePos(mt, "surfElev", mine.surfaceElevatorPos);
            if (mine.surfaceLandingPos != null) writePos(mt, "surfLand", mine.surfaceLandingPos);
            ListTag stopList = new ListTag();
            for (ElevatorStop stop : mine.stops) {
                CompoundTag st = new CompoundTag();
                st.m_128359_("key", stop.key);
                st.m_128344_("kind", stop.kind);
                st.m_128405_("floor", stop.floor);
                writePos(st, "elev", stop.elevatorPos);
                writePos(st, "land", stop.landingPos);
                st.m_128359_("pioneer", stop.pioneer);
                stopList.add(st);
            }
            mt.m_128365_("stops", stopList);
            mineList.add(mt);
        }
        root.m_128365_("mines", mineList);
        return root;
    }

    public MineShaft getById(String id) {
        return id == null ? null : minesById.get(id);
    }

    public MineShaft findNearestBySurface(int x, int z) {
        MineShaft best = null;
        long bestDistSq = MINE_LINK_RADIUS_SQ;
        for (MineShaft mine : minesById.values()) {
            long dx = (long) mine.surfaceAnchor.m_123341_() - x;
            long dz = (long) mine.surfaceAnchor.m_123343_() - z;
            long d2 = dx * dx + dz * dz;
            if (d2 <= bestDistSq) {
                bestDistSq = d2;
                best = mine;
            }
        }
        return best;
    }

    public MineShaft getOrCreateMine(BlockPos surfaceAnchor) {
        MineShaft existing = findNearestBySurface(surfaceAnchor.m_123341_(), surfaceAnchor.m_123343_());
        if (existing != null) return existing;
        String id = "mine_" + surfaceAnchor.m_123341_() + "_" + surfaceAnchor.m_123343_();
        int suffix = 1;
        while (minesById.containsKey(id)) {
            id = "mine_" + surfaceAnchor.m_123341_() + "_" + surfaceAnchor.m_123343_() + "_" + (suffix++);
        }
        MineShaft created = new MineShaft(id, surfaceAnchor);
        minesById.put(id, created);
        m_77762_();
        return created;
    }

    public void setSurfaceStation(MineShaft mine, BlockPos elevatorPos, BlockPos landingPos) {
        mine.surfaceElevatorPos = elevatorPos.m_7949_();
        mine.surfaceLandingPos = landingPos.m_7949_();
        rebuildIndex();
        m_77762_();
    }

    public void addOrUpdateStop(MineShaft mine, ElevatorStop stop) {
        for (int i = 0; i < mine.stops.size(); i++) {
            if (mine.stops.get(i).key.equals(stop.key)) {
                mine.stops.set(i, stop);
                rebuildIndex();
                m_77762_();
                return;
            }
        }
        mine.stops.add(stop);
        rebuildIndex();
        m_77762_();
    }

    public StationLookup lookupStation(BlockPos pos) {
        return stationByBlock.get(pos);
    }

    public boolean isProtectedBlock(BlockPos pos) {
        return stationByBlock.containsKey(pos);
    }

    public List<StationLookup> findNearbyStations(BlockPos center, int radiusBlocks) {
        long rSq = (long) radiusBlocks * radiusBlocks;
        List<StationLookup> result = new ArrayList<>();
        for (StationLookup lookup : stationByBlock.values()) {
            if (lookup.isLantern()) continue;
            long dx = (long) lookup.elevatorPos().m_123341_() - center.m_123341_();
            long dy = (long) lookup.elevatorPos().m_123342_() - center.m_123342_();
            long dz = (long) lookup.elevatorPos().m_123343_() - center.m_123343_();
            if (dx * dx + dy * dy + dz * dz <= rSq) {
                result.add(lookup);
            }
        }
        return result;
    }

    private void rebuildIndex() {
        stationByBlock.clear();
        for (MineShaft mine : minesById.values()) {
            if (mine.surfaceElevatorPos != null) {
                BlockPos p = mine.surfaceElevatorPos;
                stationByBlock.put(p, new StationLookup(mine, SURFACE_KEY, p, false));
                stationByBlock.put(p.m_7494_(), new StationLookup(mine, SURFACE_KEY, p, true));
            }
            for (ElevatorStop stop : mine.stops) {
                BlockPos p = stop.elevatorPos;
                stationByBlock.put(p, new StationLookup(mine, stop.key, p, false));
                stationByBlock.put(p.m_7494_(), new StationLookup(mine, stop.key, p, true));
            }
        }
    }

    private static void writePos(CompoundTag tag, String prefix, BlockPos pos) {
        tag.m_128405_(prefix + "X", pos.m_123341_());
        tag.m_128405_(prefix + "Y", pos.m_123342_());
        tag.m_128405_(prefix + "Z", pos.m_123343_());
    }

    private static BlockPos readPos(CompoundTag tag, String prefix) {
        if (!tag.m_128441_(prefix + "X")) return null;
        return new BlockPos(tag.m_128451_(prefix + "X"), tag.m_128451_(prefix + "Y"), tag.m_128451_(prefix + "Z"));
    }
}
