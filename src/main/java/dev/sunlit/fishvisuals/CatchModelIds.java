package dev.sunlit.fishvisuals;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Set;

/** IDs shared by the server reward, saved presentations and the client renderer. */
public final class CatchModelIds {
    private static final Set<String> ITEM_ONLY = Set.of(
            "aquaculture:leech", "aquaculture:goldfish", "society:neptuna"
    );

    private static final Map<String, String> ALIASES = Map.of(
            "unusualfishmod:blind_sailfin", "unusualfishmod:blindsailfin",
            "unusualfishmod:blizzard_tuna", "unusualfishmod:blizzardfin",
            "unusualfishmod:copperflame_anthias", "unusualfishmod:copperflame",
            "unusualfishmod:hatchetfish", "unusualfishmod:hatchet_fish",
            "unusualfishmod:circus_fish", "unusualfishmod:circus",
            "unusualfishmod:snowflake", "unusualfishmod:snowflaketail",
            "netherdepthsupgrade:fortress_grouper", "netherdepthsupgrade:fortressgrouper"
    );

    private CatchModelIds() {}

    public static ResourceLocation resolve(ResourceLocation id) {
        String normalized = id.toString().replace(":raw_", ":");
        String modelId = ALIASES.getOrDefault(normalized, normalized);
        if (ITEM_ONLY.contains(modelId)) return null;
        ResourceLocation model = new ResourceLocation(modelId);
        return entityType(model) == null ? null : model;
    }

    public static EntityType<?> entityType(ResourceLocation id) {
        var registry = ForgeRegistries.ENTITY_TYPES;
        // The entity registry returns its default pig for an unregistered name.
        return registry.containsKey(id) ? registry.getValue(id) : null;
    }
}
