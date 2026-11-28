package dev.sunlit.fishvisuals.mixin;

import com.bonker.stardewfishing.common.networking.C2SCompleteMinigamePacket;
import dev.sunlit.fishvisuals.client.LocalFishingCatches;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(targets = "com.bonker.stardewfishing.client.FishingScreen", remap = false)
public abstract class ClientFishingCompletionMixin {
    @ModifyArg(method = "m_7379_()V", at = @At(value = "INVOKE",
            target = "Lcom/bonker/stardewfishing/common/networking/SFNetworking;sendToServer(Ljava/lang/Object;)V"),
            require = 0)
    private Object sunlit$observeSuccessfulCatch(Object packet) {
        if (packet instanceof C2SCompleteMinigamePacket completion && completion.success()) {
            LocalFishingCatches.expectCatch();
        }
        return packet;
    }
}
