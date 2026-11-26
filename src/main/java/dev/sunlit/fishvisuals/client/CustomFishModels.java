package dev.sunlit.fishvisuals.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Set;

/**
 * Client-only 3D models for caught fish/critters that do not have their own world EntityType
 * (Aquaculture Goldfish & Leech, Society Neptuna).
 */
public final class CustomFishModels {
    private static final ResourceLocation GOLDFISH_ID = new ResourceLocation("aquaculture", "goldfish");
    private static final ResourceLocation NEPTUNA_ID = new ResourceLocation("society", "neptuna");
    private static final ResourceLocation LEECH_ID = new ResourceLocation("aquaculture", "leech");
    // Single-sided face sets for 0-thickness cutout fins prevent coplanar Z-fighting under shaders + FishMeshBend.
    private static final Set<Direction> FIN_VERTICAL_FACE = Set.of(Direction.EAST);
    private static final Set<Direction> FIN_HORIZONTAL_FACE = Set.of(Direction.DOWN);

    private static final Map<ResourceLocation, Entry> ENTRIES = Map.of(
            GOLDFISH_ID, new Entry(
                    new ResourceLocation("better_sunlit", "textures/entity/fish/goldfish.png"),
                    0.90F, 0.45F, true, 0.125F, 0.065F, CustomFishModels::createGoldfishLayer),
            NEPTUNA_ID, new Entry(
                    new ResourceLocation("better_sunlit", "textures/entity/fish/neptuna.png"),
                    1.00F, 0.90F, true, 0.125F, 0.065F, CustomFishModels::createNeptunaLayer),
            LEECH_ID, new Entry(
                    new ResourceLocation("better_sunlit", "textures/entity/fish/leech.png"),
                    0.88F, 0.50F, false, 0.0F, 0.0F, CustomFishModels::createLeechLayer)
    );

    private CustomFishModels() {}

    public static boolean has(ResourceLocation id) {
        return id != null && ENTRIES.containsKey(id);
    }

    public static float size(ResourceLocation id) {
        Entry entry = ENTRIES.get(id);
        return entry == null ? 0.5F : entry.flopSize;
    }

    public static ResourceLocation texture(ResourceLocation id) {
        Entry entry = ENTRIES.get(id);
        return entry == null ? null : entry.texture;
    }

    public static ModelPart bake(ResourceLocation id) {
        Entry entry = ENTRIES.get(id);
        return entry == null ? null : entry.root();
    }

    public static void render(ResourceLocation id, float yaw, PoseStack pose,
                              MultiBufferSource buffers, int light) {
        Entry entry = ENTRIES.get(id);
        if (entry == null) return;
        pose.m_85836_();
        try {
            // Match LivingEntityRenderer + AquaFishRenderer orientation so FishMeshBend bends head->tail identically.
            pose.m_252781_(Axis.f_252436_.m_252977_(180.0F - yaw));
            if (entry.lieOnSide) {
                pose.m_252880_(entry.sideOffsetX * entry.scale, entry.sideOffsetY * entry.scale, 0.0F);
                pose.m_252781_(Axis.f_252403_.m_252977_(90.0F));
            }
            pose.m_85841_(-entry.scale, -entry.scale, entry.scale);
            pose.m_252880_(0.0F, -1.501F, 0.0F);
            VertexConsumer consumer = buffers.m_6299_(RenderType.m_110458_(entry.texture));
            entry.root().m_104306_(pose, consumer, light, OverlayTexture.f_118083_, 1.0F, 1.0F, 1.0F, 1.0F);
        } finally {
            pose.m_85849_();
        }
    }

    /** Renders a custom fish/critter upright and smoothly swimming underwater (for fish ponds). */
    public static void renderSwimming(ResourceLocation id, float yaw, float pitch, float ageTicks,
                                      float scaleMul, PoseStack pose, MultiBufferSource buffers, int light) {
        renderSwimming(id, yaw, pitch, ageTicks, scaleMul, 0.0F, pose, buffers, light);
    }

    /** Renders a custom fish/critter swimming underwater with natural C-curve turn bending. */
    public static void renderSwimming(ResourceLocation id, float yaw, float pitch, float ageTicks,
                                      float scaleMul, float turnBend, PoseStack pose, MultiBufferSource buffers, int light) {
        Entry entry = ENTRIES.get(id);
        if (entry == null) return;
        float finalScale = entry.scale * scaleMul;
        float wag = (float) Math.sin(ageTicks * 0.32F);
        pose.m_85836_();
        try {
            FishMeshBend turnMesh = !LEECH_ID.equals(id) && Math.abs(turnBend) > 0.025F
                    ? new FishMeshBend(buffers, pose.m_85850_().m_252922_(), yaw)
                    : null;
            MultiBufferSource targetBuffers = turnMesh == null ? buffers : turnMesh;
            pose.m_252781_(Axis.f_252436_.m_252977_(180.0F - yaw + wag * (entry.lieOnSide ? 4.0F : 3.0F)));
            if (pitch != 0.0F) {
                pose.m_252781_(Axis.f_252529_.m_252977_(pitch));
            }
            pose.m_85841_(-finalScale, -finalScale, finalScale);
            // Center the fish vertically around the pond swim coordinate (model Y ~ 22 for fish, 23 for leech)
            float centerOffsetY = entry.lieOnSide ? -1.375F : -1.44F;
            pose.m_252880_(0.0F, centerOffsetY, 0.0F);

            ModelPart root = entry.root();
            if (GOLDFISH_ID.equals(id)) {
                ModelPart tl = root.m_171324_("tail_left");
                ModelPart tr = root.m_171324_("tail_right");
                float oldL = tl.f_104204_, oldR = tr.f_104204_;
                tl.f_104204_ = oldL + wag * 0.28F - turnBend * 0.32F;
                tr.f_104204_ = oldR + wag * 0.28F - turnBend * 0.32F;
                try {
                    VertexConsumer consumer = targetBuffers.m_6299_(RenderType.m_110458_(entry.texture));
                    root.m_104306_(pose, consumer, light, OverlayTexture.f_118083_, 1.0F, 1.0F, 1.0F, 1.0F);
                    if (turnMesh != null) turnMesh.draw(FishPondVisuals.turnCurve(turnBend));
                } finally {
                    tl.f_104204_ = oldL;
                    tr.f_104204_ = oldR;
                }
            } else if (NEPTUNA_ID.equals(id)) {
                ModelPart tail = root.m_171324_("tail");
                float oldTail = tail.f_104204_;
                tail.f_104204_ = oldTail + wag * 0.34F - turnBend * 0.35F;
                try {
                    VertexConsumer consumer = targetBuffers.m_6299_(RenderType.m_110458_(entry.texture));
                    root.m_104306_(pose, consumer, light, OverlayTexture.f_118083_, 1.0F, 1.0F, 1.0F, 1.0F);
                    if (turnMesh != null) turnMesh.draw(FishPondVisuals.turnCurve(turnBend));
                } finally {
                    tail.f_104204_ = oldTail;
                }
            } else {
                // Leech glides with a gentle serpentine underwater wave + turn curvature via FishMeshBend.
                FishFlopMotion turn = FishPondVisuals.turnCurve(turnBend);
                FishFlopMotion swimWave = new FishFlopMotion(
                        (float) Math.sin(ageTicks * 0.28F) * 0.14F + turn.front(),
                        (float) Math.sin(ageTicks * 0.28F - 0.9F) * 0.24F + turn.rear(),
                        (float) Math.sin(ageTicks * 0.28F - 1.8F) * 0.34F + turn.tail(),
                        (float) Math.sin(ageTicks * 0.28F - 2.6F) * 0.42F + turn.tip()
                );
                FishMeshBend bend = new FishMeshBend(buffers, pose.m_85850_().m_252922_(), yaw);
                VertexConsumer consumer = bend.m_6299_(RenderType.m_110458_(entry.texture));
                root.m_104306_(pose, consumer, light, OverlayTexture.f_118083_, 1.0F, 1.0F, 1.0F, 1.0F);
                bend.draw(swimWave);
            }
        } finally {
            pose.m_85849_();
        }
    }

    public static LayerDefinition createGoldfishLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.m_171576_();
        root.m_171599_("body",
                CubeListBuilder.m_171558_().m_171514_(0, 0).m_171481_(-1.0F, -2.0F, -2.0F, 2.0F, 4.0F, 5.0F),
                PartPose.m_171419_(0.0F, 22.0F, 0.0F));
        root.m_171599_("head_front",
                CubeListBuilder.m_171558_().m_171514_(0, 10).m_171481_(-1.0F, -1.5F, -3.0F, 2.0F, 3.0F, 1.0F),
                PartPose.m_171419_(0.0F, 22.0F, 0.0F));
        root.m_171599_("fin_top",
                CubeListBuilder.m_171558_().m_171514_(16, 6).m_271786_(0.0F, -3.0F, -2.0F, 0.0F, 3.0F, 5.0F, FIN_VERTICAL_FACE),
                PartPose.m_171419_(0.0F, 20.0F, 0.0F));
        root.m_171599_("fin_bottom",
                CubeListBuilder.m_171558_().m_171514_(16, 11).m_271786_(0.0F, 0.0F, -1.0F, 0.0F, 2.0F, 4.0F, FIN_VERTICAL_FACE),
                PartPose.m_171419_(0.0F, 24.0F, 0.0F));
        root.m_171599_("fin_left",
                CubeListBuilder.m_171558_().m_171514_(0, 16).m_271786_(0.0F, 0.0F, -1.0F, 2.0F, 0.0F, 2.0F, FIN_HORIZONTAL_FACE),
                PartPose.m_171423_(1.0F, 23.5F, -0.5F, 0.0F, 0.0F, 0.7853982F));
        root.m_171599_("fin_right",
                CubeListBuilder.m_171558_().m_171514_(6, 16).m_271786_(-2.0F, 0.0F, -1.0F, 2.0F, 0.0F, 2.0F, FIN_HORIZONTAL_FACE),
                PartPose.m_171423_(-1.0F, 23.5F, -0.5F, 0.0F, 0.0F, -0.7853982F));
        root.m_171599_("tail_left",
                CubeListBuilder.m_171558_().m_171514_(16, 0).m_271786_(0.0F, -2.5F, 0.0F, 0.0F, 5.0F, 5.0F, FIN_VERTICAL_FACE),
                PartPose.m_171423_(0.0F, 22.0F, 3.0F, 0.0F, 0.19F, 0.0F));
        root.m_171599_("tail_right",
                CubeListBuilder.m_171558_().m_171514_(16, 0).m_271786_(0.0F, -2.5F, 0.0F, 0.0F, 5.0F, 5.0F, FIN_VERTICAL_FACE),
                PartPose.m_171423_(0.0F, 22.0F, 3.0F, 0.0F, -0.19F, 0.0F));
        return LayerDefinition.m_171565_(mesh, 32, 32);
    }

    public static LayerDefinition createNeptunaLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.m_171576_();
        // Matches Aquaculture FishLargeModel (tuna) 2px-wide proportions.
        root.m_171599_("body",
                CubeListBuilder.m_171558_().m_171514_(0, 0).m_171481_(-1.0F, -2.0F, 0.0F, 2.0F, 5.0F, 7.0F),
                PartPose.m_171419_(0.0F, 22.0F, 0.0F));
        root.m_171599_("head",
                CubeListBuilder.m_171558_().m_171514_(0, 12).m_171481_(-1.0F, -2.0F, -3.0F, 2.0F, 4.0F, 3.0F),
                PartPose.m_171419_(0.0F, 22.0F, 0.0F));
        root.m_171599_("head_front",
                CubeListBuilder.m_171558_().m_171514_(11, 12).m_171481_(-1.0F, -2.0F, -1.0F, 2.0F, 3.0F, 1.0F),
                PartPose.m_171419_(0.0F, 22.0F, -3.0F));
        root.m_171599_("tail",
                CubeListBuilder.m_171558_().m_171514_(20, 0).m_271786_(0.0F, -2.0F, 0.0F, 0.0F, 5.0F, 5.0F, FIN_VERTICAL_FACE),
                PartPose.m_171419_(0.0F, 21.5F, 7.0F));
        root.m_171599_("fin_top",
                CubeListBuilder.m_171558_().m_171514_(0, 15).m_271786_(0.0F, -2.0F, 0.0F, 0.0F, 3.0F, 7.0F, FIN_VERTICAL_FACE),
                PartPose.m_171419_(0.0F, 19.0F, 0.0F));
        root.m_171599_("fin_bottom",
                CubeListBuilder.m_171558_().m_171514_(0, 20).m_271786_(0.0F, -1.0F, 0.0F, 0.0F, 2.0F, 6.0F, FIN_VERTICAL_FACE),
                PartPose.m_171419_(0.0F, 26.0F, 0.0F));
        root.m_171599_("fin_left",
                CubeListBuilder.m_171558_().m_171514_(20, 12).m_271786_(0.0F, 0.0F, -1.0F, 2.0F, 0.0F, 2.0F, FIN_HORIZONTAL_FACE),
                PartPose.m_171423_(1.0F, 24.0F, 0.0F, 0.0F, 0.0F, 0.7853982F));
        root.m_171599_("fin_right",
                CubeListBuilder.m_171558_().m_171514_(20, 15).m_271786_(-2.0F, 0.0F, -1.0F, 2.0F, 0.0F, 2.0F, FIN_HORIZONTAL_FACE),
                PartPose.m_171423_(-1.0F, 24.0F, 0.0F, 0.0F, 0.0F, -0.7853982F));
        return LayerDefinition.m_171565_(mesh, 32, 32);
    }

    public static LayerDefinition createLeechLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.m_171576_();
        root.m_171599_("head_sucker",
                CubeListBuilder.m_171558_().m_171514_(15, 0).m_171481_(-1.0F, 23.0F, -5.0F, 2.0F, 1.0F, 2.0F),
                PartPose.m_171419_(0.0F, 0.0F, 0.0F));
        root.m_171599_("front_body",
                CubeListBuilder.m_171558_().m_171514_(0, 0).m_171481_(-1.0F, 22.0F, -3.0F, 2.0F, 2.0F, 4.0F),
                PartPose.m_171419_(0.0F, 0.0F, 0.0F));
        root.m_171599_("rear_body",
                CubeListBuilder.m_171558_().m_171514_(0, 7).m_171481_(-1.0F, 23.0F, 1.0F, 2.0F, 1.0F, 3.0F),
                PartPose.m_171419_(0.0F, 0.0F, 0.0F));
        root.m_171599_("tail",
                CubeListBuilder.m_171558_().m_171514_(15, 5).m_171481_(-0.5F, 23.0F, 4.0F, 1.0F, 1.0F, 2.0F),
                PartPose.m_171419_(0.0F, 0.0F, 0.0F));
        return LayerDefinition.m_171565_(mesh, 32, 32);
    }

    private static final class Entry {
        final ResourceLocation texture;
        final float scale;
        final float flopSize;
        final boolean lieOnSide;
        final float sideOffsetX;
        final float sideOffsetY;
        final java.util.function.Supplier<LayerDefinition> layerSupplier;
        private ModelPart baked;

        Entry(ResourceLocation texture, float scale, float flopSize, boolean lieOnSide,
              float sideOffsetX, float sideOffsetY, java.util.function.Supplier<LayerDefinition> layerSupplier) {
            this.texture = texture;
            this.scale = scale;
            this.flopSize = flopSize;
            this.lieOnSide = lieOnSide;
            this.sideOffsetX = sideOffsetX;
            this.sideOffsetY = sideOffsetY;
            this.layerSupplier = layerSupplier;
        }

        ModelPart root() {
            ModelPart part = baked;
            if (part == null) {
                part = layerSupplier.get().m_171564_();
                baked = part;
            }
            return part;
        }
    }
}
