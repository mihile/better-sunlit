package dev.sunlit.fishvisuals.mixin;

import net.minecraft.core.Vec3i;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Ensures {@code RandomSpreadStructurePlacement.spacing > separation} before codec validation runs,
 * preventing singleplayer world creation / empty world-list datapack reloads from crashing when
 * structure_set files configure {@code spacing <= separation} without Structure Essentials on client.
 */
@Mixin(value = RandomSpreadStructurePlacement.class, priority = 2000, remap = false)
public abstract class PerfRandomSpreadStructurePlacementMixin {

    @Shadow(remap = false)
    @Final
    @Mutable
    private int f_204973_;

    @Shadow(remap = false)
    @Final
    private int f_204974_;

    @Inject(
            method = "<init>(Lnet/minecraft/core/Vec3i;Lnet/minecraft/world/level/levelgen/structure/placement/StructurePlacement$FrequencyReductionMethod;FILjava/util/Optional;IILnet/minecraft/world/level/levelgen/structure/placement/RandomSpreadType;)V",
            at = @At("RETURN"),
            remap = false,
            require = 1
    )
    private void sunlit$clampInvalidSpacing(
            Vec3i locateOffset,
            StructurePlacement.FrequencyReductionMethod frequencyReductionMethod,
            float frequency,
            int salt,
            Optional<StructurePlacement.ExclusionZone> exclusionZone,
            int spacing,
            int separation,
            RandomSpreadType spreadType,
            CallbackInfo ci) {
        if (this.f_204973_ <= this.f_204974_) {
            int next = this.f_204974_ == Integer.MAX_VALUE ? Integer.MAX_VALUE : this.f_204974_ + 1;
            this.f_204973_ = Math.max(1, next);
        }
    }
}
