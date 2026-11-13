package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.JeiSortingPerf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

@Pseudo
@Mixin(targets = "mezz.jei.common.config.sorting.SortingConfig", remap = false)
public abstract class PerfJeiSortingConfigMixin<T> {
    @Shadow
    private List<T> getSorted(Collection<T> allIngredients) {
        throw new AssertionError();
    }

    @Overwrite(remap = false)
    public <V> Comparator<V> getComparator(Collection<T> allIngredients, Function<V, T> mapping) {
        return JeiSortingPerf.comparator(this.getSorted(allIngredients), mapping);
    }

    @Overwrite(remap = false)
    private static <T> Comparator<T> lambda$load$1(Comparator<T> defaultOrder, List<T> sortedFromFile) {
        return JeiSortingPerf.fileOrderThenDefault(defaultOrder, sortedFromFile);
    }
}
