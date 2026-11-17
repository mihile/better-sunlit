package dev.sunlit.fishvisuals.mixin;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.program.GlUniform1iCall;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.sampler.SamplerBinding;
import net.irisshaders.iris.gl.state.ValueUpdateNotifier;
import net.irisshaders.iris.mixin.GlStateManagerAccessor;
import org.spongepowered.asm.mixin.*;
import java.util.List;

@Mixin(value = ProgramSamplers.class, remap = false)
public abstract class PerfOculusSamplerLoopsMixin {
    @Shadow private static ProgramSamplers active;
    @Shadow @Final private ImmutableList<SamplerBinding> samplerBindings;
    @Shadow @Final private ImmutableList<ValueUpdateNotifier> notifiersToReset;
    @Shadow private List<GlUniform1iCall> initializer;
    /** @author Better Sunlit @reason Preserve listener reset order without iterator allocation. */
    @Overwrite public void removeListeners() {
        active = null;
        for (int i = 0; i < notifiersToReset.size(); i++) notifiersToReset.get(i).setListener(null);
    }

    /** @author Better Sunlit @reason Port indexed sampler traversal while preserving initial uniforms and active texture. */
    @Overwrite public void update() {
        if (active != null) active.removeListeners();
        active = (ProgramSamplers)(Object)this;
        if (initializer != null) {
            for (int i = 0; i < initializer.size(); i++) {
                GlUniform1iCall call = initializer.get(i);
                RenderSystem.glUniform1i(call.location(), call.value());
            }
            initializer = null;
        }
        int texture = GlStateManagerAccessor.getActiveTexture();
        for (int i = 0; i < samplerBindings.size(); i++) samplerBindings.get(i).update();
        RenderSystem.activeTexture(33984 + texture);
    }
}
