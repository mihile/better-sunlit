package dev.sunlit.bettersunlit.client;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.function.Supplier;

/** Keeps reviewed modded skins on the model layers they were authored for. */
public final class SharedMobModelCompat {
    private static final Map<ResourceLocation, ResourceLocation> SHARED_MODELS = Map.of(
            new ResourceLocation("meadow", "wooly_sheep"), new ResourceLocation("minecraft", "sheep"),
            new ResourceLocation("farmingforblockheads", "merchant"), new ResourceLocation("minecraft", "villager"),
            new ResourceLocation("vinery", "wandering_winemaker"), new ResourceLocation("minecraft", "wandering_trader"),
            new ResourceLocation("quark", "forgotten"), new ResourceLocation("minecraft", "skeleton"));
    private static final ResourceLocation TROPICAL_FISH = new ResourceLocation("minecraft", "tropical_fish_large");
    private static final ThreadLocal<ResourceLocation> MODEL = new ThreadLocal<>();

    private SharedMobModelCompat() {}

    public static <T> T duringRendererCreation(ResourceLocation entity, Supplier<T> create) {
        ResourceLocation model = entity != null && "aquaculture".equals(entity.m_135827_())
                ? TROPICAL_FISH : entity == null ? null : SHARED_MODELS.get(entity);
        ResourceLocation previous = MODEL.get();
        if (previous == null && model == null) return create.get();

        MODEL.set(model);
        try {
            return create.get();
        } finally {
            if (previous == null) MODEL.remove();
            else MODEL.set(previous);
        }
    }

    public static boolean useOriginalLayer(ModelLayerLocation layer) {
        return layer.m_171123_().equals(MODEL.get());
    }
}
