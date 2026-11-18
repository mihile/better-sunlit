package dev.sunlit.bettersunlit.client;

import net.mehvahdjukaar.moonlight.api.platform.RegHelper.ItemToTabEvent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;

import java.util.function.Consumer;

public final class ForgeCreativeTabScope {
    private record Context(ItemToTabEvent event, ResourceKey<CreativeModeTab> key) {}
    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

    private ForgeCreativeTabScope() {}

    public static void accept(Consumer<ItemToTabEvent> listener, ItemToTabEvent event,
                              ResourceKey<CreativeModeTab> key) {
        Context previous = CURRENT.get();
        CURRENT.set(new Context(event, key));
        try {
            listener.accept(event);
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static ResourceKey<CreativeModeTab> activeTab(ItemToTabEvent event) {
        Context current = CURRENT.get();
        return current != null && current.event() == event ? current.key() : null;
    }
}
