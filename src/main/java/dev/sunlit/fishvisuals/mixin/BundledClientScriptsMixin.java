package dev.sunlit.fishvisuals.mixin;

import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.sunlit.bettersunlit.client.BundledClientScripts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.latvian.mods.kubejs.script.ScriptManager", remap = false)
public abstract class BundledClientScriptsMixin {
    @Inject(method = "load()V", at = @At("HEAD"))
    private void sunlit$addRecipeCards(CallbackInfo ci) {
        BundledClientScripts.install((ScriptManager) (Object) this);
    }
}
