package dev.sunlit.fishvisuals.mixin;

import dev.latvian.mods.kubejs.script.ScriptFileInfo;
import dev.sunlit.bettersunlit.inline.InlineScripts;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="dev.latvian.mods.kubejs.script.ScriptFile",remap=false)
public abstract class InlineScriptSourceMixin {
    @Shadow @Final public ScriptFileInfo info;
    @Inject(method="load()V",at=@At("HEAD"),remap=false)
    private void sunlit$localizeDisplayLiterals(CallbackInfo ci) {
        this.info.lines=InlineScripts.apply(this.info.location,this.info.lines);
    }
}
