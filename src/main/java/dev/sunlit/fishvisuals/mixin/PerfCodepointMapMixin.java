package dev.sunlit.fishvisuals.mixin;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import net.minecraft.client.gui.font.CodepointMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Presizes {@link IntOpenHashSet} and avoids per-codepoint lambda dispatch in {@code CodepointMap.keySet()}. */
@Mixin(value = CodepointMap.class, remap = false)
public abstract class PerfCodepointMapMixin<T> {
    @Shadow
    @Final
    private T[] f_283911_;

    @Shadow
    @Final
    private T[][] f_283938_;

    @Inject(method = "m_284498_", at = @At("HEAD"), cancellable = true)
    private void betterSunlit$fastKeySet(CallbackInfoReturnable<IntSet> cir) {
        T[][] blocks = this.f_283938_;
        T[] empty = this.f_283911_;
        int count = 0;
        for (int i = 0; i < blocks.length; i++) {
            T[] block = blocks[i];
            if (block == empty) {
                continue;
            }
            for (int j = 0; j < block.length; j++) {
                if (block[j] != null) {
                    count++;
                }
            }
        }
        IntOpenHashSet set = new IntOpenHashSet(Math.max(16, count), 0.75F);
        for (int b = 0; b < blocks.length; b++) {
            T[] block = blocks[b];
            if (block == empty) {
                continue;
            }
            int base = b << 8;
            for (int j = 0; j < block.length; j++) {
                if (block[j] != null) {
                    set.add(base | j);
                }
            }
        }
        cir.setReturnValue(set);
    }
}
