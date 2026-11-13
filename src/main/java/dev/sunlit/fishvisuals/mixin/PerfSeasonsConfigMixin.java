package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.SeasonPerf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Pseudo
@Mixin(targets = "sereneseasons.config.SeasonsConfig", remap = false)
public abstract class PerfSeasonsConfigMixin {
    @Shadow
    public List<String> whitelistedDimensions;

    @Inject(method = "isDimensionWhitelisted(Lnet/minecraft/resources/ResourceKey;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void sunlit$isDimensionWhitelistedFast(ResourceKey<Level> dimension,
                                                   CallbackInfoReturnable<Boolean> cir) {
        cir.setReturnValue(SeasonPerf.isDimensionWhitelisted(this.whitelistedDimensions, dimension));
    }
}
