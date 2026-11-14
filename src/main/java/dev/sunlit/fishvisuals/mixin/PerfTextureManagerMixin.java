package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.TextureManagerPerf;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.Tickable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Set;

/**
 * Replaces {@link TextureManager}'s unsynchronized {@code HashMap}/{@code HashSet} backing fields
 * with thread-safe collections so off-thread texture registration/release (e.g. Netty packet decode
 * or background emoji/quest image threads) never causes a {@code ConcurrentModificationException}
 * while {@code TextureManager.m_7673_()} ticks on the Render thread.
 */
@Mixin(value = TextureManager.class, priority = 2000, remap = false)
public abstract class PerfTextureManagerMixin {
    @Shadow
    @Final
    @Mutable
    private Map<ResourceLocation, AbstractTexture> f_118468_;

    @Shadow
    @Final
    @Mutable
    private Set<Tickable> f_118469_;

    @Shadow
    @Final
    @Mutable
    private Map<String, Integer> f_118470_;

    @Inject(method = "<init>(Lnet/minecraft/server/packs/resources/ResourceManager;)V", at = @At("RETURN"), require = 1)
    private void sunlit$initConcurrentMaps(ResourceManager resourceManager, CallbackInfo ci) {
        this.f_118468_ = TextureManagerPerf.concurrentByPath(this.f_118468_);
        this.f_118469_ = TextureManagerPerf.concurrentTickables(this.f_118469_);
        this.f_118470_ = TextureManagerPerf.concurrentPrefixRegister(this.f_118470_);
    }
}
