package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.EmfModelPrebakePerf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import traben.entity_texture_features.features.texture_handlers.ETFDirectory;
import traben.entity_texture_features.features.texture_handlers.ETFTexture;
import traben.entity_texture_features.features.texture_handlers.ETFTextureVariator;

import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replaces unsynchronized {@code ETFManager} collections with thread-safe containers so
 * concurrent EMF model variant initialization can query ETF directories safely.
 */
@Pseudo
@Mixin(targets = "traben.entity_texture_features.features.ETFManager", remap = false)
public abstract class PerfEtfManagerInitMixin {
    @Mutable
    @Shadow
    @Final
    public Set<String> EMISSIVE_SUFFIX_LIST;

    @Mutable
    @Shadow
    @Final
    public Set<EntityType<?>> ENTITY_TYPE_IGNORE_PARTICLES;

    @Mutable
    @Shadow
    @Final
    public HashMap<ResourceLocation, ETFTexture> ETF_TEXTURE_CACHE;

    @Mutable
    @Shadow
    @Final
    public HashMap<ResourceLocation, ETFDirectory> ETF_DIRECTORY_CACHE;

    @Mutable
    @Shadow
    @Final
    private HashMap<ResourceLocation, ETFTextureVariator> VARIATOR_MAP;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void sunlit$initConcurrentCaches(CallbackInfo ci) {
        this.EMISSIVE_SUFFIX_LIST = ConcurrentHashMap.newKeySet();
        this.ENTITY_TYPE_IGNORE_PARTICLES = ConcurrentHashMap.newKeySet();
        this.ETF_TEXTURE_CACHE = new EmfModelPrebakePerf.SyncHashMap<>();
        this.ETF_DIRECTORY_CACHE = new EmfModelPrebakePerf.SyncHashMap<>();
        this.VARIATOR_MAP = new EmfModelPrebakePerf.SyncHashMap<>();
    }
}
