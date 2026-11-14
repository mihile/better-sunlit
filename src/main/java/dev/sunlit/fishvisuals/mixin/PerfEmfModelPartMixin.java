package dev.sunlit.fishvisuals.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.sunlit.bettersunlit.client.EmfRenderPerf;
import net.minecraft.client.model.geom.ModelPart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Propagates Embeddium pose-stack matrix caching across {@code EMFModelPart.renderLikeVanilla},
 * caches child {@code ModelPart[]} arrays to avoid {@code HashMap.values()} iterator allocations,
 * and replaces {@code m_104290_} cube iteration with indexed traversal.
 */
@Pseudo
@Mixin(targets = "traben.entity_model_features.models.parts.EMFModelPart", priority = 2000, remap = false)
public abstract class PerfEmfModelPartMixin {

    @Unique
    private Map<String, ModelPart> sunlit$lastChildrenMap;

    @Unique
    private int sunlit$lastChildrenSize = -1;

    @Unique
    private ModelPart[] sunlit$childrenArray = EmfRenderPerf.EMPTY_MODEL_PARTS;

    /**
     * Rewritten in {@code ClientPostApplyPatches.patchEmfModelPart} to read {@code this.f_104212_}
     * ({@code ModelPart.cubes}) directly without allocating a per-call iterator.
     */
    @Unique
    public List<ModelPart.Cube> sunlit$cubes() {
        return Collections.emptyList();
    }

    /**
     * Rewritten in {@code ClientPostApplyPatches.patchEmfModelPart} to read {@code this.f_104213_}
     * ({@code ModelPart.children}) directly.
     */
    @Unique
    public Map<String, ModelPart> sunlit$children() {
        return Collections.emptyMap();
    }

    @Unique
    public ModelPart[] sunlit$getChildrenArray(Map<String, ModelPart> children) {
        if (children == null || children.isEmpty()) {
            this.sunlit$lastChildrenMap = children;
            this.sunlit$lastChildrenSize = 0;
            this.sunlit$childrenArray = EmfRenderPerf.EMPTY_MODEL_PARTS;
            return EmfRenderPerf.EMPTY_MODEL_PARTS;
        }
        int size = children.size();
        if (children != this.sunlit$lastChildrenMap
                || size != this.sunlit$lastChildrenSize
                || this.sunlit$childrenArray == null) {
            this.sunlit$childrenArray = children.values().toArray(new ModelPart[size]);
            this.sunlit$lastChildrenMap = children;
            this.sunlit$lastChildrenSize = size;
        }
        return this.sunlit$childrenArray;
    }

    /**
     * @author better-sunlit
     * @reason Use PoseStack matrix caching and cached child ModelPart[] traversal without HashMap iterator allocations
     */
    @Overwrite(remap = false)
    void renderLikeVanilla(PoseStack poseStack, VertexConsumer vertexConsumer,
                           int packedLight, int packedOverlay,
                           float red, float green, float blue, float alpha) {
        ModelPart self = (ModelPart) (Object) this;
        if (!self.f_104207_) {
            return;
        }
        List<ModelPart.Cube> cubes = this.sunlit$cubes();
        Map<String, ModelPart> children = this.sunlit$children();
        if (cubes.isEmpty() && children.isEmpty()) {
            return;
        }
        EmfRenderPerf.pushWithPoseCaching(poseStack);
        self.m_104299_(poseStack);
        if (!self.f_233556_) {
            this.m_104290_(poseStack.m_85850_(), vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        }
        ModelPart[] childArray = this.sunlit$getChildrenArray(children);
        for (int i = 0, n = childArray.length; i < n; i++) {
            childArray[i].m_104306_(poseStack, vertexConsumer, packedLight, packedOverlay, red, green, blue, alpha);
        }
        EmfRenderPerf.popWithPoseCaching(poseStack);
    }

    @Overwrite
    public void m_104290_(PoseStack.Pose pose, VertexConsumer consumer,
                          int packedLight, int packedOverlay,
                          float red, float green, float blue, float alpha) {
        List<ModelPart.Cube> cubes = this.sunlit$cubes();
        for (int i = 0, n = cubes.size(); i < n; i++) {
            cubes.get(i).m_171332_(pose, consumer, packedLight, packedOverlay, red, green, blue, alpha);
        }
    }
}
