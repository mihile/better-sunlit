package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sunlit.bettersunlit.client.EmfRenderPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Replaces per-polygon/vertex JOML vector allocations in {@code EMFCube.compile}.
 * Priority 900 applies before downstream parameter modifiers (e.g., ShoulderSurfing @ModifyVariable).
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.parts.EMFModelPartCustom$EMFCube", priority = 900, remap = false)
public abstract class PerfEmfCubeMixin {

    /**
     * @author Better Sunlit
     * @reason Eliminate Vector3f/Vector4f allocations per polygon and vertex
     */
    @Overwrite
    public void m_171332_(
            PoseStack.Pose pose,
            VertexConsumer consumer,
            int packedLight,
            int packedOverlay,
            float red,
            float green,
            float blue,
            float alpha) {
        EmfRenderPerf.compileCube(
                (EmfRenderPerf.PolygonHolder) (Object) this,
                pose,
                consumer,
                packedLight,
                packedOverlay,
                red,
                green,
                blue,
                alpha);
    }
}
