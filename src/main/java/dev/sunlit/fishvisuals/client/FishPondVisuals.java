package dev.sunlit.fishvisuals.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.brigadier.arguments.FloatArgumentType;
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
                float fishScale = scale * s.individualScale;
                BlockPos lightPos = BlockPos.m_274561_(rx, Math.min(pond.pos.m_123342_() + 0.85D, ry + 0.15D), rz);
                int light = LevelRenderer.m_109541_(level, lightPos);

                pose.m_85836_();
                try {
                    pose.m_85837_(rx - camPos.f_82479_, ry - camPos.f_82480_, rz - camPos.f_82481_);
                    if (isCustom) {
                        float animAge = (s.animTicks + partialTick) * s.swimSpeedMul + s.animPhase;
                        CustomFishModels.renderSwimming(pond.modelId, yaw, pitch, animAge, fishScale, pose, buffers, light);
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

                        renderer.m_7392_(proxy, yaw, partialTick, pose, buffers, light);
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
        float delta = (((to - from) % 360.0F) + 540.0F) % 360.0F - 180.0F;
        return from + delta * t;
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
            s.swimSpeedMul = 0.85F + rng.nextFloat() * 0.30F;
            s.individualScale = 0.93F + rng.nextFloat() * 0.14F;
            s.speed = 0.020D + rng.nextDouble() * 0.014D;
            s.orbitPhase = (float) (index * 2.399963D + rng.nextDouble() * 0.6D);

            double margin = 0.48D;
            double sx = Mth.m_14139_(rng.nextDouble(), bounds.minX + margin, bounds.maxX - margin);
            double sy = Mth.m_14139_(rng.nextDouble(), bounds.minY, bounds.maxY);
            double sz = Mth.m_14139_(rng.nextDouble(), bounds.minZ + margin, bounds.maxZ - margin);
            s.x = s.prevX = sx;
            s.y = s.prevY = sy;
            s.z = s.prevZ = sz;
            s.yaw = s.prevYaw = rng.nextFloat() * 360.0F;
            pickTarget(s, margin);
            return s;
        }

        private void pickTarget(Swimmer s, double margin) {
            double minX = bounds.minX + margin, maxX = bounds.maxX - margin;
            double minZ = bounds.minZ + margin, maxZ = bounds.maxZ - margin;
            // Prefer a waypoint at least 0.7 blocks away so turns feel unhurried and fluid.
            for (int attempt = 0; attempt < 6; attempt++) {
                double tx = minX + rng.nextDouble() * Math.max(0.05D, maxX - minX);
                double tz = minZ + rng.nextDouble() * Math.max(0.05D, maxZ - minZ);
                double ddx = tx - s.x, ddz = tz - s.z;
                if (ddx * ddx + ddz * ddz >= 0.49D || attempt == 5) {
                    s.targetX = tx;
                    s.targetZ = tz;
                    break;
                }
            }
            s.targetY = bounds.minY + rng.nextDouble() * Math.max(0.02D, bounds.maxY - bounds.minY);
            s.pauseTicks = 0;
        }

        void tick(ClientLevel level, BlockState state, long gameTime, Mode activeMode) {
            if (envCheckCounter-- <= 0) {
                envCheckCounter = 20;
                refreshEnvironment(level, state);
            }
            if (!active || swimmers.isEmpty()) return;

            double margin = activeMode == Mode.ADAPTIVE && swimmers.size() >= 6 ? 0.38D : 0.46D;
            double minX = bounds.minX + margin, maxX = bounds.maxX - margin;
            double minZ = bounds.minZ + margin, maxZ = bounds.maxZ - margin;

            // Free swimming (SWIM or ADAPTIVE): smooth waypoint steering + gentle boids separation
            double sepRadius = activeMode == Mode.ADAPTIVE && swimmers.size() >= 6 ? 0.56D : 0.70D;
            double sepRadiusSq = sepRadius * sepRadius;

            for (int i = 0; i < swimmers.size(); i++) {
                Swimmer s = swimmers.get(i);
                s.prevX = s.x; s.prevY = s.y; s.prevZ = s.z;
                s.prevYaw = s.yaw; s.prevPitch = s.pitch;
                s.animTicks = advanceAnimTicks(s.animTicks);
                if (s.animTicks == 0) {
                    // Reset proxy on rare wrap (~100 min) so GeckoLib never sees a negative tick delta.
                    s.proxy = null;
                }

                double dx = s.targetX - s.x;
                double dy = s.targetY - s.y;
                double dz = s.targetZ - s.z;
                double horizDistSq = dx * dx + dz * dz;

                if (horizDistSq < 0.09D) {
                    if (s.pauseTicks <= 0) {
                        s.pauseTicks = 12 + rng.nextInt(26);
                    } else if (--s.pauseTicks == 0) {
                        pickTarget(s, margin);
                        dx = s.targetX - s.x;
                        dy = s.targetY - s.y;
                        dz = s.targetZ - s.z;
                        horizDistSq = dx * dx + dz * dz;
                    }
                }

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

                double moveSpeed = s.pauseTicks > 0 ? s.speed * 0.25D : s.speed;
                double steerX = (horizDistSq > 1.0e-5D ? dx / Math.sqrt(horizDistSq) : 0.0D) + sepX * 0.85D;
                double steerZ = (horizDistSq > 1.0e-5D ? dz / Math.sqrt(horizDistSq) : 0.0D) + sepZ * 0.85D;
                if (steerX * steerX + steerZ * steerZ > 1.0e-5D) {
                    float desiredYaw = (float) Math.toDegrees(Math.atan2(-steerX, steerZ));
                    s.yaw = stepAngle(s.yaw, desiredYaw, s.pauseTicks > 0 ? 3.5F : 6.2F);
                }

                double rad = Math.toRadians(s.yaw);
                double stepX = -Math.sin(rad) * moveSpeed;
                double stepZ = Math.cos(rad) * moveSpeed;
                double stepY = Mth.m_14008_(dy * 0.06D, -0.010D, 0.010D);

                double nextX = s.x + stepX;
                double nextZ = s.z + stepZ;
                boolean hitWall = false;
                if (nextX < minX || nextX > maxX) {
                    nextX = Mth.m_14008_(nextX, minX, maxX);
                    hitWall = true;
                }
                if (nextZ < minZ || nextZ > maxZ) {
                    nextZ = Mth.m_14008_(nextZ, minZ, maxZ);
                    hitWall = true;
                }
                double nextY = Mth.m_14008_(s.y + stepY + Math.sin(s.animTicks * 0.08D + s.orbitPhase) * 0.002D, bounds.minY, bounds.maxY);
                s.vx = nextX - s.x;
                s.vy = nextY - s.y;
                s.vz = nextZ - s.z;
                s.x = nextX;
                s.y = nextY;
                s.z = nextZ;
                if (hitWall) {
                    pickTarget(s, margin);
                }
                float desiredPitch = (float) Mth.m_14008_(-stepY * 450.0D, -12.0D, 12.0D);
                s.pitch = s.pitch + (desiredPitch - s.pitch) * 0.18F;

                if (s.proxy != null) {
                    s.proxy.f_19797_ = s.animTicks;
                    float walkSpeed = s.pauseTicks > 0 ? 0.24F : 0.48F;
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
                    pickTarget(s, 0.48D);
                }
            }
        }

        private static float stepAngle(float current, float target, float maxStep) {
            float delta = (((target - current) % 360.0F) + 540.0F) % 360.0F - 180.0F;
            return current + Mth.m_14036_(delta, -maxStep, maxStep);
        }
    }

    private static final class Swimmer {
        double x, y, z;
        double prevX, prevY, prevZ;
        double vx, vy, vz;
        double targetX, targetY, targetZ;
        float yaw, prevYaw;
        float pitch, prevPitch;
        int animTicks;
        float animPhase;
        float swimSpeedMul;
        float individualScale;
        double speed;
        float orbitPhase;
        int pauseTicks;
        LivingEntity proxy;
        boolean proxyFailed;
    }
}
