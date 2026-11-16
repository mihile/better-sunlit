package dev.sunlit.bettersunlit.client;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;

import java.util.function.Function;

/**
 * Extension contract mixed into ModernFix's {@code DynamicBakedModelProvider} to register
 * per-blockstate virtual overrides and lazy {@code BakedModel} wrappers without eagerly baking
 * thousands of unused models at startup.
 */
public interface DynamicLazyModelStore {
    void sunlit$putBlockPathOverride(ResourceLocation blockId, BakedModel model);

    void sunlit$addLazySwap(ResourceLocation loc, Function<BakedModel, ? extends BakedModel> fn);
}
