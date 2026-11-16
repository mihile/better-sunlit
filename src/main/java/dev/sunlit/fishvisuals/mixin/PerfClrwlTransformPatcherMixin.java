package dev.sunlit.fishvisuals.mixin;

import dev.djefrey.colorwheel.compile.transform.ClrwlTransformOutput;
import dev.sunlit.bettersunlit.client.GlslTransformDiskCache;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Serves persistent disk-cached Colorwheel GLSL vertex/geometry/fragment transforms so Create
 * contraption shaders do not stall the Render thread when first entering view.
 */
@Pseudo
@Mixin(targets = "dev.djefrey.colorwheel.compile.transform.ClrwlTransformPatcher", priority = 2000, remap = false)
public abstract class PerfClrwlTransformPatcherMixin {
    @Unique
    private static final ThreadLocal<String> sunlit$pendingVertexKey = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<String> sunlit$pendingGeometryKey = new ThreadLocal<>();
    @Unique
    private static final ThreadLocal<String> sunlit$pendingFragmentKey = new ThreadLocal<>();

    private static final String PATCH_VERTEX =
            "patchVertex(Ljava/lang/String;ZILit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ljava/lang/String;";
    private static final String PATCH_GEOMETRY =
            "patchGeometry(Ljava/lang/String;ZILit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ljava/lang/String;";
    private static final String PATCH_FRAGMENT =
            "patchFragment(Ljava/lang/String;ZZILit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Ldev/djefrey/colorwheel/compile/transform/ClrwlTransformOutput;";

    @Inject(method = PATCH_VERTEX, at = @At("HEAD"), cancellable = true)
    private static void sunlit$checkVertexCache(
            String source,
            boolean isShadowPass,
            int gbufferType,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            CallbackInfoReturnable<String> cir) {
        sunlit$pendingVertexKey.remove();
        String key = GlslTransformDiskCache.keyForClrwlString("vert", source, isShadowPass, gbufferType, textureMap);
        if (key == null) return;
        String cached = GlslTransformDiskCache.getClrwlString(key);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        sunlit$pendingVertexKey.set(key);
    }

    @Inject(method = PATCH_VERTEX, at = @At("RETURN"))
    private static void sunlit$storeVertexCache(
            String source,
            boolean isShadowPass,
            int gbufferType,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            CallbackInfoReturnable<String> cir) {
        String key = sunlit$pendingVertexKey.get();
        sunlit$pendingVertexKey.remove();
        if (key != null && cir.getReturnValue() != null) {
            GlslTransformDiskCache.putClrwlString(key, cir.getReturnValue());
        }
    }

    @Inject(method = PATCH_GEOMETRY, at = @At("HEAD"), cancellable = true)
    private static void sunlit$checkGeometryCache(
            String source,
            boolean isShadowPass,
            int gbufferType,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            CallbackInfoReturnable<String> cir) {
        sunlit$pendingGeometryKey.remove();
        String key = GlslTransformDiskCache.keyForClrwlString("geom", source, isShadowPass, gbufferType, textureMap);
        if (key == null) return;
        String cached = GlslTransformDiskCache.getClrwlString(key);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        sunlit$pendingGeometryKey.set(key);
    }

    @Inject(method = PATCH_GEOMETRY, at = @At("RETURN"))
    private static void sunlit$storeGeometryCache(
            String source,
            boolean isShadowPass,
            int gbufferType,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            CallbackInfoReturnable<String> cir) {
        String key = sunlit$pendingGeometryKey.get();
        sunlit$pendingGeometryKey.remove();
        if (key != null && cir.getReturnValue() != null) {
            GlslTransformDiskCache.putClrwlString(key, cir.getReturnValue());
        }
    }

    @Inject(method = PATCH_FRAGMENT, at = @At("HEAD"), cancellable = true)
    private static void sunlit$checkFragmentCache(
            String source,
            boolean isShadowPass,
            boolean isSolid,
            int gbufferType,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            CallbackInfoReturnable<ClrwlTransformOutput> cir) {
        sunlit$pendingFragmentKey.remove();
        String key = GlslTransformDiskCache.keyForClrwlFragment(source, isShadowPass, isSolid, gbufferType, textureMap);
        if (key == null) return;
        ClrwlTransformOutput cached = GlslTransformDiskCache.getClrwlFragment(key);
        if (cached != null) {
            cir.setReturnValue(cached);
            return;
        }
        sunlit$pendingFragmentKey.set(key);
    }

    @Inject(method = PATCH_FRAGMENT, at = @At("RETURN"))
    private static void sunlit$storeFragmentCache(
            String source,
            boolean isShadowPass,
            boolean isSolid,
            int gbufferType,
            Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> textureMap,
            CallbackInfoReturnable<ClrwlTransformOutput> cir) {
        String key = sunlit$pendingFragmentKey.get();
        sunlit$pendingFragmentKey.remove();
        if (key != null && cir.getReturnValue() != null) {
            GlslTransformDiskCache.putClrwlFragment(key, cir.getReturnValue());
        }
    }
}
