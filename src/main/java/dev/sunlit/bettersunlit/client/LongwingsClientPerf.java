package dev.sunlit.bettersunlit.client;

import dev.sunlit.fishvisuals.SunlitFishVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client-side texture lookup cache for Longwings entities and items.
 * Invalidated on F3+T / resource pack reloads because ReloadableResourceManager swaps packs in place.
 */
@Mod.EventBusSubscriber(modid = SunlitFishVisuals.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class LongwingsClientPerf {
    private static final int MAX_CACHE_ENTRIES = 4096;
    private static final ResourceLocation DEFAULT_BUTTERFLY_TEXTURE =
            new ResourceLocation("longwings", "textures/entity/butterfly/painted_lady.png");
    private static final ResourceLocation DEFAULT_MOTH_TEXTURE =
            new ResourceLocation("longwings", "textures/entity/moth/small_white_wave.png");

    private static final AtomicLong RELOAD_EPOCH = new AtomicLong(0L);
    private static volatile TextureCache cache = new TextureCache();

    private LongwingsClientPerf() {}

    private static final class TextureCache {
        final ConcurrentHashMap<ResourceLocation, Boolean> customPresence = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, ResourceLocation> butterflyEntity = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, ResourceLocation> mothEntity = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, ResourceLocation> butterflyItem = new ConcurrentHashMap<>();
        final ConcurrentHashMap<String, ResourceLocation> mothItem = new ConcurrentHashMap<>();
    }

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) LongwingsClientPerf::onResourceReload);
    }

    /** Also callable in unit tests to verify reload-triggered cache invalidation. */
    public static void onResourceReload(ResourceManager ignored) {
        RELOAD_EPOCH.incrementAndGet();
        cache = new TextureCache();
    }

    public static long reloadEpoch() {
        return RELOAD_EPOCH.get();
    }

    public static boolean hasCustomTexture(ResourceLocation location) {
        TextureCache snap = cache;
        Boolean cached = snap.customPresence.get(location);
        if (cached != null) return cached;
        boolean present = Minecraft.m_91087_().m_91098_().m_213713_(location).isPresent();
        if (snap.customPresence.size() < MAX_CACHE_ENTRIES) {
            snap.customPresence.putIfAbsent(location, present);
        }
        return present;
    }

    public static ResourceLocation getButterflyTextureLocation(String variant) {
        String key = variant != null ? variant : "null";
        TextureCache snap = cache;
        ResourceLocation cached = snap.butterflyEntity.get(key);
        if (cached != null) return cached;
        ResourceLocation custom = new ResourceLocation("longwings", "textures/entity/butterfly/" + key + ".png");
        ResourceLocation resolved = hasCustomTexture(custom) ? custom : DEFAULT_BUTTERFLY_TEXTURE;
        if (snap.butterflyEntity.size() < MAX_CACHE_ENTRIES) {
            snap.butterflyEntity.putIfAbsent(key, resolved);
        }
        return resolved;
    }

    public static ResourceLocation getMothTextureLocation(String variant) {
        String key = variant != null ? variant : "null";
        TextureCache snap = cache;
        ResourceLocation cached = snap.mothEntity.get(key);
        if (cached != null) return cached;
        ResourceLocation custom = new ResourceLocation("longwings", "textures/entity/moth/" + key + ".png");
        ResourceLocation resolved = hasCustomTexture(custom) ? custom : DEFAULT_MOTH_TEXTURE;
        if (snap.mothEntity.size() < MAX_CACHE_ENTRIES) {
            snap.mothEntity.putIfAbsent(key, resolved);
        }
        return resolved;
    }

    public static ResourceLocation getButterflyItemTexture(ItemStack stack) {
        CompoundTag tag = stack.m_41783_();
        if (tag == null) return DEFAULT_BUTTERFLY_TEXTURE;
        String key = tag.m_128461_("variant");
        TextureCache snap = cache;
        ResourceLocation cached = snap.butterflyItem.get(key);
        if (cached != null) return cached;
        ResourceLocation resolved = new ResourceLocation("longwings", "textures/entity/butterfly/" + key + ".png");
        if (snap.butterflyItem.size() < MAX_CACHE_ENTRIES) {
            snap.butterflyItem.putIfAbsent(key, resolved);
        }
        return resolved;
    }

    public static ResourceLocation getMothItemTexture(ItemStack stack) {
        CompoundTag tag = stack.m_41783_();
        if (tag == null) return DEFAULT_MOTH_TEXTURE;
        String key = tag.m_128461_("variant");
        TextureCache snap = cache;
        ResourceLocation cached = snap.mothItem.get(key);
        if (cached != null) return cached;
        ResourceLocation resolved = new ResourceLocation("longwings", "textures/entity/moth/" + key + ".png");
        if (snap.mothItem.size() < MAX_CACHE_ENTRIES) {
            snap.mothItem.putIfAbsent(key, resolved);
        }
        return resolved;
    }

    /** Test seam checking custom-presence caching without booting Minecraft client globals. */
    public static ResourceLocation resolveWithPresenceCheck(
            String variant, boolean isMoth, java.util.function.Predicate<ResourceLocation> presenceProbe) {
        String key = variant != null ? variant : "null";
        TextureCache snap = cache;
        ConcurrentHashMap<String, ResourceLocation> texMap = isMoth ? snap.mothEntity : snap.butterflyEntity;
        ResourceLocation cached = texMap.get(key);
        if (cached != null) return cached;
        String folder = isMoth ? "textures/entity/moth/" : "textures/entity/butterfly/";
        ResourceLocation custom = new ResourceLocation("longwings", folder + key + ".png");
        Boolean present = snap.customPresence.get(custom);
        if (present == null) {
            present = presenceProbe.test(custom);
            if (snap.customPresence.size() < MAX_CACHE_ENTRIES) {
                snap.customPresence.putIfAbsent(custom, present);
            }
        }
        ResourceLocation resolved = present ? custom : (isMoth ? DEFAULT_MOTH_TEXTURE : DEFAULT_BUTTERFLY_TEXTURE);
        if (texMap.size() < MAX_CACHE_ENTRIES) {
            texMap.putIfAbsent(key, resolved);
        }
        return resolved;
    }
}
