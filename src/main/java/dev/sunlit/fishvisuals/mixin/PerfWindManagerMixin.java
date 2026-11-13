package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "pigcart.particlerain.WindManager", remap = false)
public abstract class PerfWindManagerMixin {
    @Redirect(
            method = {
                    "applyWind(Lnet/minecraft/client/particle/Particle;Lpigcart/particlerain/config/ParticleData;Lnet/minecraft/client/multiplayer/ClientLevel;)V",
                    "applySpawnWind(Lnet/minecraft/client/particle/Particle;Lpigcart/particlerain/config/ParticleData;Lnet/minecraft/client/multiplayer/ClientLevel;)V"
            },
            at = @At(value = "INVOKE", target = "Lpigcart/particlerain/ParticleRain;getWind(DDD)Lorg/joml/Vector3f;"),
            require = 2,
            remap = false
    )
    private static Vector3f sunlit$fillWindNoAlloc(double x, double y, double z) {
        return ParticleRainClientPerf.fillWind(x, y, z, ParticleRainClientPerf.scratch().vVel);
    }
}
