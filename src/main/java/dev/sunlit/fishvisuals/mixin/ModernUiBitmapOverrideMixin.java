package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ModernUiPackFonts;
import icyllis.modernui.mc.text.BitmapFont;
import net.minecraft.client.gui.font.providers.BitmapProvider;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "icyllis.modernui.mc.text.BitmapFont", remap = false)
public abstract class ModernUiBitmapOverrideMixin implements ModernUiPackFonts.BitmapOverride {
    @Unique private boolean sunlit$packOverride;

    @Inject(method = "create(Lnet/minecraft/client/gui/font/providers/BitmapProvider$Definition;Lnet/minecraft/server/packs/resources/ResourceManager;)Licyllis/modernui/mc/text/BitmapFont;",
            at = @At("RETURN"), remap = false)
    private static void sunlit$trackPackOverride(BitmapProvider.Definition definition, ResourceManager resources,
                                                CallbackInfoReturnable<BitmapFont> cir) {
        ((ModernUiBitmapOverrideMixin) (Object) cir.getReturnValue()).sunlit$packOverride =
                ModernUiPackFonts.hasPackOverride(resources, definition.f_285631_());
    }

    @Override
    public boolean sunlit$isPackOverride() {
        return sunlit$packOverride;
    }
}
