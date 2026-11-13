package dev.sunlit.bettersunlit.client;

import com.supermartijn642.core.registry.Registries;
import com.supermartijn642.core.item.BaseItem;
import com.supermartijn642.core.item.BaseBlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistry;
import java.util.Collection;

public final class EntryCreativeCandidates {
    private EntryCreativeCandidates() {}
    public static Collection<Item> get(Registries.Registry<Item> registry) {
        // Only a frozen item registry is stable for this synchronous build. The
        // original per-tab visibility predicate still runs for every candidate.
        if(registry!=Registries.ITEMS||!(registry.getForgeRegistry() instanceof ForgeRegistry<?> forge)||!forge.isLocked())return registry.getValues();
        return ScopedCandidateCache.candidates(registry,registry::getValues,item->item instanceof BaseItem||item instanceof BaseBlockItem);
    }
}
