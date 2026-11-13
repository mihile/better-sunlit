package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ClientLocalizer;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = DisplayInfo.class, remap = false)
public abstract class DisplayInfoMixin {
    @Inject(method = "m_14977_()Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private void sunlit$localizeTitle(CallbackInfoReturnable<Component> cir) {
        Component localized = ClientLocalizer.localizeAdvancementComponent(cir.getReturnValue());
        if (localized != null) {
            cir.setReturnValue(localized);
        }
    }

    @Inject(method = "m_14985_()Lnet/minecraft/network/chat/Component;",
            at = @At("RETURN"), cancellable = true, require = 1, remap = false)
    private void sunlit$localizeDescription(CallbackInfoReturnable<Component> cir) {
        Component localized = ClientLocalizer.localizeAdvancementComponent(cir.getReturnValue());
        if (localized != null) {
            cir.setReturnValue(localized);
        }
    }
}
