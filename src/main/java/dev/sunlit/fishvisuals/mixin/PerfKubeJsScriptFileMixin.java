package dev.sunlit.fishvisuals.mixin;

import dev.latvian.mods.kubejs.script.ScriptFile;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Script;
import dev.latvian.mods.rhino.Scriptable;
import dev.sunlit.bettersunlit.perf.KubeJsScriptPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Executes pre-compiled {@link Script} instances generated in parallel by
 * {@link KubeJsScriptPerf} directly without re-parsing JS source.
 */
@Mixin(value = ScriptFile.class, remap = false)
public abstract class PerfKubeJsScriptFileMixin {
    @Redirect(
            method = "load()V",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/latvian/mods/rhino/Context;evaluateString(Ldev/latvian/mods/rhino/Scriptable;Ljava/lang/String;Ljava/lang/String;ILjava/lang/Object;)Ljava/lang/Object;"))
    private Object sunlit$execPreCompiled(
            Context cx,
            Scriptable scope,
            String source,
            String sourceName,
            int lineno,
            Object securityDomain) throws Throwable {
        Object pre = KubeJsScriptPerf.takeCompiled((ScriptFile) (Object) this);
        if (pre instanceof Script s) {
            return s.exec(cx, scope);
        }
        if (pre instanceof Throwable t) {
            throw t;
        }
        return cx.evaluateString(scope, source, sourceName, lineno, securityDomain);
    }
}
