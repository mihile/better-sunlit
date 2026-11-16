package dev.sunlit.bettersunlit.perf;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Iterative cartesian product replacement for {@code StateDefinition.<init>} that avoids
 * nested Java {@code Stream.flatMap}, intermediate {@code ArrayList} copies, {@code Pair} boxing,
 * and un-sized {@code ImmutableMap.toImmutableMap} collector overhead.
 */
public final class StateDefinitionPerf {
    private StateDefinitionPerf() {
    }

    public static <O, S extends StateHolder<O, S>> void populateAllStates(
            ImmutableSortedMap<String, Property<?>> propertiesByName,
            StateDefinition.Factory<O, S> factory,
            O owner,
            MapCodec<S> codec,
            Map<Map<Property<?>, Comparable<?>>, S> byValues,
            List<S> statesOut) {
        int propCount = propertiesByName.size();
        if (propCount == 0) {
            ImmutableMap<Property<?>, Comparable<?>> empty = ImmutableMap.of();
            S state = factory.m_61106_(owner, empty, codec);
            byValues.put(empty, state);
            statesOut.add(state);
            return;
        }

        Property<?>[] props = new Property<?>[propCount];
        Comparable<?>[][] values = new Comparable<?>[propCount][];
        int pIdx = 0;
        for (Property<?> prop : propertiesByName.values()) {
            props[pIdx] = prop;
            Collection<? extends Comparable<?>> possible = prop.m_6908_();
            values[pIdx] = possible.toArray(new Comparable<?>[possible.size()]);
            if (values[pIdx].length == 0) {
                return;
            }
            pIdx++;
        }

        int[] indices = new int[propCount];
        while (true) {
            ImmutableMap.Builder<Property<?>, Comparable<?>> builder =
                    ImmutableMap.builderWithExpectedSize(propCount);
            for (int i = 0; i < propCount; i++) {
                builder.put(props[i], values[i][indices[i]]);
            }
            ImmutableMap<Property<?>, Comparable<?>> propMap = builder.build();
            S state = factory.m_61106_(owner, propMap, codec);
            byValues.put(propMap, state);
            statesOut.add(state);

            int carry = propCount - 1;
            while (carry >= 0) {
                if (++indices[carry] < values[carry].length) {
                    break;
                }
                indices[carry] = 0;
                carry--;
            }
            if (carry < 0) {
                break;
            }
        }
    }
}
