package dev.sunlit.bettersunlit.cavern;

import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.registries.ForgeRegistries;

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
 * Per-mine Skull Cavern Elevator system:
 * - Tracks each independent mine shaft from its surface entrance.
 * - Automatically spawns an elevator pedestal (lodestone + lantern) at every 10th floor (10F/20F/30F)
 *   and at discovered Copper Dungeons (trials:trials_chambers), plus at that mine's surface entrance
 *   once its first stop is discovered.
 * - Never reveals unreached/locked floors; records the pioneering player's name on every discovered stop.
 * - Fully optional client/server network channel (accepts ABSENT / ACCEPTVANILLA, zero new registries).
 */
public final class SkullCavernElevator {
    public static final ResourceLocation SKULL_CAVERN_ID = new ResourceLocation("society", "skull_cavern");
    public static final ResourceLocation ELEVATOR_BLOCK_ID = new ResourceLocation("minecraft", "lodestone");
    public static final ResourceLocation LANTERN_BLOCK_ID = new ResourceLocation("minecraft", "lantern");
    public static final ResourceLocation TRIAL_CHAMBER_ID = new ResourceLocation("trials", "trials_chambers");
    public static final ResourceLocation SKULL_STONE_ID = new ResourceLocation("society", "skull_stone");

    public static final int SURFACE_Y = 512;
    public static final int TOP_CEILING_Y = 504;
    public static final int BLOCKS_PER_FLOOR = 16;
    public static final int MAX_FLOOR = 31;
    public static final int FLOOR_STEP = 10;

    private static final String TAG_ACTIVE_MINE = "sunlit_active_mine";
    private static final String TAG_ANCHOR_X = "sunlit_mine_anchor_x";
    private static final String TAG_ANCHOR_Z = "sunlit_mine_anchor_z";
    private static final double MAX_INTERACT_DIST_SQ = 9.0D * 9.0D;
    private static final int NEARBY_MARKER_RADIUS = 18;
    private static final String PROTOCOL = "2";

    private static final class DimKeys {
        private static final ResourceKey<Registry<Structure>> STRUCTURE_REGISTRY =
                ResourceKey.m_135788_(new ResourceLocation("worldgen/structure"));
        private static final ResourceKey<Structure> TRIAL_CHAMBER =
                ResourceKey.m_135785_(STRUCTURE_REGISTRY, TRIAL_CHAMBER_ID);
    }

    private static final class NetChannel {
        private static final SimpleChannel CHANNEL = NetworkRegistry.ChannelBuilder
                .named(new ResourceLocation(SunlitFishVisuals.ID, "skull_elevator"))
                .networkProtocolVersion(() -> PROTOCOL)
                .clientAcceptedVersions(SkullCavernElevator::acceptsVersion)
                .serverAcceptedVersions(SkullCavernElevator::acceptsVersion)
                .simpleChannel();
    }

    private static final Map<UUID, Set<String>> SEEN_TOASTS_BY_PLAYER = new HashMap<>();
    private static final Map<UUID, Boolean> LAST_TRIAL_STATE = new HashMap<>();
    private static volatile Consumer<OpenPacket> clientOpenReceiver;
    private static volatile Consumer<ToastPacket> clientToastReceiver;
    private static volatile Consumer<HudPacket> clientHudReceiver;
    private static boolean registered;

    private SkullCavernElevator() {}

    public static boolean acceptsVersion(String version) {
        return PROTOCOL.equals(version)
                || NetworkRegistry.ABSENT.version().equals(version)
                || NetworkRegistry.ACCEPTVANILLA.equals(version);
    }

    public static void setClientReceivers(Consumer<OpenPacket> openReceiver,
                                          Consumer<ToastPacket> toastReceiver,
                                          Consumer<HudPacket> hudReceiver) {
        clientOpenReceiver = openReceiver;
        clientToastReceiver = toastReceiver;
        clientHudReceiver = hudReceiver;
    }

    public static void sendUseElevator(BlockPos elevatorPos, String targetStopKey) {
        NetChannel.CHANNEL.sendToServer(new UseElevatorPacket(elevatorPos, targetStopKey));
    }

    public static boolean isServerChannelPresent(net.minecraft.network.Connection connection) {
        return connection != null && NetChannel.CHANNEL.isRemotePresent(connection);
    }

    public static void register() {
        if (registered) return;
        registered = true;
        NetChannel.CHANNEL.registerMessage(0, OpenPacket.class, OpenPacket::encode, OpenPacket::decode,
                OpenPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        NetChannel.CHANNEL.registerMessage(1, ToastPacket.class, ToastPacket::encode, ToastPacket::decode,
                ToastPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        NetChannel.CHANNEL.registerMessage(2, HudPacket.class, HudPacket::encode, HudPacket::decode,
                HudPacket::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        NetChannel.CHANNEL.registerMessage(3, UseElevatorPacket.class, UseElevatorPacket::encode, UseElevatorPacket::decode,
                UseElevatorPacket::handle, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, SkullCavernElevator::onRightClickBlock);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, SkullCavernElevator::onLeftClickBlock);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, SkullCavernElevator::onBreakBlock);
        MinecraftForge.EVENT_BUS.addListener(EventPriority.HIGHEST, SkullCavernElevator::onExplosionDetonate);
        MinecraftForge.EVENT_BUS.addListener(SkullCavernElevator::onPlayerTick);
        MinecraftForge.EVENT_BUS.addListener(SkullCavernElevator::onPlayerChangedDimension);
        MinecraftForge.EVENT_BUS.addListener(SkullCavernElevator::onPlayerLogout);
        MinecraftForge.EVENT_BUS.addListener(SkullCavernElevator::onServerStopped);
    }

    /** Maps a Y coordinate in society:skull_cavern to its 16-block floor index (0 = surface, 1..31 = underground). */
    public static int floorForY(int y) {
        if (y >= TOP_CEILING_Y) return 0;
        return Mth.m_14045_((TOP_CEILING_Y - 1 - y) / BLOCKS_PER_FLOOR + 1, 1, MAX_FLOOR);
    }

    public static boolean isMilestoneFloor(int floor) {
        return floor >= FLOOR_STEP && floor <= MAX_FLOOR && (floor % FLOOR_STEP == 0);
    }

    public static String floorStopKey(int floor) {
        return "floor_" + floor;
    }

    public static String trialStopKey(BlockPos center) {
        return "trial_" + center.m_123341_() + "_" + center.m_123343_();
    }

    public static boolean isSkullCavern(Level level) {
        return level != null && SKULL_CAVERN_ID.equals(level.m_46472_().m_135782_());
    }

    private static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID uuid = player.m_20148_();
        SEEN_TOASTS_BY_PLAYER.remove(uuid);
        LAST_TRIAL_STATE.remove(uuid);
        if (SKULL_CAVERN_ID.equals(event.getTo().m_135782_())) {
            bindSurfaceAnchor(player, player.m_284548_(), player.m_20183_());
            syncHud(player, player.m_284548_(), false);
        } else {
            syncHudEmpty(player);
        }
    }

    private static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID uuid = event.getEntity().m_20148_();
        SEEN_TOASTS_BY_PLAYER.remove(uuid);
        LAST_TRIAL_STATE.remove(uuid);
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        SEEN_TOASTS_BY_PLAYER.clear();
        LAST_TRIAL_STATE.clear();
    }

    private static void bindSurfaceAnchor(ServerPlayer player, ServerLevel cavern, BlockPos pos) {
        SkullCavernElevatorData data = SkullCavernElevatorData.get(cavern);
        int x = pos.m_123341_();
        int z = pos.m_123343_();
        SkullCavernElevatorData.MineShaft existing = data.findNearestBySurface(x, z);
        var tag = player.getPersistentData();
        if (existing != null) {
            tag.m_128359_(TAG_ACTIVE_MINE, existing.id);
            tag.m_128405_(TAG_ANCHOR_X, existing.surfaceAnchor.m_123341_());
            tag.m_128405_(TAG_ANCHOR_Z, existing.surfaceAnchor.m_123343_());
        } else {
            tag.m_128473_(TAG_ACTIVE_MINE);
            tag.m_128405_(TAG_ANCHOR_X, x);
            tag.m_128405_(TAG_ANCHOR_Z, z);
        }
    }

    private static BlockPos resolvePlayerSurfaceAnchor(ServerPlayer player) {
        var tag = player.getPersistentData();
        if (tag.m_128441_(TAG_ANCHOR_X) && tag.m_128441_(TAG_ANCHOR_Z)) {
            return new BlockPos(tag.m_128451_(TAG_ANCHOR_X), SURFACE_Y, tag.m_128451_(TAG_ANCHOR_Z));
        }
        return new BlockPos(player.m_146903_(), SURFACE_Y, player.m_146907_());
    }

    private static SkullCavernElevatorData.MineShaft resolveOrCreateActiveMine(ServerPlayer player, ServerLevel cavern) {
        SkullCavernElevatorData data = SkullCavernElevatorData.get(cavern);
        var tag = player.getPersistentData();
        if (tag.m_128441_(TAG_ACTIVE_MINE)) {
            SkullCavernElevatorData.MineShaft byId = data.getById(tag.m_128461_(TAG_ACTIVE_MINE));
            if (byId != null) return byId;
        }
        BlockPos anchor = resolvePlayerSurfaceAnchor(player);
        SkullCavernElevatorData.MineShaft mine = data.getOrCreateMine(anchor);
        tag.m_128359_(TAG_ACTIVE_MINE, mine.id);
        tag.m_128405_(TAG_ANCHOR_X, mine.surfaceAnchor.m_123341_());
        tag.m_128405_(TAG_ANCHOR_Z, mine.surfaceAnchor.m_123343_());
        return mine;
    }

    private static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) return;
        if (!(event.player instanceof ServerPlayer player)) return;
        if (player.f_19797_ % 10 != 0) return;
        ServerLevel level = player.m_284548_();
        if (!isSkullCavern(level)) return;

        BlockPos playerPos = player.m_20183_();
        int floor = floorForY(playerPos.m_123342_());

        // While walking on cavern_top surface, rebind to whichever mine shaft entrance the player is over.
        if (floor == 0) {
            BlockPos currentAnchor = resolvePlayerSurfaceAnchor(player);
            long dx = (long) currentAnchor.m_123341_() - playerPos.m_123341_();
            long dz = (long) currentAnchor.m_123343_() - playerPos.m_123343_();
            if (dx * dx + dz * dz > SkullCavernElevatorData.MINE_LINK_RADIUS_SQ || !player.getPersistentData().m_128441_(TAG_ANCHOR_X)) {
                bindSurfaceAnchor(player, level, playerPos);
            }
            syncHud(player, level, false);
            return;
        }

        // 1. Check 10F / 20F / 30F milestone floor discovery & placement.
        if (isMilestoneFloor(floor)) {
            handleMilestoneFloor(player, level, playerPos, floor);
        }

        // 2. Check Copper Dungeon (trials:trials_chambers) discovery & placement.
        boolean inTrial = false;
        StructureStart trialStart = level.m_215010_().m_220488_(playerPos, DimKeys.TRIAL_CHAMBER);
        if (trialStart.m_73603_()) {
            inTrial = true;
            BlockPos center = trialStart.m_73601_().m_162394_();
            handleTrialChamber(player, level, playerPos, floor, center);
        }

        Boolean prevTrial = LAST_TRIAL_STATE.put(player.m_20148_(), inTrial);
        if (player.f_19797_ % 20 == 0 || prevTrial == null || prevTrial != inTrial) {
            syncHud(player, level, inTrial);
        }
    }

    private static void handleMilestoneFloor(ServerPlayer player, ServerLevel cavern, BlockPos playerPos, int floor) {
        SkullCavernElevatorData data = SkullCavernElevatorData.get(cavern);
        String stopKey = floorStopKey(floor);
        SkullCavernElevatorData.MineShaft existingMine = data.getById(player.getPersistentData().m_128461_(TAG_ACTIVE_MINE));
        if (existingMine == null) {
            BlockPos anchor = resolvePlayerSurfaceAnchor(player);
            existingMine = data.findNearestBySurface(anchor.m_123341_(), anchor.m_123343_());
        }

        SkullCavernElevatorData.ElevatorStop existingStop = existingMine == null ? null : existingMine.findStop(stopKey);
        Set<String> seen = SEEN_TOASTS_BY_PLAYER.computeIfAbsent(player.m_20148_(), k -> new HashSet<>());

        if (existingStop == null) {
            StationPlacement placed = findUndergroundPlacementForPlayer(cavern, player, playerPos);
            if (placed == null) return;
            SkullCavernElevatorData.MineShaft mine = resolveOrCreateActiveMine(player, cavern);
            boolean surfaceOpened = ensureSurfaceElevatorBuilt(cavern, data, mine);
            placeStationBlocks(cavern, placed.elevatorPos());
            String pioneer = player.m_7755_().getString();
            SkullCavernElevatorData.ElevatorStop created = new SkullCavernElevatorData.ElevatorStop(
                    stopKey, SkullCavernElevatorData.KIND_FLOOR, floor, placed.elevatorPos(), placed.landingPos(), pioneer);
            data.addOrUpdateStop(mine, created);
            seen.add(stopKey);

            playDiscoveryEffects(cavern, placed.elevatorPos(), false);
            sendToast(player, SkullCavernElevatorData.KIND_FLOOR, floor, pioneer, true, surfaceOpened);
            if (surfaceOpened) {
                player.m_5661_(Component.m_264642_(
                        "better_sunlit.elevator.surface_built",
                        "An elevator station has been installed at this mine's surface entrance!").m_130940_(ChatFormatting.GOLD), true);
            }
            syncHud(player, cavern, Boolean.TRUE.equals(LAST_TRIAL_STATE.get(player.m_20148_())));
        } else {
            if (isShaftOrBlockedStation(cavern, existingStop.elevatorPos, existingStop.landingPos)) {
                StationPlacement relocated = findUndergroundPlacementForPlayer(cavern, player, playerPos);
                if (relocated != null) {
                    relocateStop(cavern, data, existingMine, existingStop, relocated);
                    player.m_5661_(Component.m_264642_(
                            "better_sunlit.elevator.relocated",
                            "Relocated elevator out of the narrow shaft onto open ground.").m_130940_(ChatFormatting.GOLD), true);
                    syncHud(player, cavern, Boolean.TRUE.equals(LAST_TRIAL_STATE.get(player.m_20148_())));
                }
            } else {
                ensureStationBlocksIntact(cavern, existingStop.elevatorPos);
            }
            if (seen.add(stopKey)) {
                sendToast(player, SkullCavernElevatorData.KIND_FLOOR, floor, existingStop.pioneer, false, false);
            }
        }
    }

    private static void handleTrialChamber(ServerPlayer player, ServerLevel cavern, BlockPos playerPos, int floor, BlockPos center) {
        SkullCavernElevatorData data = SkullCavernElevatorData.get(cavern);
        String stopKey = trialStopKey(center);
        SkullCavernElevatorData.MineShaft existingMine = data.getById(player.getPersistentData().m_128461_(TAG_ACTIVE_MINE));
        if (existingMine == null) {
            BlockPos anchor = resolvePlayerSurfaceAnchor(player);
            existingMine = data.findNearestBySurface(anchor.m_123341_(), anchor.m_123343_());
        }
        SkullCavernElevatorData.ElevatorStop existingStop = existingMine == null ? null : existingMine.findStop(stopKey);
        Set<String> seen = SEEN_TOASTS_BY_PLAYER.computeIfAbsent(player.m_20148_(), k -> new HashSet<>());

        if (existingStop == null) {
            StationPlacement placed = findUndergroundPlacementForPlayer(cavern, player, playerPos);
            if (placed == null) return;
            SkullCavernElevatorData.MineShaft mine = resolveOrCreateActiveMine(player, cavern);
            boolean surfaceOpened = ensureSurfaceElevatorBuilt(cavern, data, mine);
            placeStationBlocks(cavern, placed.elevatorPos());
            String pioneer = player.m_7755_().getString();
            SkullCavernElevatorData.ElevatorStop created = new SkullCavernElevatorData.ElevatorStop(
                    stopKey, SkullCavernElevatorData.KIND_TRIAL_CHAMBER, floor, placed.elevatorPos(), placed.landingPos(), pioneer);
            data.addOrUpdateStop(mine, created);
            seen.add(stopKey);

            playDiscoveryEffects(cavern, placed.elevatorPos(), true);
            sendToast(player, SkullCavernElevatorData.KIND_TRIAL_CHAMBER, floor, pioneer, true, surfaceOpened);
            if (surfaceOpened) {
                player.m_5661_(Component.m_264642_(
                        "better_sunlit.elevator.surface_built",
                        "An elevator station has been installed at this mine's surface entrance!").m_130940_(ChatFormatting.GOLD), true);
            }
            syncHud(player, cavern, true);
        } else {
            if (isShaftOrBlockedStation(cavern, existingStop.elevatorPos, existingStop.landingPos)) {
                StationPlacement relocated = findUndergroundPlacementForPlayer(cavern, player, playerPos);
                if (relocated != null) {
                    relocateStop(cavern, data, existingMine, existingStop, relocated);
                    player.m_5661_(Component.m_264642_(
                            "better_sunlit.elevator.relocated",
                            "Relocated elevator out of the narrow shaft onto open ground.").m_130940_(ChatFormatting.GOLD), true);
                    syncHud(player, cavern, true);
                }
            } else {
                ensureStationBlocksIntact(cavern, existingStop.elevatorPos);
            }
            if (seen.add(stopKey)) {
                sendToast(player, SkullCavernElevatorData.KIND_TRIAL_CHAMBER, existingStop.floor, existingStop.pioneer, false, false);
            }
        }
    }

    private static boolean ensureSurfaceElevatorBuilt(ServerLevel cavern, SkullCavernElevatorData data,
                                                      SkullCavernElevatorData.MineShaft mine) {
        if (mine.surfaceElevatorPos != null) {
            ensureStationBlocksIntact(cavern, mine.surfaceElevatorPos);
            return false;
        }
        int ax = mine.surfaceAnchor.m_123341_();
        int az = mine.surfaceAnchor.m_123343_();
        // Pick a sturdy spot 2 blocks beside the surface return teleporter at (ax, 511, az).
        int[][] offsets = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, 2}, {2, -2}, {-2, -2}};
        BlockPos chosenElev = null;
        BlockPos chosenLand = new BlockPos(ax, SURFACE_Y, az);
        for (int[] off : offsets) {
            int x = ax + off[0];
            int z = az + off[1];
            for (int y = SURFACE_Y; y >= TOP_CEILING_Y; y--) {
                BlockPos ground = new BlockPos(x, y - 1, z);
                BlockPos spot = new BlockPos(x, y, z);
                if (isSafeGround(cavern, ground, cavern.m_8055_(ground))
                        && isClearForStation(cavern, spot)
                        && isClearForStation(cavern, spot.m_7494_())
                        && !hasRopeInColumn(cavern, spot)) {
                    chosenElev = spot;
                    // Land 1 block between the elevator pillar and the surface teleporter.
                    int lx = ax + Integer.signum(off[0]);
                    int lz = az + Integer.signum(off[1]);
                    chosenLand = new BlockPos(lx, y, lz);
                    break;
                }
            }
            if (chosenElev != null) break;
        }
        if (chosenElev == null) {
            chosenElev = new BlockPos(ax + 2, SURFACE_Y, az);
            BlockPos below = chosenElev.m_7495_();
            if (cavern.m_8055_(below).m_60795_()) {
                cavern.m_46597_(below, Blocks.f_50222_.m_49966_());
            }
            chosenLand = new BlockPos(ax + 1, SURFACE_Y, az);
        }
        placeStationBlocks(cavern, chosenElev);
        data.setSurfaceStation(mine, chosenElev, chosenLand);
        return true;
    }

    private record StationPlacement(BlockPos elevatorPos, BlockPos landingPos) {}

    /**
     * Only allows discovering/placing an underground elevator once the player is standing on
     * natural solid ground (not mid-air, not climbing a rope/ladder, not inside a 1x1 shaft)
     * with an open 2-block-tall floor tile beside them that does not block any tunnel or rope.
     */
    private static StationPlacement findUndergroundPlacementForPlayer(ServerLevel cavern, ServerPlayer player, BlockPos playerPos) {
        if (!player.m_20096_() || player.m_6147_()) return null;
        if (!isValidStandingSpot(cavern, playerPos)) return null;
        BlockPos elevPos = findOpenStationSpot(cavern, playerPos);
        return elevPos == null ? null : new StationPlacement(elevPos, playerPos.m_7949_());
    }

    private static StationPlacement findOpenStationNear(ServerLevel cavern, BlockPos origin, int expectedFloor, int maxRadius) {
        int ox = origin.m_123341_();
        int oy = origin.m_123342_();
        int oz = origin.m_123343_();
        for (int r = 1; r <= maxRadius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = 0; dy >= -6; dy--) {
                        BlockPos cand = new BlockPos(ox + dx, oy + dy, oz + dz);
                        if (floorForY(cand.m_123342_()) != expectedFloor) continue;
                        if (!isValidStandingSpot(cavern, cand)) continue;
                        BlockPos elev = findOpenStationSpot(cavern, cand);
                        if (elev != null) {
                            return new StationPlacement(elev, cand);
                        }
                    }
                    for (int dy = 1; dy <= 4; dy++) {
                        BlockPos cand = new BlockPos(ox + dx, oy + dy, oz + dz);
                        if (floorForY(cand.m_123342_()) != expectedFloor) continue;
                        if (!isValidStandingSpot(cavern, cand)) continue;
                        BlockPos elev = findOpenStationSpot(cavern, cand);
                        if (elev != null) {
                            return new StationPlacement(elev, cand);
                        }
                    }
                }
            }
        }
        return null;
    }

    private static boolean isValidStandingSpot(ServerLevel cavern, BlockPos landing) {
        BlockPos below = landing.m_7495_();
        if (!isSafeGround(cavern, below, cavern.m_8055_(below))) return false;
        if (!isClearForStation(cavern, landing) || !isClearForStation(cavern, landing.m_7494_())) return false;
        if (hasRopeInColumn(cavern, landing)) return false;
        return countOpenHorizontalNeighbors(cavern, landing, null) >= 2;
    }

    private static BlockPos findOpenStationSpot(ServerLevel cavern, BlockPos landing) {
        int px = landing.m_123341_();
        int py = landing.m_123342_();
        int pz = landing.m_123343_();
        int[][] offsets = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}};
        int[] dyOrder = {0, -1, 1};
        for (int[] off : offsets) {
            int x = px + off[0];
            int z = pz + off[1];
            for (int dy : dyOrder) {
                BlockPos spot = new BlockPos(x, py + dy, z);
                BlockPos ground = spot.m_7495_();
                if (!isSafeGround(cavern, ground, cavern.m_8055_(ground))) continue;
                if (!isClearForStation(cavern, spot) || !isClearForStation(cavern, spot.m_7494_())) continue;
                if (hasRopeInColumn(cavern, spot)) continue;
                // Keep at least two open horizontal exits around the landing tile (or around the pillar)
                // so the elevator never plugs a 1-block tunnel or vertical shaft.
                if (countOpenHorizontalNeighbors(cavern, landing, spot) < 2
                        && countOpenHorizontalNeighbors(cavern, spot, landing) < 2) {
                    continue;
                }
                return spot;
            }
        }
        return null;
    }

    private static boolean isShaftOrBlockedStation(ServerLevel cavern, BlockPos elevPos, BlockPos landingPos) {
        if (!cavern.m_46749_(elevPos) || !cavern.m_46749_(landingPos)) return false;
        Block lanternBlock = ForgeRegistries.BLOCKS.getValue(LANTERN_BLOCK_ID);
        BlockState aboveElev = cavern.m_8055_(elevPos.m_7494_());
        boolean missingHeadroom = (lanternBlock == null || !aboveElev.m_60713_(lanternBlock))
                && !isClearForStation(cavern, elevPos.m_7494_());
        if (missingHeadroom) return true;
        if (hasRopeInColumn(cavern, landingPos) || hasRopeInColumn(cavern, elevPos)) return true;
        return countOpenHorizontalNeighbors(cavern, landingPos, elevPos) == 0;
    }

    private static void relocateStop(ServerLevel cavern, SkullCavernElevatorData data,
                                     SkullCavernElevatorData.MineShaft mine,
                                     SkullCavernElevatorData.ElevatorStop oldStop,
                                     StationPlacement placed) {
        clearOldStationBlocks(cavern, oldStop.elevatorPos, oldStop.landingPos);
        placeStationBlocks(cavern, placed.elevatorPos());
        SkullCavernElevatorData.ElevatorStop updated = new SkullCavernElevatorData.ElevatorStop(
                oldStop.key, oldStop.kind, oldStop.floor, placed.elevatorPos(), placed.landingPos(), oldStop.pioneer);
        data.addOrUpdateStop(mine, updated);
        playDiscoveryEffects(cavern, placed.elevatorPos(), oldStop.kind == SkullCavernElevatorData.KIND_TRIAL_CHAMBER);
    }

    private static void clearOldStationBlocks(ServerLevel cavern, BlockPos oldElevPos, BlockPos oldLandingPos) {
        if (cavern.m_46749_(oldElevPos)) {
            Block elevBlock = ForgeRegistries.BLOCKS.getValue(ELEVATOR_BLOCK_ID);
            Block lanternBlock = ForgeRegistries.BLOCKS.getValue(LANTERN_BLOCK_ID);
            if (elevBlock != null && cavern.m_8055_(oldElevPos).m_60713_(elevBlock)) {
                cavern.m_46597_(oldElevPos, Blocks.f_50016_.m_49966_());
            }
            BlockPos above = oldElevPos.m_7494_();
            if (lanternBlock != null && cavern.m_8055_(above).m_60713_(lanternBlock)) {
                cavern.m_46597_(above, Blocks.f_50016_.m_49966_());
            }
        }
        // If an earlier buggy landing plugged a vertical rope shaft with skull_stone directly below a rope, unplug it.
        if (oldLandingPos != null && cavern.m_46749_(oldLandingPos)) {
            BlockPos below = oldLandingPos.m_7495_();
            Block skullStone = ForgeRegistries.BLOCKS.getValue(SKULL_STONE_ID);
            boolean ropeAbove = isClimbableOrRope(cavern.m_8055_(oldLandingPos))
                    || isClimbableOrRope(cavern.m_8055_(oldLandingPos.m_7494_()));
            boolean pluggedWithSkullStone = skullStone != null && cavern.m_8055_(below).m_60713_(skullStone);
            boolean voidOrRopeBeneath = cavern.m_8055_(below.m_7495_()).m_60795_()
                    || isClimbableOrRope(cavern.m_8055_(below.m_7495_()));
            if (ropeAbove && pluggedWithSkullStone && voidOrRopeBeneath) {
                cavern.m_46597_(below, Blocks.f_50016_.m_49966_());
            }
        }
    }

    private static void placeStationBlocks(ServerLevel cavern, BlockPos elevPos) {
        Block elevBlock = ForgeRegistries.BLOCKS.getValue(ELEVATOR_BLOCK_ID);
        Block lanternBlock = ForgeRegistries.BLOCKS.getValue(LANTERN_BLOCK_ID);
        if (elevBlock != null && elevBlock != Blocks.f_50016_) {
            cavern.m_46597_(elevPos, elevBlock.m_49966_());
        }
        BlockPos topPos = elevPos.m_7494_();
        if (lanternBlock != null && lanternBlock != Blocks.f_50016_ && isClearForStation(cavern, topPos)) {
            cavern.m_46597_(topPos, lanternBlock.m_49966_());
        }
    }

    private static void ensureStationBlocksIntact(ServerLevel cavern, BlockPos elevPos) {
        if (!cavern.m_46749_(elevPos)) return;
        Block elevBlock = ForgeRegistries.BLOCKS.getValue(ELEVATOR_BLOCK_ID);
        if (elevBlock != null && !cavern.m_8055_(elevPos).m_60713_(elevBlock)) {
            placeStationBlocks(cavern, elevPos);
        }
    }

    private static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        Level level = event.getLevel();
        if (level.f_46443_ || !isSkullCavern(level)) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;

        ServerLevel cavern = (ServerLevel) level;
        SkullCavernElevatorData data = SkullCavernElevatorData.get(cavern);
        SkullCavernElevatorData.StationLookup lookup = data.lookupStation(event.getPos());
        if (lookup == null) return;

        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);

        if (player.f_8906_ == null || !NetChannel.CHANNEL.isRemotePresent(player.f_8906_.f_9742_)) {
            player.m_5661_(Component.m_264642_(
                    "better_sunlit.elevator.client_required",
                    "Install Better Sunlit on your client to use the Skull Cavern Elevator UI.").m_130940_(ChatFormatting.YELLOW), true);
            return;
        }

        // Bind player's active mine to the clicked station's mine so subsequent exploration stays linked.
        SkullCavernElevatorData.MineShaft mine = lookup.mine();
        player.getPersistentData().m_128359_(TAG_ACTIVE_MINE, mine.id);
        player.getPersistentData().m_128405_(TAG_ANCHOR_X, mine.surfaceAnchor.m_123341_());
        player.getPersistentData().m_128405_(TAG_ANCHOR_Z, mine.surfaceAnchor.m_123343_());

        List<StopEntry> entries = new ArrayList<>();
        entries.add(new StopEntry(
                SkullCavernElevatorData.SURFACE_KEY,
                SkullCavernElevatorData.KIND_SURFACE,
                0,
                "",
                SkullCavernElevatorData.SURFACE_KEY.equals(lookup.stopKey())));
        for (SkullCavernElevatorData.ElevatorStop stop : mine.sortedStops()) {
            entries.add(new StopEntry(
                    stop.key,
                    stop.kind,
                    stop.floor,
                    stop.pioneer,
                    stop.key.equals(lookup.stopKey())));
        }

        NetChannel.CHANNEL.sendTo(
                new OpenPacket(lookup.elevatorPos(), lookup.stopKey(), entries),
                player.f_8906_.f_9742_, NetworkDirection.PLAY_TO_CLIENT);
    }

    private static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Level level = event.getLevel();
        if (level.f_46443_ || !isSkullCavern(level)) return;
        if (SkullCavernElevatorData.get((ServerLevel) level).isProtectedBlock(event.getPos())) {
            event.setCanceled(true);
        }
    }

    private static void onBreakBlock(BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isSkullCavern(level)) return;
        if (SkullCavernElevatorData.get(level).isProtectedBlock(event.getPos())) {
            event.setCanceled(true);
        }
    }

    private static void onExplosionDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !isSkullCavern(level)) return;
        SkullCavernElevatorData data = SkullCavernElevatorData.get(level);
        event.getAffectedBlocks().removeIf(data::isProtectedBlock);
    }

    private static void handleUseElevator(ServerPlayer player, BlockPos clickedElevatorPos, String targetStopKey) {
        ServerLevel cavern = player.m_284548_();
        if (!isSkullCavern(cavern)) return;
        if (player.m_20275_(clickedElevatorPos.m_123341_() + 0.5D, clickedElevatorPos.m_123342_() + 0.5D, clickedElevatorPos.m_123343_() + 0.5D) > MAX_INTERACT_DIST_SQ) {
            return;
        }
        SkullCavernElevatorData data = SkullCavernElevatorData.get(cavern);
        SkullCavernElevatorData.StationLookup lookup = data.lookupStation(clickedElevatorPos);
        if (lookup == null) return;

        SkullCavernElevatorData.MineShaft mine = lookup.mine();
        BlockPos destElev;
        BlockPos destLand;
        Component arrivedMsg;
        if (SkullCavernElevatorData.SURFACE_KEY.equals(targetStopKey)) {
            if (mine.surfaceElevatorPos == null) return;
            destElev = mine.surfaceElevatorPos;
            destLand = mine.surfaceLandingPos != null ? mine.surfaceLandingPos : mine.surfaceAnchor;
            arrivedMsg = Component.m_264642_(
                    "better_sunlit.elevator.arrived_surface",
                    "Arrived at Skull Cavern entrance.").m_130940_(ChatFormatting.GOLD);
        } else {
            SkullCavernElevatorData.ElevatorStop stop = mine.findStop(targetStopKey);
            if (stop == null) return;
            destElev = stop.elevatorPos;
            destLand = stop.landingPos;
            // Auto-rescue any legacy station that was saved inside a 1x1 rope shaft before landing.
            if (isShaftOrBlockedStation(cavern, destElev, destLand)) {
                StationPlacement rescued = findOpenStationNear(cavern, destLand, stop.floor, 8);
                if (rescued != null) {
                    relocateStop(cavern, data, mine, stop, rescued);
                    destElev = rescued.elevatorPos();
                    destLand = rescued.landingPos();
                }
            }
            arrivedMsg = stop.kind == SkullCavernElevatorData.KIND_TRIAL_CHAMBER
                    ? Component.m_264642_(
                    "better_sunlit.elevator.arrived_trial",
                    "Arrived at Copper Dungeon (%sF).", stop.floor).m_130940_(ChatFormatting.AQUA)
                    : Component.m_264642_(
                    "better_sunlit.elevator.arrived_floor",
                    "Skull Cavern Elevator: arrived at %sF.", stop.floor).m_130940_(ChatFormatting.GOLD);
        }

        if (!isShaftOrBlockedStation(cavern, destElev, destLand)) {
            ensureStationBlocksIntact(cavern, destElev);
        }
        ensureLandingClear(cavern, destLand);

        // Face toward the elevator pillar upon arrival for clear orientation.
        double dx = (destElev.m_123341_() + 0.5D) - (destLand.m_123341_() + 0.5D);
        double dz = (destElev.m_123343_() + 0.5D) - (destLand.m_123343_() + 0.5D);
        float yaw = (dx * dx + dz * dz) > 0.01D ? (float) Math.toDegrees(Math.atan2(-dx, dz)) : player.m_146908_();

        player.m_183634_();
        player.m_8999_(cavern, destLand.m_123341_() + 0.5D, destLand.m_123342_(), destLand.m_123343_() + 0.5D, yaw, 8.0F);
        playSound(cavern, destLand, "minecraft:entity.enderman.teleport", 0.65F, 1.15F);
        playSound(cavern, destLand, "minecraft:block.amethyst_block.chime", 0.85F, 1.05F);
        player.m_5661_(arrivedMsg, true);
        syncHud(player, cavern, SkullCavernElevatorData.SURFACE_KEY.equals(targetStopKey)
                ? false
                : (mine.findStop(targetStopKey) != null
                && mine.findStop(targetStopKey).kind == SkullCavernElevatorData.KIND_TRIAL_CHAMBER));
    }

    private static void ensureLandingClear(ServerLevel cavern, BlockPos landing) {
        BlockPos ground = landing.m_7495_();
        BlockState groundState = cavern.m_8055_(ground);
        // Never plug a vertical rope shaft with skull_stone underneath a climbable/rope column.
        if (!hasRopeInColumn(cavern, landing)
                && !isSafeGround(cavern, ground, groundState)
                && !groundState.m_60713_(Blocks.f_50075_)) {
            Block stone = ForgeRegistries.BLOCKS.getValue(SKULL_STONE_ID);
            cavern.m_46597_(ground, (stone == null || stone == Blocks.f_50016_ ? Blocks.f_50069_ : stone).m_49966_());
        }
        for (int dy = 0; dy <= 1; dy++) {
            BlockPos p = landing.m_6630_(dy);
            BlockState s = cavern.m_8055_(p);
            if (!isSafeAir(cavern, p, s) && !s.m_60713_(Blocks.f_50075_) && !isClimbableOrFeature(s)) {
                cavern.m_46597_(p, Blocks.f_50016_.m_49966_());
            }
        }
    }

    private static void playDiscoveryEffects(ServerLevel cavern, BlockPos elevPos, boolean isTrial) {
        playSound(cavern, elevPos, isTrial ? "minecraft:block.copper.place" : "minecraft:entity.player.levelup", 0.85F, 1.15F);
        playSound(cavern, elevPos, "minecraft:block.amethyst_block.chime", 1.0F, 1.0F);
        cavern.m_8767_(ParticleTypes.f_123747_,
                elevPos.m_123341_() + 0.5D, elevPos.m_123342_() + 1.1D, elevPos.m_123343_() + 0.5D,
                16, 0.45D, 0.45D, 0.45D, 0.04D);
    }

    private static void sendToast(ServerPlayer player, byte kind, int floor, String pioneer,
                                  boolean firstDiscovery, boolean surfaceOpened) {
        if (player.f_8906_ == null || !NetChannel.CHANNEL.isRemotePresent(player.f_8906_.f_9742_)) {
            // Fallback message for clients without Better Sunlit.
            if (firstDiscovery) {
                Component msg = kind == SkullCavernElevatorData.KIND_TRIAL_CHAMBER
                        ? Component.m_264642_("better_sunlit.elevator.toast_trial_title", "Copper Dungeon Discovered!")
                        : Component.m_264642_("better_sunlit.elevator.toast_floor_title", "Skull Cavern %sF", floor);
                player.m_5661_(msg.m_6881_().m_130940_(ChatFormatting.GOLD), true);
            }
            return;
        }
        NetChannel.CHANNEL.sendTo(new ToastPacket(kind, floor, pioneer, firstDiscovery, surfaceOpened),
                player.f_8906_.f_9742_, NetworkDirection.PLAY_TO_CLIENT);
    }

    private static void syncHud(ServerPlayer player, ServerLevel cavern, boolean inTrialChamber) {
        if (player.f_8906_ == null || !NetChannel.CHANNEL.isRemotePresent(player.f_8906_.f_9742_)) return;
        SkullCavernElevatorData data = SkullCavernElevatorData.get(cavern);
        List<SkullCavernElevatorData.StationLookup> nearby = data.findNearbyStations(player.m_20183_(), NEARBY_MARKER_RADIUS);
        List<MarkerEntry> markers = new ArrayList<>(nearby.size());
        for (SkullCavernElevatorData.StationLookup st : nearby) {
            if (SkullCavernElevatorData.SURFACE_KEY.equals(st.stopKey())) {
                markers.add(new MarkerEntry(st.elevatorPos(), SkullCavernElevatorData.KIND_SURFACE, 0));
            } else {
                SkullCavernElevatorData.ElevatorStop stop = st.mine().findStop(st.stopKey());
                if (stop != null) {
                    markers.add(new MarkerEntry(st.elevatorPos(), stop.kind, stop.floor));
                }
            }
        }
        NetChannel.CHANNEL.sendTo(new HudPacket(inTrialChamber, markers),
                player.f_8906_.f_9742_, NetworkDirection.PLAY_TO_CLIENT);
    }

    private static void syncHudEmpty(ServerPlayer player) {
        if (player.f_8906_ == null || !NetChannel.CHANNEL.isRemotePresent(player.f_8906_.f_9742_)) return;
        NetChannel.CHANNEL.sendTo(new HudPacket(false, List.of()),
                player.f_8906_.f_9742_, NetworkDirection.PLAY_TO_CLIENT);
    }

    private static boolean isSafeGround(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.m_60795_() || !state.m_60819_().m_76178_() || isHazard(state) || isClimbableOrFeature(state)) return false;
        return state.m_60659_(level, pos, Direction.UP, SupportType.FULL);
    }

    private static boolean isSafeAir(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.m_60819_().m_76178_() || isHazard(state)) return false;
        return state.m_60812_(level, pos).m_83281_();
    }

    private static boolean isClearForStation(ServerLevel level, BlockPos pos) {
        BlockState state = level.m_8055_(pos);
        if (!state.m_60819_().m_76178_() || isHazard(state) || isClimbableOrFeature(state)) return false;
        return state.m_60795_() || state.m_247087_();
    }

    private static boolean isClimbableOrRope(BlockState state) {
        if (state.m_60795_()) return false;
        if (state.m_204336_(BlockTags.f_13082_)) return true;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        if (id != null) {
            String path = id.m_135815_();
            if (path.contains("rope") || path.contains("ladder")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isClimbableOrFeature(BlockState state) {
        if (state.m_60795_()) return false;
        if (isClimbableOrRope(state)) return true;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        if (id != null) {
            String path = id.m_135815_();
            if (path.contains("teleporter") || path.contains("spawner") || path.contains("vault")
                    || path.contains("chest") || path.contains("crate") || path.contains("lodestone")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasRopeInColumn(ServerLevel level, BlockPos pos) {
        for (int dy = -2; dy <= 2; dy++) {
            if (isClimbableOrRope(level.m_8055_(pos.m_6630_(dy)))) {
                return true;
            }
        }
        return false;
    }

    private static int countOpenHorizontalNeighbors(ServerLevel level, BlockPos center, BlockPos exclude) {
        int open = 0;
        Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction dir : dirs) {
            BlockPos n = center.m_121945_(dir);
            if (exclude != null && n.equals(exclude)) continue;
            if (isClearForStation(level, n) && isClearForStation(level, n.m_7494_())) {
                open++;
            }
        }
        return open;
    }

    private static boolean isHazard(BlockState state) {
        return state.m_60713_(Blocks.f_49991_)
                || state.m_60713_(Blocks.f_50455_)
                || state.m_60713_(Blocks.f_50083_)
                || state.m_60713_(Blocks.f_50084_)
                || state.m_60713_(Blocks.f_50128_)
                || state.m_60713_(Blocks.f_152499_);
    }

    private static void playSound(ServerLevel level, BlockPos pos, String soundId, float volume, float pitch) {
        SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(soundId));
        if (sound != null) {
            level.m_5594_(null, pos, sound, SoundSource.BLOCKS, volume, pitch);
        }
    }

    public record StopEntry(String key, byte kind, int floor, String pioneer, boolean isCurrent) {}

    public record MarkerEntry(BlockPos pos, byte kind, int floor) {}

    public record OpenPacket(BlockPos clickedElevatorPos, String currentStopKey, List<StopEntry> stops) {
        public static void encode(OpenPacket packet, FriendlyByteBuf out) {
            out.m_130064_(packet.clickedElevatorPos);
            out.m_130070_(packet.currentStopKey);
            out.writeByte(packet.stops.size());
            for (StopEntry entry : packet.stops) {
                out.m_130070_(entry.key);
                out.writeByte(entry.kind);
                out.writeByte(entry.floor);
                out.m_130070_(entry.pioneer);
                out.writeBoolean(entry.isCurrent);
            }
        }

        public static OpenPacket decode(FriendlyByteBuf in) {
            BlockPos pos = in.m_130135_();
            String currentKey = in.m_130136_(64);
            int count = in.readUnsignedByte();
            List<StopEntry> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                list.add(new StopEntry(in.m_130136_(64), in.readByte(), in.readUnsignedByte(),
                        in.m_130136_(64), in.readBoolean()));
            }
            return new OpenPacket(pos, currentKey, list);
        }

        public static void handle(OpenPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context ctx = supplier.get();
            ctx.enqueueWork(() -> {
                Consumer<OpenPacket> receiver = clientOpenReceiver;
                if (receiver != null) receiver.accept(packet);
            });
            ctx.setPacketHandled(true);
        }
    }

    public record ToastPacket(byte kind, int floor, String pioneer, boolean firstDiscovery, boolean surfaceOpened) {
        public static void encode(ToastPacket packet, FriendlyByteBuf out) {
            out.writeByte(packet.kind);
            out.writeByte(packet.floor);
            out.m_130070_(packet.pioneer);
            out.writeBoolean(packet.firstDiscovery);
            out.writeBoolean(packet.surfaceOpened);
        }

        public static ToastPacket decode(FriendlyByteBuf in) {
            return new ToastPacket(in.readByte(), in.readUnsignedByte(), in.m_130136_(64),
                    in.readBoolean(), in.readBoolean());
        }

        public static void handle(ToastPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context ctx = supplier.get();
            ctx.enqueueWork(() -> {
                Consumer<ToastPacket> receiver = clientToastReceiver;
                if (receiver != null) receiver.accept(packet);
            });
            ctx.setPacketHandled(true);
        }
    }

    public record HudPacket(boolean inTrialChamber, List<MarkerEntry> markers) {
        public static void encode(HudPacket packet, FriendlyByteBuf out) {
            out.writeBoolean(packet.inTrialChamber);
            out.writeByte(Math.min(32, packet.markers.size()));
            int limit = Math.min(32, packet.markers.size());
            for (int i = 0; i < limit; i++) {
                MarkerEntry m = packet.markers.get(i);
                out.m_130064_(m.pos);
                out.writeByte(m.kind);
                out.writeByte(m.floor);
            }
        }

        public static HudPacket decode(FriendlyByteBuf in) {
            boolean inTrial = in.readBoolean();
            int count = in.readUnsignedByte();
            List<MarkerEntry> list = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                list.add(new MarkerEntry(in.m_130135_(), in.readByte(), in.readUnsignedByte()));
            }
            return new HudPacket(inTrial, list);
        }

        public static void handle(HudPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context ctx = supplier.get();
            ctx.enqueueWork(() -> {
                Consumer<HudPacket> receiver = clientHudReceiver;
                if (receiver != null) receiver.accept(packet);
            });
            ctx.setPacketHandled(true);
        }
    }

    public record UseElevatorPacket(BlockPos clickedElevatorPos, String targetStopKey) {
        public static void encode(UseElevatorPacket packet, FriendlyByteBuf out) {
            out.m_130064_(packet.clickedElevatorPos);
            out.m_130070_(packet.targetStopKey);
        }

        public static UseElevatorPacket decode(FriendlyByteBuf in) {
            return new UseElevatorPacket(in.m_130135_(), in.m_130136_(64));
        }

        public static void handle(UseElevatorPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context ctx = supplier.get();
            ServerPlayer sender = ctx.getSender();
            if (sender != null) {
                ctx.enqueueWork(() -> handleUseElevator(sender, packet.clickedElevatorPos, packet.targetStopKey));
            }
            ctx.setPacketHandled(true);
        }
    }
}
