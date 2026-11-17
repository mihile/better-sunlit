package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.StableVertexRadixSort;
import me.jellysquid.mods.sodium.client.util.sorting.MergeSort;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.util.sorting.VertexSorters$AbstractVertexSorter", remap = false)
public abstract class PerfEmbeddiumVertexSortMixin {
    @Redirect(method = "mergeSort([Lorg/joml/Vector3f;)[I", at = @At(value = "INVOKE", target = "Lme/jellysquid/mods/sodium/client/util/sorting/MergeSort;mergeSort([F)[I"))
    private int[] sunlit$stableRadix(float[] keys) {
        int[] result = StableVertexRadixSort.sort(keys);
        return result == null ? MergeSort.mergeSort(keys) : result;
    }
}
