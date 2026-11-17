package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ToastTrace;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Diagnostic entry point: records every queued toast together with its originating mod. */
@Mixin(value = ToastComponent.class, priority = 2000, remap = false)
public abstract class DebugToastTraceMixin {
    @Inject(method = "m_94922_(Lnet/minecraft/client/gui/components/toasts/Toast;)V", at = @At("HEAD"), require = 0)
    private void sunlit$traceToast(Toast toast, CallbackInfo ci) {
        ToastTrace.log(toast);
    }
}
