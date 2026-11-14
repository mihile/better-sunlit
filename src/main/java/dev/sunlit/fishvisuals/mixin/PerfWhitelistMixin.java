package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ParticleRainClientPerf;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;

/**
 * Replaces ParticleRain {@code Whitelist.contains(Holder)} linear tag/ID scans and per-call
 * {@code ArrayList$Itr} allocations with a per-instance {@link Reference2ByteOpenHashMap} cache
 * invalidated on {@code populateInternalLists()}, snapshot changes, or Forge tag reloads.
 */
@Pseudo
@Mixin(targets = "pigcart.particlerain.config.Whitelist", priority = 900, remap = false)
public abstract class PerfWhitelistMixin<T> {

    @Shadow(remap = false)
    public transient ArrayList<TagKey<T>> tags;

    @Shadow(remap = false)
    public transient ArrayList<ResourceLocation> ids;

    @Shadow(remap = false)
    public boolean isWhitelist;

    @Shadow(remap = false)
    public abstract ArrayList<String> getEntries();

    @Unique
    private transient Reference2ByteOpenHashMap<Object> sunlit$cache;

    @Unique
    private transient Thread sunlit$cacheThread;

    @Unique
    private transient int sunlit$lastTagCount = -1;

    @Unique
    private transient int sunlit$lastIdCount = -1;

    @Unique
    private transient int sunlit$lastEntryCount = -1;

    @Unique
    private transient boolean sunlit$lastIsWhitelist;

    @Unique
    private transient int sunlit$lastEpoch = -1;

    @Inject(method = "populateInternalLists()V", at = @At("HEAD"), remap = false)
    private void sunlit$invalidateOnPopulate(CallbackInfo ci) {
        if (this.sunlit$cache != null) {
            this.sunlit$cache.clear();
        }
        this.sunlit$lastTagCount = -1;
    }

    /**
     * @author better-sunlit
     * @reason Replace linear ArrayList iteration and iterator allocation with cached O(1) lookup
     */
    @Overwrite(remap = false)
    public boolean contains(Holder<T> holder) {
        ArrayList<String> entries = this.getEntries();
        if (entries == null || entries.isEmpty()) {
            return true;
        }
        if (holder == null) {
            return !this.isWhitelist;
        }
        Thread current = Thread.currentThread();
        if (this.sunlit$cacheThread == null) {
            ParticleRainClientPerf.ensureTagHookRegistered();
            this.sunlit$cacheThread = current;
            Reference2ByteOpenHashMap<Object> map = new Reference2ByteOpenHashMap<>(64);
            map.defaultReturnValue((byte) -1);
            this.sunlit$cache = map;
        }
        if (current == this.sunlit$cacheThread) {
            Reference2ByteOpenHashMap<Object> cache = this.sunlit$cache;
            int epoch = ParticleRainClientPerf.whitelistCacheEpoch();
            int tagCount = this.tags.size();
            int idCount = this.ids.size();
            int entryCount = entries.size();
            boolean whitelist = this.isWhitelist;
            if (this.sunlit$lastEpoch != epoch
                    || this.sunlit$lastTagCount != tagCount
                    || this.sunlit$lastIdCount != idCount
                    || this.sunlit$lastEntryCount != entryCount
                    || this.sunlit$lastIsWhitelist != whitelist
                    || cache.size() >= 4096) {
                cache.clear();
                this.sunlit$lastEpoch = epoch;
                this.sunlit$lastTagCount = tagCount;
                this.sunlit$lastIdCount = idCount;
                this.sunlit$lastEntryCount = entryCount;
                this.sunlit$lastIsWhitelist = whitelist;
            } else {
                byte cached = cache.getByte(holder);
                if (cached >= 0) {
                    return cached != 0;
                }
            }
            boolean result = this.sunlit$computeContains(holder);
            cache.put(holder, result ? (byte) 1 : (byte) 0);
            return result;
        }
        return this.sunlit$computeContains(holder);
    }

    @Unique
    private boolean sunlit$computeContains(Holder<T> holder) {
        boolean whitelist = this.isWhitelist;
        ArrayList<TagKey<T>> tagList = this.tags;
        for (int i = 0, n = tagList.size(); i < n; i++) {
            if (holder.m_203656_(tagList.get(i))) {
                return whitelist;
            }
        }
        ArrayList<ResourceLocation> idList = this.ids;
        for (int i = 0, n = idList.size(); i < n; i++) {
            if (holder.m_203373_(idList.get(i))) {
                return whitelist;
            }
        }
        return !whitelist;
    }
}
