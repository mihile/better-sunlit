package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sunlit.bettersunlit.client.EmfRenderPerf;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

/**
 * Replaces per-polygon/vertex JOML vector allocations in {@code ModelPart$Cube.compile}.
 * Priority 900 applies before downstream parameter modifiers (e.g., ShoulderSurfing @ModifyVariable).
 * Paired with {@code ClientPostApplyPatches.patchModelPartCube} to wire {@link EmfRenderPerf.PolygonHolder}.
 */
@Mixin(value = ModelPart.Cube.class, priority = 900, remap = false)
public abstract class PerfModelPartCubeMixin implements EmfRenderPerf.PolygonHolder {

    @Override
    @Unique
    public Object[] sunlit$polygons() {
        throw new AssertionError("Patched in postApply");
    }

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
        EmfRenderPerf.compilePolygons(
                this.sunlit$polygons(),
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
