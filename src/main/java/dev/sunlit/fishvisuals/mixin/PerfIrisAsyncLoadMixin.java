package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.IrisShaderLoadPerf;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Overlaps initial Iris shaderpack parsing with Forge mod loading by running
 * {@link Iris#loadShaderpack()} on a background daemon thread after pre-warming GL-backed
 * {@link FeatureFlags} on the Render thread.
 */
@Mixin(value = Iris.class, remap = false)
public abstract class PerfIrisAsyncLoadMixin {
    @Redirect(
            method = "onRenderSystemInit()V",
            at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/Iris;loadShaderpack()V"))
    private static void sunlit$asyncInitialShaderpackLoad() {
        IrisRenderSystem.supportsSSBO();
        IrisRenderSystem.supportsImageLoadStore();
        IrisRenderSystem.supportsBufferBlending();
        for (FeatureFlags flag : FeatureFlags.values()) {
            flag.isUsable();
        }
        StandardMacros.createStandardEnvironmentDefines();
        IrisShaderLoadPerf.startAsync(Iris::loadShaderpack);
    }

    @Inject(method = "getCurrentPack()Ljava/util/Optional;", at = @At("HEAD"))
    private static void sunlit$awaitPack(CallbackInfoReturnable<Optional<ShaderPack>> cir) {
        IrisShaderLoadPerf.awaitLoaded();
    }

    @Inject(method = "getCurrentPackName()Ljava/lang/String;", at = @At("HEAD"))
    private static void sunlit$awaitPackName(CallbackInfoReturnable<String> cir) {
        IrisShaderLoadPerf.awaitLoaded();
    }

    @Inject(method = "createPipeline(Lnet/irisshaders/iris/shaderpack/materialmap/NamespacedId;)Lnet/irisshaders/iris/pipeline/WorldRenderingPipeline;", at = @At("HEAD"))
    private static void sunlit$awaitCreatePipeline(NamespacedId dimension, CallbackInfoReturnable<WorldRenderingPipeline> cir) {
        IrisShaderLoadPerf.awaitLoaded();
    }

    @Inject(method = "isFallback()Z", at = @At("HEAD"))
    private static void sunlit$awaitFallback(CallbackInfoReturnable<Boolean> cir) {
        IrisShaderLoadPerf.awaitLoaded();
    }

    @Inject(method = {"reload()V", "onLoadingComplete()V"}, at = @At("HEAD"))
    private static void sunlit$awaitVoidOps(CallbackInfo ci) {
        IrisShaderLoadPerf.awaitLoaded();
    }

    @Inject(method = "toggleShaders(Lnet/minecraft/client/Minecraft;Z)V", at = @At("HEAD"))
    private static void sunlit$awaitToggleShaders(Minecraft minecraft, boolean enabled, CallbackInfo ci) {
        IrisShaderLoadPerf.awaitLoaded();
    }
}
