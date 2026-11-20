package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.fishvisuals.BalloonTracking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity", remap = false)
public abstract class BalloonTrackingRangeMixin {
    @Shadow @Final private Entity f_140472_;
    @Unique private boolean sunlit$balloon;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sunlit$identifyBalloon(CallbackInfo ci) {
        sunlit$balloon = BalloonTracking.isBalloon(f_140472_);
    }

    @Inject(method = "m_140496_()I", at = @At("HEAD"), cancellable = true)
    private void sunlit$useSimulationDistance(CallbackInfoReturnable<Integer> cir) {
        if (!sunlit$balloon) return;

        MinecraftServer server = f_140472_.m_20194_();
        if (server != null) {
            // ChunkMap still clamps the result to its view distance.
            cir.setReturnValue(server.m_6846_().m_184213_() * 16);
        }
    }
}
