package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.LongwingsVariantPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "com.cicada.longwings.entity.ButterflyEntity", remap = false)
public abstract class PerfButterflyEntityMixin {
    @Redirect(
            method = {
                "setButterflyVariantOnBiome(Lnet/minecraft/world/level/ServerLevelAccessor;Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/MobSpawnType;Lnet/minecraft/world/entity/SpawnGroupData;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/entity/SpawnGroupData;",
                "lambda$mobInteract$1(Ljava/lang/String;)Z"
            },
            at = @At(value = "INVOKE", target = "Ljava/lang/String;matches(Ljava/lang/String;)Z"),
            require = 3,
            remap = false)
    private boolean sunlit$fastMatches(String candidate, String pattern) {
        return LongwingsVariantPerf.matches(candidate, pattern);
    }
}
