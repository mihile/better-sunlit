package dev.sunlit.fishvisuals.mixin;

import dev.sunlit.bettersunlit.client.ForgeCreativeTabScope;
import net.mehvahdjukaar.moonlight.api.platform.PlatHelper;
import net.mehvahdjukaar.moonlight.api.platform.RegHelper.ItemToTabEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.Set;

@Pseudo
@Mixin(targets = "net.mehvahdjukaar.every_compat.EveryCompat", remap = false)
public abstract class EntryEveryCompatTabMixin {
    @Redirect(method = "registerItemsToTabs",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;entrySet()Ljava/util/Set;"),
            require = 1)
    private static <K, V> Set<Map.Entry<K, V>> sunlit$activeTabOnly(Map<K, V> groups, ItemToTabEvent event) {
        var entries = groups.entrySet();
        var tab = ForgeCreativeTabScope.activeTab(event);
        // The Forge callback uses this key identity test after constructing default stacks.
        // Preserve development mode checks and all custom event consumers.
        if (tab == null || PlatHelper.isDev()) return entries;
        for (var entry : entries) {
            if (entry.getKey() == tab) return Set.of(entry);
        }
        return Set.of();
    }
}
