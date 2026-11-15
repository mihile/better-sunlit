package dev.sunlit.fishvisuals.mixin;

import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Skips the synchronous filesystem walk in ClientLoginHandler.onPlayerLoggedIn whose return value
 * is immediately discarded with a POP instruction; category icons are already loaded at startup.
 */
@Pseudo
@Mixin(targets = "com.github.spacemex.skillsexpnotifier.client.ClientLoginHandler", remap = false)
public abstract class PerfSkillsLoginScanMixin {
    @Inject(
            method = "onPlayerLoggedIn(Lnet/minecraftforge/client/event/ClientPlayerNetworkEvent$LoggingIn;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private static void sunlit$skipDeadLoginScan(ClientPlayerNetworkEvent.LoggingIn event, CallbackInfo ci) {
        ci.cancel();
    }
}
