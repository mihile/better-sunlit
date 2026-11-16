package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.DynamicLazyModelHelper;
import dev.sunlit.bettersunlit.client.DynamicLazyModelStore;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import org.embeddedt.modernfix.dynamicresources.DynamicBakedModelProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Adds lazy swap registration and block-ID-keyed overrides to ModernFix's
 * {@code DynamicBakedModelProvider}.
 */
@Mixin(value = DynamicBakedModelProvider.class, remap = false)
public abstract class PerfDynamicBakedModelProviderMixin implements DynamicLazyModelStore {
    @Shadow
    @Final
    private Map<ResourceLocation, BakedModel> permanentOverrides;

    @Unique
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, BakedModel>> sunlit$blockPathOverrides =
            new ConcurrentHashMap<>(4);

    @Unique
    private final ConcurrentHashMap<ResourceLocation, DynamicLazyModelHelper.LazySlot> sunlit$lazySwaps =
            new ConcurrentHashMap<>();

    @Unique
    private static final ThreadLocal<Set<ResourceLocation>> SUNLIT_RESOLVING =
            ThreadLocal.withInitial(HashSet::new);

    @Inject(
            method = "<init>(Lnet/minecraft/client/resources/model/ModelBakery;Ljava/util/Map;)V",
            at = @At("RETURN"))
    private void sunlit$recordLastInstance(
            ModelBakery bakery,
            Map<?, BakedModel> bakedCache,
            CallbackInfo ci) {
        DynamicLazyModelHelper.setLastStore(this);
    }

    @Override
    public void sunlit$putBlockPathOverride(ResourceLocation blockId, BakedModel model) {
        if (blockId == null || model == null) {
            return;
        }
        this.sunlit$blockPathOverrides
                .computeIfAbsent(blockId.m_135827_(), k -> new ConcurrentHashMap<>())
                .put(blockId.m_135815_(), model);
    }

    @Override
    public void sunlit$addLazySwap(
            ResourceLocation loc,
            Function<BakedModel, ? extends BakedModel> fn) {
        DynamicLazyModelHelper.addLazySwap(this.sunlit$lazySwaps, this.permanentOverrides, loc, fn);
    }

    @Inject(
            method = "get(Ljava/lang/Object;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("HEAD"),
            cancellable = true)
    private void sunlit$resolveLazyOrBlockOverride(
            Object key,
            CallbackInfoReturnable<BakedModel> cir) {
        if (!(key instanceof ResourceLocation loc)) {
            return;
        }
        if (!this.sunlit$blockPathOverrides.isEmpty()
                && loc instanceof ModelResourceLocation mrl
                && !"inventory".equals(mrl.m_119448_())) {
            ConcurrentHashMap<String, BakedModel> byPath =
                    this.sunlit$blockPathOverrides.get(loc.m_135827_());
            if (byPath != null) {
                BakedModel blockBaked = byPath.get(loc.m_135815_());
                if (blockBaked != null) {
                    BakedModel direct = this.permanentOverrides.get(loc);
                    if (direct == null) {
                        cir.setReturnValue(blockBaked);
                        return;
                    }
                }
            }
        }
        if (this.sunlit$lazySwaps.isEmpty()) {
            return;
        }
        Set<ResourceLocation> active = SUNLIT_RESOLVING.get();
        if (active.contains(loc)) {
            return;
        }
        DynamicLazyModelHelper.LazySlot slot = this.sunlit$lazySwaps.get(loc);
        if (slot == null) {
            return;
        }
        BakedModel resolved = DynamicLazyModelHelper.resolveLazySwap(
                this, this.sunlit$lazySwaps, this.permanentOverrides, loc, active);
        cir.setReturnValue(resolved);
    }

    @Inject(
            method = "put(Lnet/minecraft/resources/ResourceLocation;Lnet/minecraft/client/resources/model/BakedModel;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("HEAD"))
    private void sunlit$clearLazyOnPut(
            ResourceLocation key,
            BakedModel value,
            CallbackInfoReturnable<BakedModel> cir) {
        if (key != null && !this.sunlit$lazySwaps.isEmpty()) {
            this.sunlit$lazySwaps.remove(key);
        }
    }

    @Inject(method = "putAll(Ljava/util/Map;)V", at = @At("HEAD"))
    private void sunlit$clearLazyOnPutAll(
            Map<? extends ResourceLocation, ? extends BakedModel> map,
            CallbackInfo ci) {
        if (map != null && !map.isEmpty() && !this.sunlit$lazySwaps.isEmpty()) {
            for (ResourceLocation key : map.keySet()) {
                if (key != null) {
                    this.sunlit$lazySwaps.remove(key);
                }
            }
        }
    }

    @Inject(
            method = "remove(Ljava/lang/Object;)Lnet/minecraft/client/resources/model/BakedModel;",
            at = @At("HEAD"))
    private void sunlit$clearLazyOnRemove(
            Object key,
            CallbackInfoReturnable<BakedModel> cir) {
        if (key != null && !this.sunlit$lazySwaps.isEmpty()) {
            this.sunlit$lazySwaps.remove(key);
        }
    }
}
