package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.perf.PathPackIndexPerf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.resource.PathPackResources;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;

/**
 * Caches namespace sets on {@link PathPackResources} and rejects missing-namespace and missing-prefix
 * lookups before {@code getPathFromLocation} / {@code resolve} / {@code Files.exists} allocate
 * {@code String[]}, {@code UnionPath}, and {@code LinkOption[]} instances.
 */
@Mixin(value = PathPackResources.class, remap = false)
public abstract class PerfPathPackResourcesMixin {
    @Unique
    private static final Object SUNLIT$NOT_IMMUTABLE = new Object();

    @Unique
    private volatile Set<String> sunlit$clientNamespaces;

    @Unique
    private volatile Set<String> sunlit$serverNamespaces;

    @Unique
    private volatile Object sunlit$packIndex;

    @Shadow
    protected abstract Set<String> getNamespacesFromDisk(PackType type);

    @Shadow
    protected abstract Path resolve(String... paths);

    @Unique
    private Set<String> sunlit$getCachedNamespaces(PackType type) {
        if (type == PackType.CLIENT_RESOURCES) {
            Set<String> cached = this.sunlit$clientNamespaces;
            if (cached == null) {
                Set<String> disk = this.getNamespacesFromDisk(type);
                cached = disk == null || disk.isEmpty() ? Set.of() : Set.copyOf(disk);
                this.sunlit$clientNamespaces = cached;
            }
            return cached;
        }
        Set<String> cached = this.sunlit$serverNamespaces;
        if (cached == null) {
            Set<String> disk = this.getNamespacesFromDisk(type);
            cached = disk == null || disk.isEmpty() ? Set.of() : Set.copyOf(disk);
            this.sunlit$serverNamespaces = cached;
        }
        return cached;
    }

    @Unique
    private PathPackIndexPerf.PackIndex sunlit$getIndex() {
        Object cached = this.sunlit$packIndex;
        if (cached == SUNLIT$NOT_IMMUTABLE) {
            return null;
        }
        if (cached != null) {
            return (PathPackIndexPerf.PackIndex) cached;
        }
        PathPackResources self = (PathPackResources) (Object) this;
        if (!PathPackIndexPerf.isImmutableModPack(self)) {
            this.sunlit$packIndex = SUNLIT$NOT_IMMUTABLE;
            return null;
        }
        PathPackIndexPerf.PackIndex idx = new PathPackIndexPerf.PackIndex();
        this.sunlit$packIndex = idx;
        return idx;
    }

    @Inject(method = "m_5698_", at = @At("HEAD"), cancellable = true)
    private void sunlit$cachedNamespaces(PackType type, CallbackInfoReturnable<Set<String>> cir) {
        if (type != null) {
            cir.setReturnValue(this.sunlit$getCachedNamespaces(type));
        }
    }

    @Inject(method = "m_8017_", at = @At("HEAD"), cancellable = true)
    private void sunlit$fastRootResource(String[] paths, CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (paths == null || paths.length == 0) {
            return;
        }
        for (int i = 0; i < paths.length; i++) {
            String p = paths[i];
            if (p == null || p.contains("..")) {
                return;
            }
        }
        PathPackIndexPerf.PackIndex idx = this.sunlit$getIndex();
        if (idx == null) {
            return;
        }
        if (paths.length == 1) {
            Object cached = idx.getRootCached(paths[0], this::resolve);
            if (idx.isRootMiss(cached)) {
                cir.setReturnValue(null);
            } else {
                cir.setReturnValue(IoSupplier.m_246697_((Path) cached));
            }
            return;
        }
        if (paths.length >= 3 && ("assets".equals(paths[0]) || "data".equals(paths[0]))) {
            PackType t = "assets".equals(paths[0]) ? PackType.CLIENT_RESOURCES : PackType.SERVER_DATA;
            if (!this.sunlit$getCachedNamespaces(t).contains(paths[1])) {
                cir.setReturnValue(null);
                return;
            }
            String seg2 = paths.length >= 4 ? paths[3] : null;
            if (!idx.mayContainSegments(paths[0], paths[1], paths[2], seg2, this::resolve)) {
                cir.setReturnValue(null);
            }
        }
    }

    @Inject(method = "m_214146_", at = @At("HEAD"), cancellable = true)
    private void sunlit$fastMissNamespace(
            PackType type,
            ResourceLocation location,
            CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        if (type == null || location == null) {
            return;
        }
        String relPath = location.m_135815_();
        PackType effective = relPath.startsWith("lang/") ? PackType.CLIENT_RESOURCES : type;
        String ns = location.m_135827_();
        if (!this.sunlit$getCachedNamespaces(effective).contains(ns)) {
            cir.setReturnValue(null);
            return;
        }
        PathPackIndexPerf.PackIndex idx = this.sunlit$getIndex();
        if (idx != null && !idx.mayContainAssetOrDataPath(effective.m_10305_(), ns, relPath, this::resolve)) {
            cir.setReturnValue(null);
        }
    }
}
