package dev.sunlit.fishvisuals.mixin;

import com.google.common.collect.ImmutableList;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.gl.uniform.Uniform;
import org.spongepowered.asm.mixin.*;

@Mixin(value = ProgramUniforms.class, remap = false)
public abstract class PerfOculusUniformLoopsMixin {
    @Shadow private static ProgramUniforms active;
    @Shadow @Final private ImmutableList<ValueUpdateNotifier> notifiersToReset;

    /** @author Better Sunlit @reason Avoid a per-stage immutable-list iterator; retain update order. */
    @Overwrite private void updateStage(ImmutableList<Uniform> uniforms) {
        for (int i = 0; i < uniforms.size(); i++) uniforms.get(i).update();
    }
    /** @author Better Sunlit @reason Retain active reset and listener order without an iterator. */
    @Overwrite public void removeListeners() {
        active = null;
        for (int i = 0; i < notifiersToReset.size(); i++) notifiersToReset.get(i).setListener(null);
    }
}
