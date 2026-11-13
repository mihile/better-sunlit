package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.IrisDimensionPerf;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.Iris", remap = false)
public abstract class PerfIrisDimensionMixin {
    @Shadow
    public static NamespacedId lastDimension;

    @Overwrite(remap = false)
    public static NamespacedId getCurrentDimension() {
        return IrisDimensionPerf.getCurrentDimension(lastDimension);
    }
}
