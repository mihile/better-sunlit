package dev.sunlit.fishvisuals.mixin;

import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptPack;
import dev.latvian.mods.rhino.ClassShutter;
import dev.latvian.mods.rhino.Context;
import dev.sunlit.bettersunlit.perf.KubeJsScriptPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Delegates parallel AST parsing and interpreter compilation to {@link KubeJsScriptPerf} before
 * sequential pack execution inside {@link ScriptManager#load()}.
 */
@Mixin(value = ScriptManager.class, remap = false)
public abstract class PerfKubeJsScriptManagerMixin implements ClassShutter {
    @Shadow
    public Map<String, ScriptPack> packs;

    @Shadow
    public Context context;

    @Inject(
            method = "load()V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Map;values()Ljava/util/Collection;",
                    ordinal = 0,
                    shift = At.Shift.BEFORE))
    private void sunlit$parallelCompileScripts(CallbackInfo ci) {
        KubeJsScriptPerf.parallelCompile(this.context, this.packs, this);
    }

    @Inject(method = "load()V", at = @At("RETURN"))
    private void sunlit$clearCompiledScripts(CallbackInfo ci) {
        KubeJsScriptPerf.clearCompiled();
    }
}
