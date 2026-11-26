package dev.sunlit.fishvisuals.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.math.Axis;
import dev.sunlit.fishvisuals.CatchModelIds;
import dev.sunlit.fishvisuals.FishPondSync;
import dev.sunlit.fishvisuals.SunlitFishVisuals;
import dev.sunlit.fishvisuals.mixin.EntityWaterStateAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Client-only swimming fish visuals rendered inside the 3x4 water basin behind society:fish_pond blocks.
 * Never spawns world entities; reuses one proxy entity per species and renders in AFTER_ENTITIES
 * so water surface shading and refraction naturally draw over the submerged fish.
 */
@Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, value = Dist.CLIENT)
public final class FishPondVisuals {
    public enum Mode {
        ADAPTIVE, // Free swimming + automatic scale adjustment for higher populations / large species
        SWIM,     // Free swimming at base scale
        OFF
    }

    public static final float DEFAULT_ADAPTIVE_SCALE = 0.70F;
    public static final float MIN_ADAPTIVE_SCALE = 0.30F;
    public static final float MAX_ADAPTIVE_SCALE = 1.20F;
    static final int MAX_ANIM_TICKS = 120_000;

    public record Settings(Mode mode, float adaptiveScale) {
        public static final Settings DEFAULT = new Settings(Mode.ADAPTIVE, DEFAULT_ADAPTIVE_SCALE);
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double MAX_RENDER_DIST_SQ = 34.0D * 34.0D;
    private static final int MAX_VISIBLE_FISH = 10;

    private static final Map<BlockPos, PondVisual> PONDS = new HashMap<>();
    private static int nextProxyId = -200_000;
    private static ClientLevel currentLevel;
    private static volatile Mode mode = Mode.ADAPTIVE;
    private static volatile float adaptiveScale = DEFAULT_ADAPTIVE_SCALE;

    private FishPondVisuals() {}

    @Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            loadSettings();
            FishPondSync.setClientReceiver(FishPondVisuals::receive);
        }
    }

    public static Mode getMode() {
        return mode;
    }

    public static float getAdaptiveScale() {
        return adaptiveScale;
    }

    public static void setMode(Mode next) {
        if (next != null) {
            mode = next;
        }
    }

    public static void setAdaptiveScale(float scale) {
        adaptiveScale = clampAdaptiveScale(scale);
    }

    public static float clampAdaptiveScale(float scale) {
        if (!Float.isFinite(scale)) return DEFAULT_ADAPTIVE_SCALE;
        return Math.max(MIN_ADAPTIVE_SCALE, Math.min(MAX_ADAPTIVE_SCALE, Math.round(scale * 100.0F) / 100.0F));
    }

    public static void setModeByName(String name) {
        Mode parsed = parseMode(name);
        if (parsed != null) mode = parsed;
    }

    private static Mode parseMode(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "adaptive" -> Mode.ADAPTIVE;
            case "swim" -> Mode.SWIM;
            case "off" -> Mode.OFF;
            default -> null;
        };
    }

    public static int advanceAnimTicks(int current) {
        int next = current + 1;
        return next >= MAX_ANIM_TICKS || next < 0 ? 0 : next;
    }

    private static Path settingsFile() {
        return FMLPaths.CONFIGDIR.get().resolve("sunlit-fish-pond-client.json");
    }

    public static Settings readSettings(String text) {
        JsonObject json = GSON.fromJson(text, JsonObject.class);
        if (json == null) return Settings.DEFAULT;
        Mode parsedMode = json.has("mode") && json.get("mode").isJsonPrimitive()
                ? parseMode(json.get("mode").getAsString()) : null;
        float parsedScale = DEFAULT_ADAPTIVE_SCALE;
        if (json.has("adaptiveScale") && json.get("adaptiveScale").isJsonPrimitive()
                && json.get("adaptiveScale").getAsJsonPrimitive().isNumber()) {
            parsedScale = clampAdaptiveScale(json.get("adaptiveScale").getAsFloat());
        }
        return new Settings(parsedMode == null ? Mode.ADAPTIVE : parsedMode, parsedScale);
    }

    private static void loadSettings() {
        try {
            Path file = settingsFile();
            if (!Files.exists(file)) {
                saveSettings(Settings.DEFAULT);
                mode = Settings.DEFAULT.mode();
                adaptiveScale = Settings.DEFAULT.adaptiveScale();
                return;
            }
            Settings loaded = readSettings(Files.readString(file));
            mode = loaded.mode();
            adaptiveScale = loaded.adaptiveScale();
        } catch (Exception ex) {
            SunlitFishVisuals.LOG.warn("Cannot load fish pond settings; using defaults", ex);
            mode = Settings.DEFAULT.mode();
            adaptiveScale = Settings.DEFAULT.adaptiveScale();
        }
    }

    private static void saveSettings(Settings settings) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("mode", settings.mode().name().toLowerCase(Locale.ROOT));
        json.addProperty("adaptiveScale", settings.adaptiveScale());
        Path file = settingsFile();
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, GSON.toJson(json) + "\n");
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String formatScale(float value) {
        float rounded = Math.round(value * 100.0F) / 100.0F;
        if (Math.abs(rounded * 10.0F - Math.round(rounded * 10.0F)) < 0.001F) {
            return String.format(Locale.ROOT, "%.1f", rounded);
        }
        return String.format(Locale.ROOT, "%.2f", rounded);
    }

    private static Component summaryComponent(Mode activeMode, float scale) {
        String modeName = activeMode.name().toLowerCase(Locale.ROOT);
        if (activeMode == Mode.ADAPTIVE) {
            return Component.m_264642_("better_sunlit.fishpond.summary_adaptive",
                    "Fish pond: %s (min scale %s)", modeName, formatScale(scale));
        }
        return Component.m_264642_("better_sunlit.fishpond.summary",
                "Fish pond: %s", modeName);
    }

    private static int applyCommandSettings(CommandSourceStack source, Mode nextMode, float nextScale) {
        float clamped = clampAdaptiveScale(nextScale);
        Settings next = new Settings(nextMode, clamped);
        try {
            saveSettings(next);
            mode = next.mode();
            adaptiveScale = next.adaptiveScale();
        } catch (IOException ex) {
            source.m_81352_(Component.m_264642_("better_sunlit.fishpond.save_failed",
                    "Failed to save fish pond settings. Keeping previous settings."));
            return 0;
        }
        source.m_288197_(() -> Component.m_264642_("better_sunlit.fishpond.saved",
                "Saved: %s", summaryComponent(mode, adaptiveScale)), false);
        return 1;
    }

    private static void syncLevel() {
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level != currentLevel) {
            PONDS.clear();
            currentLevel = level;
        }
    }

    public static void receive(FishPondSync.PondPacket packet) {
        syncLevel();
        if (currentLevel == null || !currentLevel.m_46472_().m_135782_().equals(packet.dimension())) return;
        for (BlockPos removed : packet.removed()) {
            PONDS.remove(removed);
        }
        for (FishPondSync.PondSnapshot snap : packet.updates()) {
            if (snap.fishId() == null || snap.fishId().isEmpty() || snap.population() <= 0) {
                PONDS.remove(snap.pos());
                continue;
            }
            ResourceLocation rawId = ResourceLocation.m_135820_(snap.fishId());
            ResourceLocation modelId = rawId == null ? null : CatchModelIds.resolve(rawId);
            if (modelId == null) {
                PONDS.remove(snap.pos());
                continue;
            }
            PondVisual pond = PONDS.computeIfAbsent(snap.pos().m_7949_(), PondVisual::new);
            pond.updateSpec(modelId, Math.min(MAX_VISIBLE_FISH, snap.population()), Math.max(1, snap.maxPopulation()));
        }
    }

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.m_82127_("fishpond")
                .executes(ctx -> {
                    ctx.getSource().m_288197_(() -> Component.m_264642_(
                            "better_sunlit.fishpond.usage",
                            "%s | /fishpond <adaptive [scale]|swim|off>",
                            summaryComponent(mode, adaptiveScale)), false);
                    return 1;
                })
                .then(Commands.m_82127_("adaptive")
                        .executes(ctx -> applyCommandSettings(ctx.getSource(), Mode.ADAPTIVE, adaptiveScale))
                        .then(Commands.m_82129_("scale", FloatArgumentType.floatArg(MIN_ADAPTIVE_SCALE, MAX_ADAPTIVE_SCALE))
                                .suggests((ctx, builder) -> {
                                    builder.suggest("0.5");
                                    builder.suggest("0.6");
                                    builder.suggest("0.7");
                                    builder.suggest("0.8");
                                    builder.suggest("0.9");
                                    builder.suggest("1.0");
                                    return builder.buildFuture();
                                })
                                .executes(ctx -> applyCommandSettings(
                                        ctx.getSource(), Mode.ADAPTIVE, FloatArgumentType.getFloat(ctx, "scale")))))
                .then(Commands.m_82127_("swim")
                        .executes(ctx -> applyCommandSettings(ctx.getSource(), Mode.SWIM, adaptiveScale)))
                .then(Commands.m_82127_("off")
                        .executes(ctx -> applyCommandSettings(ctx.getSource(), Mode.OFF, adaptiveScale))));
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.m_91087_();
        if (mc.m_91104_()) return; // paused
        syncLevel();
        if (currentLevel == null || PONDS.isEmpty() || mode == Mode.OFF) return;

        long gameTime = currentLevel.m_46467_();
        var it = PONDS.entrySet().iterator();
        while (it.hasNext()) {
            PondVisual pond = it.next().getValue();
            if (!currentLevel.m_46749_(pond.pos)) continue;
            BlockState state = currentLevel.m_8055_(pond.pos);
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
            if (!FishPondSync.FISH_POND_BLOCK_ID.equals(blockId)) {
                it.remove();
                continue;
            }
            pond.tick(currentLevel, state, gameTime, mode);
        }
    }

    @SubscribeEvent
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        if (mode == Mode.OFF || PONDS.isEmpty()) return;
        Minecraft mc = Minecraft.m_91087_();
        ClientLevel level = mc.f_91073_;
        if (level == null || level != currentLevel) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.m_90583_();
        float partialTick = event.getPartialTick();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.m_91269_().m_110104_();
        boolean drewAny = false;

        for (PondVisual pond : PONDS.values()) {
            if (!pond.active || pond.swimmers.isEmpty() || pond.modelId == null) continue;
            double dx = pond.bounds.centerX - camPos.f_82479_;
            double dy = pond.bounds.centerY - camPos.f_82480_;
            double dz = pond.bounds.centerZ - camPos.f_82481_;
            if (dx * dx + dy * dy + dz * dz > MAX_RENDER_DIST_SQ) continue;

            boolean isCustom = CustomFishModels.has(pond.modelId);
            float baseSize = isCustom ? CustomFishModels.size(pond.modelId) : 0.5F;
            if (!isCustom) {
                LivingEntity sampleProxy = getOrCreateSwimmerProxy(level, pond.modelId, pond.swimmers.get(0));
                if (sampleProxy == null) continue;
                baseSize = Math.max(0.35F, Math.max(sampleProxy.m_20205_(), sampleProxy.m_20206_()));
            }
            float scale = computeScale(mode, pond.swimmers.size(), baseSize);

            for (Swimmer s : pond.swimmers) {
                double rx = Mth.m_14139_(partialTick, s.prevX, s.x);
                double ry = Mth.m_14139_(partialTick, s.prevY, s.y);
                double rz = Mth.m_14139_(partialTick, s.prevZ, s.z);
                float yaw = lerpAngle(partialTick, s.prevYaw, s.yaw);
                float pitch = Mth.m_14179_(partialTick, s.prevPitch, s.pitch);
                float roll = Mth.m_14179_(partialTick, s.prevRoll, s.roll);
                float turnBend = Mth.m_14179_(partialTick, s.prevTurnBend, s.turnBend);
                float fishScale = scale * s.individualScale;
                BlockPos lightPos = BlockPos.m_274561_(rx, Math.min(pond.pos.m_123342_() + 0.85D, ry + 0.15D), rz);
                int light = LevelRenderer.m_109541_(level, lightPos);

                pose.m_85836_();
                try {
                    pose.m_85837_(rx - camPos.f_82479_, ry - camPos.f_82480_, rz - camPos.f_82481_);
                    if (Math.abs(roll) > 0.05F) {
                        // Bank gently around the fish's own longitudinal forward axis (-sin(yaw), 0, cos(yaw)).
                        pose.m_252781_(Axis.f_252436_.m_252977_(-yaw));
                        pose.m_252781_(Axis.f_252403_.m_252977_(roll));
                        pose.m_252781_(Axis.f_252436_.m_252977_(yaw));
                    }
                    if (isCustom) {
                        float animAge = (s.animTicks + partialTick) * s.swimSpeedMul + s.animPhase;
                        CustomFishModels.renderSwimming(pond.modelId, yaw, pitch, animAge, fishScale, turnBend, pose, buffers, light);
                    } else {
                        LivingEntity proxy = getOrCreateSwimmerProxy(level, pond.modelId, s);
                        if (proxy == null) continue;
                        EntityRenderer renderer = mc.m_91290_().m_114382_(proxy);
                        if (renderer == null) continue;

                        // Shift down by half scaled entity height so entity center sits at (rx, ry, rz)
                        float halfH = proxy.m_20206_() * 0.5F * fishScale;
                        pose.m_252880_(0.0F, -halfH, 0.0F);
                        pose.m_85841_(fishScale, fishScale, fishScale);

                        proxy.m_20219_(new Vec3(rx, ry, rz));
                        proxy.m_20256_(new Vec3(s.vx, s.vy, s.vz));
                        ((EntityWaterStateAccessor) proxy).sunlit$setWasTouchingWater(true);
                        proxy.m_6853_(false);
                        proxy.f_19797_ = s.animTicks;
                        proxy.m_146922_(s.yaw);
                        proxy.f_19859_ = s.prevYaw;
                        proxy.m_146926_(s.pitch);
                        proxy.f_19860_ = s.prevPitch;
                        proxy.f_20883_ = s.yaw;
                        proxy.f_20884_ = s.prevYaw;
                        proxy.f_20885_ = s.yaw;
                        proxy.f_20886_ = s.prevYaw;

                        FishMeshBend mesh = Math.abs(turnBend) > 0.025F
                                ? new FishMeshBend(buffers, pose.m_85850_().m_252922_(), yaw)
                                : null;
                        renderer.m_7392_(proxy, yaw, partialTick, pose, mesh == null ? buffers : mesh, light);
                        if (mesh != null) {
                            mesh.draw(turnCurve(turnBend));
                        }
                    }
                    drewAny = true;
                } catch (RuntimeException ex) {
                    SunlitFishVisuals.LOG.warn("Failed rendering fish pond visual for {}", pond.modelId, ex);
                    pond.active = false;
                    break;
                } finally {
                    pose.m_85849_();
                }
            }
        }

        if (drewAny) {
            // Flush now inside AFTER_ENTITIES so water translucent pass draws properly over submerged fish.
            buffers.m_109911_();
        }
    }

    /** Computes population & species-size-aware scale for the chosen pond visual mode. */
    public static float computeScale(Mode activeMode, int population, float entitySize) {
        return computeScale(activeMode, population, entitySize, adaptiveScale);
    }

    public static float computeScale(Mode activeMode, int population, float entitySize, float fullPondScale) {
        // Clamp very large fish (arapaima, large sharks/tuna) so they always fit comfortably inside a 3x4 basin.
        float speciesCap = entitySize > 0.78F ? Math.max(0.58F, 0.84F / entitySize) : 1.0F;
        if (activeMode == Mode.ADAPTIVE) {
            float target = clampAdaptiveScale(fullPondScale);
            float t;
            if (population <= 3) t = 0.0F;
            else if (population <= 5) t = 1.0F / 3.0F;
            else if (population <= 7) t = 2.0F / 3.0F;
            else t = 1.0F;
            float popScale = 1.0F + (target - 1.0F) * t;
            return Math.max(0.25F, popScale * speciesCap);
        }
        return speciesCap;
    }

    private static LivingEntity getOrCreateSwimmerProxy(ClientLevel level, ResourceLocation modelId, Swimmer swimmer) {
        if (swimmer.proxy != null && swimmer.proxy.m_9236_() == level) {
            return swimmer.proxy;
        }
        if (swimmer.proxyFailed) {
            return null;
        }
        EntityType<?> type = CatchModelIds.entityType(modelId);
        Entity created = type == null ? null : type.m_20615_(level);
        if (!(created instanceof LivingEntity living)) {
            swimmer.proxyFailed = true;
            return null;
        }
        // Give each swimmer a unique negative client ID and mark added-to-world so GeckoLib and
        // UnusualFishMod controllers evaluate SWIM animations independently without timestamp collisions.
        living.m_20234_(nextProxyId--);
        living.onAddedToWorld();
        ((EntityWaterStateAccessor) living).sunlit$setWasTouchingWater(true);
        living.m_6853_(false);
        living.f_19797_ = swimmer.animTicks;
        living.f_267362_.m_267566_(0.45F, 1.0F);
        living.f_267362_.m_267566_(0.45F, 1.0F);
        swimmer.proxy = living;
        return living;
    }

    private static float lerpAngle(float t, float from, float to) {
        float delta = wrapDegrees(to - from);
        return from + delta * t;
    }

    static float wrapDegrees(float deg) {
        float wrapped = ((deg % 360.0F) + 540.0F) % 360.0F - 180.0F;
        return wrapped == -180.0F ? 180.0F : wrapped;
    }

    /** Crisp C-whip spine profile engaged briefly during snappy fish flick-turns. */
    static FishFlopMotion turnCurve(float bend) {
        return new FishFlopMotion(
                -0.20F * bend,
                 0.12F * bend,
                 0.46F * bend,
                 0.80F * bend
        );
    }

    /** Computes the 3x4 water basin bounds behind a society:fish_pond block, matching global.validatePond. */
    public static PondBounds computeBounds(BlockPos pondPos, Direction facing, boolean deepWater, boolean isLava) {
        int bx = pondPos.m_123341_(), by = pondPos.m_123342_(), bz = pondPos.m_123343_();
        double minX, maxX, minZ, maxZ;
        switch (facing) {
            case SOUTH -> {
                minX = bx - 1.0D; maxX = bx + 2.0D;
                minZ = bz - 4.0D; maxZ = bz;
            }
            case EAST -> {
                minX = bx - 4.0D; maxX = bx;
                minZ = bz - 1.0D; maxZ = bz + 2.0D;
            }
            case WEST -> {
                minX = bx + 1.0D; maxX = bx + 5.0D;
                minZ = bz - 1.0D; maxZ = bz + 2.0D;
            }
            default -> { // NORTH
                minX = bx - 1.0D; maxX = bx + 2.0D;
                minZ = bz + 1.0D; maxZ = bz + 5.0D;
            }
        }
        double minY, maxY;
        if (isLava) {
            // Lava is opaque, so keep lava fish swimming near the surface where their backs & fins are clearly visible.
            minY = by + 0.56D;
            maxY = by + 0.78D;
        } else if (deepWater) {
            minY = by - 0.50D;
            maxY = by + 0.66D;
        } else {
            // Standard 1-block-deep 3x4 pond: stay below water surface (by+0.88) and above floor (by+0.0).
            minY = by + 0.28D;
            maxY = by + 0.64D;
        }
        return new PondBounds(minX, maxX, minY, maxY, minZ, maxZ, isLava);
    }

    private static Direction readFacing(BlockState state) {
        for (Property<?> prop : state.m_61147_()) {
            if ("facing".equals(prop.m_61708_())) {
                Object val = state.m_61143_(prop);
                if (val instanceof Direction dir && dir.m_122434_().m_122479_()) {
                    return dir;
                }
            }
        }
        return Direction.NORTH;
    }

    private static boolean readValid(BlockState state) {
        for (Property<?> prop : state.m_61147_()) {
            if ("valid".equals(prop.m_61708_())) {
                Object val = state.m_61143_(prop);
                if (val instanceof Boolean b) return b;
            }
        }
        return true;
    }

    public static final class PondBounds {
        public final double minX, maxX, minY, maxY, minZ, maxZ;
        public final double centerX, centerY, centerZ;
        public final boolean isLava;

        PondBounds(double minX, double maxX, double minY, double maxY, double minZ, double maxZ, boolean isLava) {
            this.minX = minX; this.maxX = maxX;
            this.minY = minY; this.maxY = maxY;
            this.minZ = minZ; this.maxZ = maxZ;
            this.centerX = (minX + maxX) * 0.5D;
            this.centerY = (minY + maxY) * 0.5D;
            this.centerZ = (minZ + maxZ) * 0.5D;
            this.isLava = isLava;
        }
    }

    private static final class PondVisual {
        final BlockPos pos;
        ResourceLocation modelId;
        int targetCount;
        int maxPop;
        boolean active;
        PondBounds bounds;
        Direction lastFacing = Direction.NORTH;
        int envCheckCounter;
        final List<Swimmer> swimmers = new ArrayList<>();
        final Random rng;

        PondVisual(BlockPos pos) {
            this.pos = pos;
            this.rng = new Random(pos.m_121878_() * 341873128712L + 132897987541L);
            this.bounds = computeBounds(pos, Direction.NORTH, false, false);
        }

        void updateSpec(ResourceLocation modelId, int population, int maxPop) {
            boolean speciesChanged = !modelId.equals(this.modelId);
            this.modelId = modelId;
            this.targetCount = population;
            this.maxPop = maxPop;
            if (speciesChanged) {
                swimmers.clear();
            }
            while (swimmers.size() > targetCount) {
                swimmers.remove(swimmers.size() - 1);
            }
            while (swimmers.size() < targetCount) {
                swimmers.add(spawnSwimmer(swimmers.size()));
            }
        }

        private Swimmer spawnSwimmer(int index) {
            Swimmer s = new Swimmer();
            s.animTicks = rng.nextInt(160);
            s.animPhase = rng.nextFloat() * 200.0F;
            s.swimSpeedMul = 0.85F + rng.nextFloat() * 0.25F;
            s.individualScale = 0.93F + rng.nextFloat() * 0.14F;
            s.speed = 0.015D + rng.nextDouble() * 0.008D;
            s.turnAgility = 30.0F + rng.nextFloat() * 8.0F;
            s.orbitPhase = (float) (index * 2.399963D + rng.nextDouble() * 0.6D);

            double margin = 0.36D;
            double sx = Mth.m_14139_(rng.nextDouble(), bounds.minX + margin, bounds.maxX - margin);
            double sy = Mth.m_14139_(rng.nextDouble(), bounds.minY, bounds.maxY);
            double sz = Mth.m_14139_(rng.nextDouble(), bounds.minZ + margin, bounds.maxZ - margin);
            s.x = s.prevX = sx;
            s.y = s.prevY = sy;
            s.z = s.prevZ = sz;
            s.yaw = s.prevYaw = rng.nextFloat() * 360.0F - 180.0F;
            s.idleHeading = s.yaw;
            pickTarget(s, bounds, margin, rng);
            if (rng.nextFloat() < 0.45F) {
                // Start some fish already calmly hovering so a freshly loaded pond feels serene.
                s.pauseTicks = 25 + rng.nextInt(70);
                s.idleHeading = s.yaw;
            }
            return s;
        }

        void tick(ClientLevel level, BlockState state, long gameTime, Mode activeMode) {
            if (envCheckCounter-- <= 0) {
                envCheckCounter = 20;
                refreshEnvironment(level, state);
            }
            if (!active || swimmers.isEmpty()) return;

            double margin = activeMode == Mode.ADAPTIVE && swimmers.size() >= 6 ? 0.32D : 0.36D;

            // Free swimming (SWIM or ADAPTIVE): calm resting, snappy flick-turns, kick-and-glide swimming & wall loitering
            double sepRadius = activeMode == Mode.ADAPTIVE && swimmers.size() >= 6 ? 0.52D : 0.64D;
            double sepRadiusSq = sepRadius * sepRadius;

            for (int i = 0; i < swimmers.size(); i++) {
                Swimmer s = swimmers.get(i);
                // Pairwise separation push so 7~10 fish never stack into a single blob
                double sepX = 0.0D, sepZ = 0.0D;
                for (int j = 0; j < swimmers.size(); j++) {
                    if (i == j) continue;
                    Swimmer other = swimmers.get(j);
                    double ox = s.x - other.x;
                    double oz = s.z - other.z;
                    double odSq = ox * ox + oz * oz;
                    if (odSq > 1.0e-5D && odSq < sepRadiusSq) {
                        double od = Math.sqrt(odSq);
                        double push = (sepRadius - od) / sepRadius;
                        sepX += (ox / od) * push;
                        sepZ += (oz / od) * push;
                    }
                }
                stepSwimmer(s, bounds, margin, sepX, sepZ, rng);
                if (s.animTicks == 0) {
                    // Reset proxy on rare wrap (~100 min) so GeckoLib never sees a negative tick delta.
                    s.proxy = null;
                }
                if (s.proxy != null) {
                    s.proxy.f_19797_ = s.animTicks;
                    float flickEffort = Math.min(1.0F, Math.abs(s.yawVel) / 26.0F);
                    float speedRatio = (float) Math.min(1.4D, Math.hypot(s.vx, s.vz) / Math.max(0.008D, s.speed));
                    float walkSpeed = s.pauseTicks > 0
                            ? Mth.m_14036_(0.13F + speedRatio * 0.14F + flickEffort * 0.45F, 0.12F, 0.68F)
                            : Mth.m_14036_(0.20F + speedRatio * 0.24F + flickEffort * 0.42F, 0.15F, 0.80F);
                    s.proxy.f_267362_.m_267566_(walkSpeed, 0.4F);
                }
            }
        }

        private void refreshEnvironment(ClientLevel level, BlockState state) {
            if (!readValid(state)) {
                active = false;
                return;
            }
            Direction facing = readFacing(state);
            PondBounds probe = computeBounds(pos, facing, false, false);
            int waterCount = 0;
            int lavaCount = 0;
            int deepCount = 0;
            int bx0 = (int) Math.floor(probe.minX);
            int bx1 = (int) Math.floor(probe.maxX) - 1;
            int bz0 = (int) Math.floor(probe.minZ);
            int bz1 = (int) Math.floor(probe.maxZ) - 1;
            int by = pos.m_123342_();

            for (int x = bx0; x <= bx1; x++) {
                for (int z = bz0; z <= bz1; z++) {
                    BlockPos p = new BlockPos(x, by, z);
                    BlockState bs = level.m_8055_(p);
                    FluidState fs = bs.m_60819_();
                    if (fs.m_205070_(FluidTags.f_13132_)) {
                        lavaCount++;
                    } else if (fs.m_205070_(FluidTags.f_13131_) || bs.m_60713_(Blocks.f_50126_)) {
                        waterCount++;
                        if (level.m_6425_(p.m_7495_()).m_205070_(FluidTags.f_13131_)) {
                            deepCount++;
                        }
                    }
                }
            }
            if (waterCount + lavaCount < 6) {
                active = false;
                return;
            }
            boolean isLava = lavaCount > waterCount;
            boolean deepWater = !isLava && deepCount >= 8;
            boolean boundsChanged = facing != lastFacing || bounds.isLava != isLava
                    || Math.abs(bounds.minY - computeBounds(pos, facing, deepWater, isLava).minY) > 0.05D;
            this.lastFacing = facing;
            this.bounds = computeBounds(pos, facing, deepWater, isLava);
            this.active = true;
            if (boundsChanged) {
                for (Swimmer s : swimmers) {
                    s.x = s.prevX = Mth.m_14008_(s.x, bounds.minX + 0.48D, bounds.maxX - 0.48D);
                    s.y = s.prevY = Mth.m_14008_(s.y, bounds.minY, bounds.maxY);
                    s.z = s.prevZ = Mth.m_14008_(s.z, bounds.minZ + 0.48D, bounds.maxZ - 0.48D);
                    pickTarget(s, bounds, 0.48D, rng);
                }
            }
        }
    }

    static void pickTarget(Swimmer s, PondBounds bounds, double margin, Random rng) {
        double minX = bounds.minX + margin;
        double maxX = bounds.maxX - margin;
        double minZ = bounds.minZ + margin;
        double maxZ = bounds.maxZ - margin;
        double bestX = Mth.m_14008_(s.x, minX, maxX);
        double bestZ = Mth.m_14008_(s.z, minZ, maxZ);
        double bestScore = Double.NEGATIVE_INFINITY;
        // Sample several candidate waypoints across the basin (including near walls) and mildly prefer
        // natural forward/angled glides over constant 180-degree reversals.
        boolean preferGentle = rng.nextFloat() < 0.72F;
        for (int attempt = 0; attempt < 8; attempt++) {
            double tx = minX + rng.nextDouble() * Math.max(0.05D, maxX - minX);
            double tz = minZ + rng.nextDouble() * Math.max(0.05D, maxZ - minZ);
            double ddx = tx - s.x, ddz = tz - s.z;
            double dist = Math.hypot(ddx, ddz);
            if (dist < 0.55D && attempt < 7) continue;
            float angleTo = (float) Math.toDegrees(Math.atan2(-ddx, ddz));
            float absTurn = Math.abs(wrapDegrees(angleTo - s.yaw));
            double score = Math.min(dist, 2.0D) + rng.nextDouble() * 0.45D;
            if (preferGentle) {
                if (absTurn <= 85.0F) score += 1.15D;
                else if (absTurn >= 135.0F) score -= 0.75D;
            }
            if (score > bestScore) {
                bestScore = score;
                bestX = tx;
                bestZ = tz;
            }
        }
        s.targetX = bestX;
        s.targetZ = bestZ;
        s.targetY = bounds.minY + rng.nextDouble() * Math.max(0.02D, bounds.maxY - bounds.minY);
        s.pauseTicks = 0;
        s.turnDirection = 0;
    }

    private static void pickWallFollowTarget(Swimmer s, PondBounds bounds, double margin,
                                             boolean hitX, boolean hitZ, Random rng) {
        double minX = bounds.minX + margin;
        double maxX = bounds.maxX - margin;
        double minZ = bounds.minZ + margin;
        double maxZ = bounds.maxZ - margin;
        double rad = Math.toRadians(s.yaw);
        double fwdX = -Math.sin(rad);
        double fwdZ = Math.cos(rad);
        double tx = s.x;
        double tz = s.z;
        if (hitX && !hitZ) {
            // Glide calmly along the Z wall in whichever direction is closer to current heading.
            double dirZ = Math.abs(fwdZ) > 0.15D ? Math.signum(fwdZ) : (rng.nextBoolean() ? 1.0D : -1.0D);
            double span = 0.75D + rng.nextDouble() * 1.15D;
            tz = Mth.m_14008_(s.z + dirZ * span, minZ + 0.05D, maxZ - 0.05D);
            if (Math.abs(tz - s.z) < 0.35D) {
                tz = Mth.m_14008_(s.z - dirZ * span, minZ + 0.05D, maxZ - 0.05D);
            }
            double inward = s.x <= (minX + maxX) * 0.5D ? 1.0D : -1.0D;
            tx = Mth.m_14008_(s.x + inward * (0.08D + rng.nextDouble() * 0.30D), minX, maxX);
        } else if (hitZ && !hitX) {
            // Glide calmly along the X wall.
            double dirX = Math.abs(fwdX) > 0.15D ? Math.signum(fwdX) : (rng.nextBoolean() ? 1.0D : -1.0D);
            double span = 0.65D + rng.nextDouble() * 1.05D;
            tx = Mth.m_14008_(s.x + dirX * span, minX + 0.05D, maxX - 0.05D);
            if (Math.abs(tx - s.x) < 0.35D) {
                tx = Mth.m_14008_(s.x - dirX * span, minX + 0.05D, maxX - 0.05D);
            }
            double inward = s.z <= (minZ + maxZ) * 0.5D ? 1.0D : -1.0D;
            tz = Mth.m_14008_(s.z + inward * (0.08D + rng.nextDouble() * 0.30D), minZ, maxZ);
        } else {
            pickTarget(s, bounds, margin, rng);
            return;
        }
        s.targetX = tx;
        s.targetZ = tz;
        s.targetY = bounds.minY + rng.nextDouble() * Math.max(0.02D, bounds.maxY - bounds.minY);
        s.pauseTicks = 0;
        s.turnDirection = 0;
    }

    /**
     * Advances a single pond swimmer by one client tick using lifelike aquarium/pond fish physics:
     * calm stillness/hovering, snappy dart-and-flick turns (both small angle adjustments and full reversals
     * whip cleanly in ~3-7 ticks rather than slowly creeping in artificial arcs), kick-and-glide propulsion,
     * and natural wall-side resting or parallel wall gliding.
     */
    public static void stepSwimmer(Swimmer s, PondBounds bounds, double margin, double sepX, double sepZ, Random rng) {
        s.prevX = s.x; s.prevY = s.y; s.prevZ = s.z;
        s.prevYaw = s.yaw; s.prevPitch = s.pitch;
        s.prevRoll = s.roll; s.prevTurnBend = s.turnBend;
        if (Math.abs(s.yaw) > 36_000.0F) {
            float shift = Math.round(s.yaw / 360.0F) * 360.0F;
            s.yaw -= shift;
            s.prevYaw -= shift;
            s.idleHeading -= shift;
        }
        s.animTicks = advanceAnimTicks(s.animTicks);

        double minX = bounds.minX + margin, maxX = bounds.maxX - margin;
        double minZ = bounds.minZ + margin, maxZ = bounds.maxZ - margin;

        double dx = s.targetX - s.x;
        double dy = s.targetY - s.y;
        double dz = s.targetZ - s.z;
        double horizDistSq = dx * dx + dz * dz;

        if (s.pauseTicks > 0) {
            if (--s.pauseTicks == 0) {
                pickTarget(s, bounds, margin, rng);
                dx = s.targetX - s.x;
                dy = s.targetY - s.y;
                dz = s.targetZ - s.z;
                horizDistSq = dx * dx + dz * dz;
            }
        } else if (horizDistSq < 0.10D) {
            if (rng.nextFloat() < 0.72F) {
                // Rest calmly in place (2.2s - 6.7s) rather than constantly swimming back and forth.
                s.pauseTicks = 45 + rng.nextInt(90);
                s.idleHeading = s.yaw;
                s.turnDirection = 0;
            } else {
                pickTarget(s, bounds, margin, rng);
                dx = s.targetX - s.x;
                dy = s.targetY - s.y;
                dz = s.targetZ - s.z;
                horizDistSq = dx * dx + dz * dz;
            }
        }

        float flickStep = 0.0F;
        double flickKick = 0.0D;
        float maxFlick = s.turnAgility > 0.0F ? s.turnAgility : 34.0F;

        if (s.pauseTicks > 0) {
            // Stay calm and steady; occasionally perform a crisp little 20-55 deg flick-glance instead of slow creeping.
            double sepMagSq = sepX * sepX + sepZ * sepZ;
            if (sepMagSq > 0.20D) {
                s.idleHeading = (float) Math.toDegrees(Math.atan2(-sepX, sepZ));
            } else if (s.pauseTicks > 18 && Math.abs(wrapDegrees(s.idleHeading - s.yaw)) < 2.0F && rng.nextFloat() < 0.018F) {
                float glance = (20.0F + rng.nextFloat() * 35.0F) * (rng.nextBoolean() ? 1.0F : -1.0F);
                s.idleHeading = s.yaw + glance;
            }
            float idleErr = wrapDegrees(s.idleHeading - s.yaw);
            if (Math.abs(idleErr) > 0.8F) {
                flickStep = Mth.m_14036_(idleErr * 0.48F, -maxFlick * 0.75F, maxFlick * 0.75F);
                s.yaw += flickStep;
                if (Math.abs(flickStep) > 5.0F) {
                    flickKick = s.speed * 0.45D;
                }
            }
            s.yawVel = flickStep;
        } else {
            double invDist = horizDistSq > 1.0e-5D ? 1.0D / Math.sqrt(horizDistSq) : 0.0D;
            double steerX = dx * invDist + sepX * 0.55D;
            double steerZ = dz * invDist + sepZ * 0.55D;
            if (steerX * steerX + steerZ * steerZ > 1.0e-5D) {
                float desiredYaw = (float) Math.toDegrees(Math.atan2(-steerX, steerZ));
                float err = wrapDegrees(desiredYaw - s.yaw);
                float absErr = Math.abs(err);

                // Commit to one turn side on sharp reversals so the whip never hesitates or flips mid-flick.
                if (absErr > 135.0F) {
                    if (s.turnDirection == 0) {
                        if (Math.abs(s.yawVel) > 2.0F) {
                            s.turnDirection = s.yawVel > 0.0F ? 1 : -1;
                        } else {
                            s.turnDirection = rng.nextBoolean() ? 1 : -1;
                        }
                    }
                    err = s.turnDirection * absErr;
                } else if (absErr < 50.0F) {
                    s.turnDirection = 0;
                }

                // Snappy fast-attack exponential flick: both small adjustments (20-50 deg) and full
                // turnarounds (180 deg) whip cleanly in ~3-7 ticks, then hold straight and glide.
                if (absErr > 0.6F) {
                    flickStep = Mth.m_14036_(err * 0.45F, -maxFlick, maxFlick);
                    if (absErr < 4.0F) {
                        flickStep = err;
                    }
                    s.yaw += flickStep;
                }
                s.yawVel = flickStep;

                // Give a brief natural tail-flick glide kick right as the fish snaps into the new heading.
                if (Math.abs(flickStep) > 6.0F && absErr < 65.0F) {
                    flickKick = s.speed * 0.65D;
                }
            } else {
                s.yawVel *= 0.50F;
            }
        }

        double rad = Math.toRadians(s.yaw);
        double headingX = -Math.sin(rad);
        double headingZ = Math.cos(rad);

        if (s.pauseTicks > 0) {
            // Smooth water drag to calm stillness, with tiny push from a curious idle flick or neighbor separation.
            s.vx = s.vx * 0.84D + headingX * flickKick * 0.35D + sepX * 0.0018D;
            s.vz = s.vz * 0.84D + headingZ * flickKick * 0.35D + sepZ * 0.0018D;
        } else {
            // Kick-and-glide swimming: brief crisp stroke when snapping a turn or periodically while cruising,
            // followed by relaxed coasting along the new heading.
            double arrivalEase = horizDistSq < 0.30D ? 0.40D + 0.60D * (Math.sqrt(horizDistSq) / 0.55D) : 1.0D;
            boolean strokeBeat = ((s.animTicks + (int) (s.orbitPhase * 11.0F)) % 28) < 4;
            double cruiseTarget = s.speed * (strokeBeat ? 1.28D : 0.78D) * arrivalEase + flickKick;
            double desiredVx = headingX * cruiseTarget;
            double desiredVz = headingZ * cruiseTarget;
            // Redirect velocity crisply on flick turns so the fish darts cleanly along the new heading without drifting sideways.
            double velBlend = Math.abs(flickStep) > 6.0F ? 0.46D : (strokeBeat ? 0.26D : 0.14D);
            s.vx += (desiredVx - s.vx) * velBlend;
            s.vz += (desiredVz - s.vz) * velBlend;
        }

        double stepY = Mth.m_14008_(dy * 0.045D, -0.007D, 0.007D);
        double nextX = s.x + s.vx;
        double nextZ = s.z + s.vz;
        boolean hitX = false;
        boolean hitZ = false;
        if (nextX < minX || nextX > maxX) {
            nextX = Mth.m_14008_(nextX, minX, maxX);
            s.vx = 0.0D;
            hitX = true;
        }
        if (nextZ < minZ || nextZ > maxZ) {
            nextZ = Mth.m_14008_(nextZ, minZ, maxZ);
            s.vz = 0.0D;
            hitZ = true;
        }
        double nextY = Mth.m_14008_(s.y + stepY + Math.sin(s.animTicks * 0.06D + s.orbitPhase) * 0.0012D, bounds.minY, bounds.maxY);
        s.vy = nextY - s.y;
        s.x = nextX;
        s.y = nextY;
        s.z = nextZ;

        if ((hitX || hitZ) && s.pauseTicks <= 0) {
            // Reaching a wall does NOT force a turnaround:
            // often rest calmly right by the wall, or flick smoothly along the wall, or flick back into the pond.
            float choice = rng.nextFloat();
            if (choice < 0.45F) {
                s.pauseTicks = 40 + rng.nextInt(75);
                s.idleHeading = s.yaw;
                s.targetX = s.x;
                s.targetZ = s.z;
                s.turnDirection = 0;
            } else if (choice < 0.82F) {
                pickWallFollowTarget(s, bounds, margin, hitX, hitZ, rng);
            } else {
                pickTarget(s, bounds, margin, rng);
            }
        }

        float desiredPitch = (float) Mth.m_14008_(-s.vy * 380.0D, -10.0D, 10.0D);
        s.pitch += (desiredPitch - s.pitch) * 0.22F;

        // Crisp body whip and light bank synced directly to the flick; snaps straight immediately once gliding.
        float targetRoll = Mth.m_14036_(s.yawVel * 0.16F, -5.5F, 5.5F);
        s.roll += (targetRoll - s.roll) * 0.45F;
        float targetBend = Mth.m_14036_(s.yawVel * 0.017F, -0.52F, 0.52F);
        float bendRate = Math.abs(targetBend) > Math.abs(s.turnBend) ? 0.68F : 0.36F;
        s.turnBend += (targetBend - s.turnBend) * bendRate;
    }

    public static final class Swimmer {
        public double x, y, z;
        public double prevX, prevY, prevZ;
        public double vx, vy, vz;
        public double targetX, targetY, targetZ;
        public float yaw, prevYaw;
        public float yawVel;
        public float idleHeading;
        public float pitch, prevPitch;
        public float roll, prevRoll;
        public float turnBend, prevTurnBend;
        public int animTicks;
        public float animPhase;
        public float swimSpeedMul = 1.0F;
        public float individualScale = 1.0F;
        public double speed = 0.018D;
        public float turnAgility = 34.0F;
        public int turnDirection;
        public float orbitPhase;
        public int pauseTicks;
        LivingEntity proxy;
        boolean proxyFailed;
    }
}
