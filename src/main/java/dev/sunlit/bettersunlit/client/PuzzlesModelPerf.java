package dev.sunlit.bettersunlit.client;

import com.google.common.base.Stopwatch;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import dev.sunlit.bettersunlit.perf.StateDefinitionPerf;
import fuzs.puzzleslib.api.client.event.v1.ModelEvents;
import fuzs.puzzleslib.api.event.v1.core.EventResultHolder;
import fuzs.puzzleslib.impl.PuzzlesLib;
import fuzs.puzzleslib.impl.client.event.ForgeModelBakerImpl;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.client.event.ModelEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Narrows PuzzlesLib's {@code ModelEvents.ModifyUnbakedModel} scan for {@code DiagonalBlocks}
 * so each diagonal block's shared model is translated and baked once and assigned across all of its
 * states, while falling back to the full PuzzlesLib path for any other listener.
 */
public final class PuzzlesModelPerf {
    private PuzzlesModelPerf() {
    }

    public static void handleModifyUnbakedModel(
            ModelEvents.ModifyUnbakedModel callback,
            ModelEvent.ModifyBakingResult evt,
            BiConsumer<ModelEvents.ModifyUnbakedModel, ModelEvent.ModifyBakingResult> fallback) {
        if (callback == null || !callback.getClass().getName().startsWith("fuzs.diagonalblocks.")) {
            fallback.accept(callback, evt);
            return;
        }
        List<Block> diagonalBlocks = collectDiagonalBlocks();
        if (diagonalBlocks == null) {
            fallback.accept(callback, evt);
            return;
        }
        Stopwatch stopwatch = Stopwatch.createStarted();
        Map<ResourceLocation, BakedModel> models = evt.getModels();
        Map<ForgeModelBakerImpl.BakedCacheKey, BakedModel> bakedCache = Maps.newHashMap();
        Multimap<ResourceLocation, Material> missingTextures = HashMultimap.create();
        BakedModel missingModel = models.get(ModelBakery.f_119230_);
        Objects.requireNonNull(missingModel, "missing model is null");
        Map<ResourceLocation, UnbakedModel> additionalModels = new HashMap<>();
        Function<ResourceLocation, UnbakedModel> modelGetter = rl -> {
            UnbakedModel extra = additionalModels.get(rl);
            if (extra != null) {
                return extra;
            }
            return loadUnbakedForDiagonal(evt.getModelBakery(), rl);
        };
        BiConsumer<ResourceLocation, UnbakedModel> modelAdder = (rl, unbaked) -> {
            if (rl instanceof ModelResourceLocation) {
                throw new IllegalArgumentException("model resource location is supported");
            }
            additionalModels.put(rl, unbaked);
        };
        for (Block diagonalBlock : diagonalBlocks) {
            try {
                List<BlockState> states = diagonalBlock.m_49965_().m_61056_();
                ResourceLocation id = BuiltInRegistries.f_256975_.m_7981_(diagonalBlock);
                boolean hasItem = BuiltInRegistries.f_257033_.m_7804_(id);
                ResourceLocation probeLoc;
                if (!states.isEmpty()) {
                    probeLoc = BlockModelShaper.m_110895_(states.get(0));
                } else if (hasItem) {
                    probeLoc = new ModelResourceLocation(id, "inventory");
                } else {
                    continue;
                }
                EventResultHolder<UnbakedModel> result = callback.onModifyUnbakedModel(
                        probeLoc,
                        () -> modelGetter.apply(probeLoc),
                        modelGetter,
                        modelAdder);
                if (!result.isInterrupt()) {
                    continue;
                }
                UnbakedModel modified = result.getInterrupt().get();
                ForgeModelBakerImpl baker = new ForgeModelBakerImpl(
                        probeLoc,
                        bakedCache,
                        modelGetter,
                        missingTextures::put,
                        missingModel);
                BakedModel baked = baker.bake(modified, probeLoc);
                DynamicLazyModelStore store = DynamicLazyModelHelper.resolveStore(models);
                if (store != null) {
                    store.sunlit$putBlockPathOverride(id, baked);
                    if (hasItem && id != null) {
                        models.put(new ModelResourceLocation(id, "inventory"), baked);
                    }
                } else {
                    PuzzlesModelPrewarmPerf.putAllStatesBatched(models, diagonalBlock, states, id, baked, hasItem);
                }
            } catch (Exception exception) {
                PuzzlesLib.LOGGER.error("Failed to modify unbaked model", exception);
            }
        }
        missingTextures.asMap().forEach((rl, materials) -> PuzzlesLib.LOGGER.warn(
                "Missing textures in model {}:\n{}",
                rl,
                materials.stream()
                        .sorted(Material.f_244523_)
                        .map(m -> "    " + m.m_119193_() + ":" + m.m_119203_())
                        .collect(Collectors.joining("\n"))));
        PuzzlesLib.LOGGER.info("Modifying unbaked models took {}ms", stopwatch.stop().elapsed().toMillis());
    }

    private static UnbakedModel loadUnbakedForDiagonal(ModelBakery bakery, ResourceLocation rl) {
        if (rl instanceof ModelResourceLocation mrl && !"inventory".equals(mrl.m_119448_())) {
            StateDefinitionPerf.setSingleStateThread(Thread.currentThread());
            try {
                return bakery.m_119341_(rl);
            } finally {
                StateDefinitionPerf.setSingleStateThread(null);
            }
        }
        return bakery.m_119341_(rl);
    }

    static List<Block> collectDiagonalBlocks() {
        try {
            Class<?> typeClass = Class.forName("fuzs.diagonalblocks.api.v2.DiagonalBlockType");
            Field typesField = typeClass.getField("TYPES");
            Method getConversions = typeClass.getMethod("getBlockConversions");
            Collection<?> types = (Collection<?>) typesField.get(null);
            List<Block> blocks = new ArrayList<>();
            for (Object type : types) {
                Map<?, ?> conversions = (Map<?, ?>) getConversions.invoke(type);
                for (Object value : conversions.values()) {
                    if (value instanceof Block diagonalBlock) {
                        blocks.add(diagonalBlock);
                    }
                }
            }
            return blocks;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
