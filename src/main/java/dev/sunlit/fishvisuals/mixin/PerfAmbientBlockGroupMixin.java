package dev.sunlit.fishvisuals.mixin;

import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import team.creative.ambientsounds.block.AmbientBlock;

@Pseudo
@Mixin(targets = "team.creative.ambientsounds.block.AmbientBlockGroup", priority = 2000, remap = false)
public abstract class PerfAmbientBlockGroupMixin {
    @Shadow
    private List<AmbientBlock> filters;

    @Overwrite(remap = false)
    public boolean is(BlockState state) {
        List<AmbientBlock> list = this.filters;
        if (list == null) {
            return false;
        }
        for (int i = 0, n = list.size(); i < n; i++) {
            if (list.get(i).is(state)) {
                return true;
            }
        }
        return false;
    }
}
