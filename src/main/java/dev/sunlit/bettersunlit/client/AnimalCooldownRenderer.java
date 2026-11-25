package dev.sunlit.bettersunlit.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sunlit.bettersunlit.husbandry.AnimalStatusSync;
import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Renders 3D overhead readiness & 1/3-step cooldown icons over animals when the player
 * holds a resource-harvesting tool whose cooldown/state is not visually shown on the model.
 */
@Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, value = Dist.CLIENT)
public final class AnimalCooldownRenderer {
    private static final double MAX_RENDER_DIST = 16.0D;
    private static final double MAX_RENDER_DIST_SQ = MAX_RENDER_DIST * MAX_RENDER_DIST;
    private static final float ICON_HALF_SIZE = 0.22f;
    private static final ResourceLocation WHITE_TEXTURE = new ResourceLocation(SunlitFishVisuals.ID, "dynamic/animal_icon/white");
    private static final int DAY_FILLED = 0x62E0BD;
    private static final int DAY_FILLED_BLOCKED = 0xB29D91;
    private static final int DAY_EMPTY = 0x45505D;
    private static final int FULL_BRIGHT = 0x00F000F0;

    private static final int STAGE_GRAY = 0;
    private static final int STAGE_ONE_THIRD = 1;
    private static final int STAGE_TWO_THIRDS = 2;
    private static final int STAGE_READY = 3;
    private static final int STAGE_BLOCKED = 4;
    private static final int FRAME_COUNT = 5;

    private static final ResourceLocation ICON_MILK = new ResourceLocation("society", "milk");
    private static final ResourceLocation ICON_SHEEP_MILK = new ResourceLocation("society", "sheep_milk");
    private static final ResourceLocation ICON_GOAT_MILK = new ResourceLocation("society", "goat_milk");
    private static final ResourceLocation ICON_BUFFALO_MILK = new ResourceLocation("society", "buffalo_milk");
    private static final ResourceLocation ICON_TRIBULL_MILK = new ResourceLocation("society", "tri_bull_milk");
    private static final ResourceLocation ICON_WARPED_MILK = new ResourceLocation("society", "warped_milk");
    private static final ResourceLocation ICON_AMETHYST_MILK = new ResourceLocation("society", "amethyst_milk");
    private static final ResourceLocation ICON_INK_SAC = new ResourceLocation("minecraft", "ink_sac");
    private static final ResourceLocation ICON_GLOW_INK_SAC = new ResourceLocation("minecraft", "glow_ink_sac");
    private static final ResourceLocation ICON_ICHOR = new ResourceLocation("species", "ichor_bottle");
    private static final ResourceLocation ICON_MAGIC_SHEARS = new ResourceLocation("society", "magic_shears");

    private static final Map<String, MilkProduceInfo> MILK_PRODUCE_MAP = buildMilkProduceMap();
    private static final Map<Integer, AnimalStatusSync.AnimalSnapshot> STATES = new HashMap<>();
    private static final Map<ResourceLocation, IconStrip> STRIP_CACHE = new HashMap<>();
    private static final Set<ResourceLocation> FAILED_STRIP_LOGS = new HashSet<>();
    private static Object whiteTextureResourceManager;
    private static ClientLevel currentLevel;
    private static int cleanupCounter;
    private static long lastServerDayTime = -1L;
    private static long lastPacketGameTime = 0L;

    private AnimalCooldownRenderer() {}

    @Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBusEvents {
        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            AnimalStatusSync.setClientReceiver(AnimalCooldownRenderer::receive);
        }
    }

    private enum ToolMode {
        MILK_PAIL,
        MAGIC_SHEARS,
        GLASS_BOTTLE
    }

    private record MilkProduceInfo(ResourceLocation iconId, int cooldownDays) {}

    private static Map<String, MilkProduceInfo> buildMilkProduceMap() {
        Map<String, MilkProduceInfo> map = new HashMap<>();
        map.put("minecraft:cow", new MilkProduceInfo(ICON_MILK, 1));
        map.put("minecraft:mooshroom", new MilkProduceInfo(ICON_MILK, 1));
        map.put("meadow:wooly_cow", new MilkProduceInfo(ICON_MILK, 1));
        map.put("windswept:frostbiter", new MilkProduceInfo(ICON_MILK, 1));
        map.put("buzzier_bees:moobloom", new MilkProduceInfo(ICON_AMETHYST_MILK, 1));
        map.put("minecraft:sheep", new MilkProduceInfo(ICON_SHEEP_MILK, 2));
        map.put("meadow:wooly_sheep", new MilkProduceInfo(ICON_SHEEP_MILK, 2));
        map.put("wildernature:minisheep", new MilkProduceInfo(ICON_SHEEP_MILK, 2));
        map.put("minecraft:goat", new MilkProduceInfo(ICON_GOAT_MILK, 1));
        map.put("meadow:water_buffalo", new MilkProduceInfo(ICON_BUFFALO_MILK, 1));
        map.put("wildernature:bison", new MilkProduceInfo(ICON_BUFFALO_MILK, 1));
        map.put("farmlife:domestic_tribull", new MilkProduceInfo(ICON_TRIBULL_MILK, 1));
        map.put("minecraft:squid", new MilkProduceInfo(ICON_INK_SAC, 1));
        map.put("minecraft:glow_squid", new MilkProduceInfo(ICON_GLOW_INK_SAC, 1));
        map.put("species:mammutilation", new MilkProduceInfo(ICON_ICHOR, 2));
        return map;
    }

    public static void receive(AnimalStatusSync.StatusPacket packet) {
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        if (level == null || !level.m_46472_().m_135782_().equals(packet.dimension())) return;
        syncLevel(level);
        lastServerDayTime = packet.serverDayTime();
        lastPacketGameTime = level.m_46467_();
        for (AnimalStatusSync.AnimalSnapshot entry : packet.entries()) {
            STATES.put(entry.entityId(), entry);
        }
    }

    private static void syncLevel(ClientLevel level) {
        if (level != currentLevel) {
            STATES.clear();
            currentLevel = level;
            lastServerDayTime = -1L;
            lastPacketGameTime = 0L;
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        STATES.clear();
        currentLevel = null;
        lastServerDayTime = -1L;
        lastPacketGameTime = 0L;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        ClientLevel level = Minecraft.m_91087_().f_91073_;
        syncLevel(level);
        if (level != null && ++cleanupCounter >= 40) {
            cleanupCounter = 0;
            STATES.keySet().removeIf(id -> {
                Entity e = level.m_6815_(id);
                return e == null || !e.m_6084_();
            });
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES) return;
        Minecraft mc = Minecraft.m_91087_();
        Player player = mc.f_91074_;
        ClientLevel level = mc.f_91073_;
        if (player == null || level == null) return;

        ToolMode mode = detectToolMode(player);
        if (mode == null) return;

        Camera camera = event.getCamera();
        Vec3 camPos = camera.m_90583_();
        AABB box = new AABB(
                camPos.f_82479_ - MAX_RENDER_DIST, camPos.f_82480_ - MAX_RENDER_DIST, camPos.f_82481_ - MAX_RENDER_DIST,
                camPos.f_82479_ + MAX_RENDER_DIST, camPos.f_82480_ + MAX_RENDER_DIST, camPos.f_82481_ + MAX_RENDER_DIST
        );

        var entities = level.m_45976_(LivingEntity.class, box);
        if (entities.isEmpty()) return;

        long rawDayTime = level.m_46468_();
        long clientDayTime = rawDayTime < 0L ? -rawDayTime : rawDayTime;
        long dayTime = clientDayTime;
        if (lastServerDayTime >= 0L) {
            long estimatedServerTime = lastServerDayTime + Math.max(0L, level.m_46467_() - lastPacketGameTime);
            if (Math.abs(clientDayTime - estimatedServerTime) > 100L) {
                dayTime = estimatedServerTime;
            }
        }
        long currentDay = Math.floorDiv(dayTime, AnimalStatusSync.TICKS_PER_DAY) + 1L;
        float partialTick = event.getPartialTick();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.m_91269_().m_110104_();
        Set<RenderType> usedRenderTypes = null;

        for (LivingEntity entity : entities) {
            if (entity == player || !entity.m_6084_() || entity.m_20145_()) continue;
            if (entity.m_20280_(player) > MAX_RENDER_DIST_SQ) continue;

            AnimalStatusSync.AnimalSnapshot snap = STATES.get(entity.m_19879_());
            if (snap == null) continue;

            ResourceLocation iconItemId;
            int stage;
            int cooldownDays = 1;
            int daysRemaining = 0;
            if (mode == ToolMode.MILK_PAIL) {
                if (entity.m_6162_() || !snap.isMilkable()) continue;
                MilkProduceInfo info = resolveMilkInfo(entity, snap);
                iconItemId = info.iconId();
                cooldownDays = info.cooldownDays();
                daysRemaining = computeMilkDaysRemaining(snap, cooldownDays, currentDay);
                stage = milkStage(snap, daysRemaining, dayTime, currentDay);
            } else if (mode == ToolMode.MAGIC_SHEARS) {
                if (!snap.isHusbandry()) continue;
                iconItemId = ICON_MAGIC_SHEARS;
                stage = computeMagicStage(snap, dayTime, currentDay);
            } else {
                if (entity.m_6162_() || snap.ichorState() < 0) continue;
                iconItemId = ICON_ICHOR;
                stage = snap.ichorState() >= 3 ? STAGE_READY : STAGE_GRAY;
            }

            IconStrip strip = getOrBuildStrip(mc, iconItemId);
            if (strip == null) continue;

            double ex = entity.f_19854_ + (entity.m_20185_() - entity.f_19854_) * partialTick;
            double ey = entity.f_19855_ + (entity.m_20186_() - entity.f_19855_) * partialTick;
            double ez = entity.f_19856_ + (entity.m_20189_() - entity.f_19856_) * partialTick;
            double headOffset = entity.m_20206_() + (entity.m_8077_() ? 0.68D : 0.42D);

            RenderType renderType = RenderType.m_110473_(strip.textureLoc);
            if (usedRenderTypes == null) usedRenderTypes = new HashSet<>(4);
            usedRenderTypes.add(renderType);

            float u0 = stage / (float) FRAME_COUNT;
            float u1 = (stage + 1) / (float) FRAME_COUNT;

            poseStack.m_85836_();
            poseStack.m_85837_(ex - camPos.f_82479_, ey + headOffset - camPos.f_82480_, ez - camPos.f_82481_);
            poseStack.m_252781_(camera.m_253121_());
            Matrix4f mat = poseStack.m_85850_().m_252922_();
            Matrix3f norm = poseStack.m_85850_().m_252943_();
            float s = ICON_HALF_SIZE;
            emitQuad(buffers.m_6299_(renderType), mat, norm, -s, -s, s, s, u1, u0, 0xFFFFFF);
            if (cooldownDays > 1) {
                ensureWhiteTexture(mc);
                RenderType dayType = RenderType.m_110473_(WHITE_TEXTURE);
                usedRenderTypes.add(dayType);
                drawMilkDays(buffers.m_6299_(dayType), mat, norm, cooldownDays, daysRemaining, stage);
            }
            poseStack.m_85849_();
        }

        if (usedRenderTypes != null) {
            for (RenderType rt : usedRenderTypes) {
                buffers.m_109912_(rt);
            }
        }
    }

    private static void drawMilkDays(VertexConsumer vertices, Matrix4f mat, Matrix3f norm,
                                     int totalDays, int daysRemaining, int stage) {
        float span = 0.32f;
        float gap = 0.024f;
        float cell = (span - gap * (totalDays - 1)) / totalDays;
        float x = -span / 2.0f;
        int completeColor = stage == STAGE_BLOCKED ? DAY_FILLED_BLOCKED : DAY_FILLED;
        for (int day = 0; day < totalDays; day++) {
            int color = day < totalDays - daysRemaining ? completeColor : DAY_EMPTY;
            emitQuad(vertices, mat, norm, x, -0.28f, x + cell, -0.24f, 0.0f, 1.0f, color);
            x += cell + gap;
        }
    }

    private static void emitQuad(VertexConsumer vertices, Matrix4f mat, Matrix3f norm,
                                 float left, float bottom, float right, float top,
                                 float uLeft, float uRight, int rgb) {
        emitVertex(vertices, mat, norm, left, bottom, uLeft, 1.0f, rgb);
        emitVertex(vertices, mat, norm, left, top, uLeft, 0.0f, rgb);
        emitVertex(vertices, mat, norm, right, top, uRight, 0.0f, rgb);
        emitVertex(vertices, mat, norm, right, bottom, uRight, 1.0f, rgb);
    }

    private static void emitVertex(VertexConsumer vc, Matrix4f mat, Matrix3f norm,
                                   float x, float y, float u, float v, int rgb) {
        vc.m_252986_(mat, x, y, 0.0f)
                .m_6122_((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, 255)
                .m_7421_(u, v)
                .m_86008_(OverlayTexture.f_118083_)
                .m_85969_(FULL_BRIGHT)
                .m_252939_(norm, 0.0f, 1.0f, 0.0f)
                .m_5752_();
    }

    private static ToolMode detectToolMode(Player player) {
        ToolMode main = modeForStack(player.m_21205_());
        return main != null ? main : modeForStack(player.m_21206_());
    }

    private static ToolMode modeForStack(ItemStack stack) {
        if (stack == null || stack.m_41619_()) return null;
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        if (AnimalStatusSync.MILK_PAIL_ID.equals(id)) return ToolMode.MILK_PAIL;
        if (AnimalStatusSync.MAGIC_SHEARS_ID.equals(id)) return ToolMode.MAGIC_SHEARS;
        if (AnimalStatusSync.GLASS_BOTTLE_ID.equals(id)) return ToolMode.GLASS_BOTTLE;
        return null;
    }

    private static MilkProduceInfo resolveMilkInfo(LivingEntity entity, AnimalStatusSync.AnimalSnapshot snap) {
        ResourceLocation typeId = ForgeRegistries.ENTITY_TYPES.getKey(entity.m_6095_());
        if (typeId != null && AnimalStatusSync.WOOLY_COW_ID.equals(typeId) && snap.isWarped()) {
            return new MilkProduceInfo(ICON_WARPED_MILK, 2);
        }
        MilkProduceInfo info = typeId != null ? MILK_PRODUCE_MAP.get(typeId.toString()) : null;
        return info != null ? info : new MilkProduceInfo(ICON_MILK, 1);
    }

    public static int computeMilkDaysRemaining(AnimalStatusSync.AnimalSnapshot snap, int cooldownDays, long currentDay) {
        long lastMilked = snap.hasMilkedTag() ? snap.ageLastMilked() : currentDay - 1L;
        if (currentDay < lastMilked) return 0;
        return (int) Math.max(0L, lastMilked + cooldownDays - currentDay);
    }

    public static int computeMilkStage(AnimalStatusSync.AnimalSnapshot snap, int cooldownDays,
                                       long dayTime, long currentDay) {
        return milkStage(snap, computeMilkDaysRemaining(snap, cooldownDays, currentDay), dayTime, currentDay);
    }

    private static int milkStage(AnimalStatusSync.AnimalSnapshot snap, int daysRemaining,
                                 long dayTime, long currentDay) {
        // Society considers the animal fed only for the calendar day on which it ate.
        if (!snap.hasFedTag() || currentDay != snap.ageLastFed()) return STAGE_BLOCKED;
        if (daysRemaining == 0) return STAGE_READY;

        long lastMilked = snap.hasMilkedTag() ? snap.ageLastMilked() : currentDay - 1L;
        long readyTick = (currentDay - 1L + daysRemaining) * AnimalStatusSync.TICKS_PER_DAY;
        long startTick = (snap.hasMilkedTag() && snap.timeLastMilked() > 0L && snap.timeLastMilked() < readyTick)
                ? snap.timeLastMilked()
                : (lastMilked - 1L) * AnimalStatusSync.TICKS_PER_DAY;
        return stageFromTicks(dayTime, startTick, readyTick);
    }

    public static int computeMagicStage(AnimalStatusSync.AnimalSnapshot snap, long dayTime, long currentDay) {
        if (snap.hearts() < 5) return STAGE_BLOCKED;
        long effectiveLastMagic = snap.hasMagicTag() ? snap.ageLastMagic() : (currentDay - 1L);
        if (currentDay < effectiveLastMagic || currentDay > effectiveLastMagic) {
            return STAGE_READY;
        }
        long readyTick = effectiveLastMagic * AnimalStatusSync.TICKS_PER_DAY;
        long startTick = (snap.hasMagicTag() && snap.timeLastMagic() > 0L && snap.timeLastMagic() < readyTick)
                ? snap.timeLastMagic()
                : (effectiveLastMagic - 1L) * AnimalStatusSync.TICKS_PER_DAY;
        return stageFromTicks(dayTime, startTick, readyTick);
    }

    private static int stageFromTicks(long dayTime, long startTick, long readyTick) {
        long total = Math.max(1L, readyTick - startTick);
        double ratio = Math.max(0.0D, Math.min(0.9999D, (dayTime - startTick) / (double) total));
        if (ratio >= 2.0D / 3.0D) return STAGE_TWO_THIRDS;
        if (ratio >= 1.0D / 3.0D) return STAGE_ONE_THIRD;
        return STAGE_GRAY;
    }

    private static IconStrip getOrBuildStrip(Minecraft mc, ResourceLocation itemId) {
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null) return null;
        ItemStack stack = new ItemStack(item);
        if (stack.m_41619_()) return null;

        TextureAtlasSprite sprite = mc.m_91291_().m_174264_(stack, null, null, 0).m_6160_();
        IconStrip existing = STRIP_CACHE.get(itemId);
        if (existing != null && existing.sourceSprite == sprite) {
            return existing;
        }

        IconStrip built = buildStrip(mc, itemId, sprite);
        if (built != null) {
            STRIP_CACHE.put(itemId, built);
        }
        return built;
    }

    private static void ensureWhiteTexture(Minecraft mc) {
        Object rm = mc.m_91098_();
        if (whiteTextureResourceManager == rm) return;
        NativeImage img = new NativeImage(1, 1, true);
        img.m_84988_(0, 0, 0xFFFFFFFF);
        DynamicTexture dyn = new DynamicTexture(img);
        dyn.m_117960_(false, false);
        mc.m_91097_().m_118495_(WHITE_TEXTURE, dyn);
        FAILED_STRIP_LOGS.clear();
        whiteTextureResourceManager = rm;
    }

    private static IconStrip buildStrip(Minecraft mc, ResourceLocation itemId, TextureAtlasSprite sprite) {
        SpriteContents contents = sprite.m_245424_();
        ResourceLocation spriteName = contents.m_246162_();
        int w = Math.max(1, contents.m_246492_());
        int h = Math.max(1, contents.m_245330_());
        ResourceLocation pngLoc = new ResourceLocation(
                spriteName.m_135827_(),
                "textures/" + spriteName.m_135815_() + ".png"
        );

        Optional<Resource> resOpt = mc.m_91098_().m_213713_(pngLoc);
        if (resOpt.isEmpty()) {
            if (FAILED_STRIP_LOGS.add(itemId)) {
                SunlitFishVisuals.LOG.warn("AnimalCooldownRenderer: missing icon texture {} for {}", pngLoc, itemId);
            }
            return null;
        }

        try (InputStream stream = resOpt.get().m_215507_();
             NativeImage src = NativeImage.m_85058_(stream)) {
            w = Math.min(w, src.m_84982_());
            h = Math.min(h, src.m_85084_());
            int minY = h;
            int maxY = -1;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int alpha = (src.m_84985_(x, y) >>> 24) & 0xFF;
                    if (alpha > 16) {
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxY < minY) {
                minY = 0;
                maxY = h - 1;
            }
            int visH = maxY - minY + 1;

            NativeImage strip = new NativeImage(w * FRAME_COUNT, h, true);
            for (int stage = 0; stage < FRAME_COUNT; stage++) {
                int colorTopY;
                if (stage == STAGE_READY) {
                    colorTopY = minY;
                } else if (stage == STAGE_TWO_THIRDS) {
                    colorTopY = maxY - Math.max(1, (int) Math.round(visH * (2.0D / 3.0D))) + 1;
                } else if (stage == STAGE_ONE_THIRD) {
                    colorTopY = maxY - Math.max(1, (int) Math.round(visH / 3.0D)) + 1;
                } else {
                    colorTopY = maxY + 1;
                }

                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int abgr = src.m_84985_(x, y);
                        int a = (abgr >>> 24) & 0xFF;
                        if (a == 0) {
                            strip.m_84988_(stage * w + x, y, 0);
                            continue;
                        }
                        if (stage != STAGE_BLOCKED && y >= colorTopY) {
                            strip.m_84988_(stage * w + x, y, abgr);
                            continue;
                        }
                        int r = abgr & 0xFF;
                        int g = (abgr >>> 8) & 0xFF;
                        int b = (abgr >>> 16) & 0xFF;
                        int lum = (int) Math.round(0.299D * r + 0.587D * g + 0.114D * b);
                        int nr;
                        int ng;
                        int nb;
                        int na;
                        if (stage == STAGE_BLOCKED) {
                            nr = clampByte(Math.round(lum * 0.52f) + 28);
                            ng = clampByte(Math.round(lum * 0.28f));
                            nb = clampByte(Math.round(lum * 0.28f));
                            na = clampByte(Math.round(a * 0.82f));
                        } else {
                            // Darkened cool slate gray ensures pure-white milk items stay clearly distinct from full color.
                            int gray = Math.max(24, Math.round(lum * 0.43f));
                            nr = clampByte(gray - 4);
                            ng = clampByte(gray);
                            nb = clampByte(gray + 6);
                            na = clampByte(Math.round(a * 0.85f));
                        }
                        int outAbgr = (na << 24) | (nb << 16) | (ng << 8) | nr;
                        strip.m_84988_(stage * w + x, y, outAbgr);
                    }
                }
            }

            DynamicTexture dynTex = new DynamicTexture(strip);
            dynTex.m_117960_(false, false);
            String cleanPath = "dynamic/animal_icon/" + itemId.m_135827_() + "/" + itemId.m_135815_();
            ResourceLocation texLoc = new ResourceLocation(SunlitFishVisuals.ID, cleanPath);
            mc.m_91097_().m_118495_(texLoc, dynTex);
            return new IconStrip(sprite, texLoc);
        } catch (Exception e) {
            if (FAILED_STRIP_LOGS.add(itemId)) {
                SunlitFishVisuals.LOG.warn("AnimalCooldownRenderer: failed building icon strip for {} ({})", itemId, pngLoc, e);
            }
            return null;
        }
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private record IconStrip(TextureAtlasSprite sourceSprite, ResourceLocation textureLoc) {}
}
