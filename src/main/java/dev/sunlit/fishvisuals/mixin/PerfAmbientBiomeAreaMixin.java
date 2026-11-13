package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.AmbientSoundsClientPerf;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "team.creative.ambientsounds.environment.BiomeEnvironment$BiomeArea", remap = false)
public abstract class PerfAmbientBiomeAreaMixin {
    @Shadow
    @Final
    public Holder<Biome> biome;

    @Shadow
    @Final
    public ResourceLocation location;

    @Inject(method = "checkBiome([Ljava/lang/String;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$checkBiomeFast(String[] definitions, CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(AmbientSoundsClientPerf.checkBiome(this.biome, this.location, definitions));
    }
}
