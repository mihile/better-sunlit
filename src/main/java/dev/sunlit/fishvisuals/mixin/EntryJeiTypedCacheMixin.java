package dev.sunlit.fishvisuals.mixin;

import com.google.common.cache.CacheBuilder;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets="mezz.jei.library.ingredients.itemStacks.TypedItemStack",remap=false)
public abstract class EntryJeiTypedCacheMixin {
    @Redirect(method="<clinit>()V",at=@At(value="INVOKE",target="Lcom/google/common/cache/CacheBuilder;concurrencyLevel(I)Lcom/google/common/cache/CacheBuilder;"),require=1,remap=false)
    private static CacheBuilder<?,?> sunlit$avoidRepeatedCacheGrowth(CacheBuilder<?,?> builder,int concurrency) {
        // Keep JEI's loader and one-second expiration. Sizing the table above
        // modpack item counts (~100k) avoids repeated segment expansion during JEI init.
        return builder.concurrencyLevel(concurrency).initialCapacity(131072);
    }
}
