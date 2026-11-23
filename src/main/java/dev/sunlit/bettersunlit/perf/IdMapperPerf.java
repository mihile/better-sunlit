package dev.sunlit.bettersunlit.perf;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenCustomHashMap;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Helpers for pre-sizing {@code IdMapper} backing structures before bulk registry baking.
 */
public final class IdMapperPerf {
    public static final ResourceLocation BLOCKSTATE_TO_ID = new ResourceLocation("minecraft", "blockstatetoid");
    private static final Set<Object2IntMap<?>> PRESIZED_MAPS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private IdMapperPerf() {
    }

    public static <T> Object2IntMap<T> presizeMap(Object2IntMap<T> existing, List<T> list, int expectedSize) {
        if (list instanceof ArrayList<T> arrayList && expectedSize > 512) {
            arrayList.ensureCapacity(expectedSize);
        }
        if (expectedSize <= 512 || !(existing instanceof Object2IntOpenCustomHashMap<T> openMap)) {
            return existing;
        }
        if (PRESIZED_MAPS.contains(existing)) {
            return existing;
        }
        Object2IntOpenCustomHashMap<T> sized = new Object2IntOpenCustomHashMap<>(
                expectedSize, Object2IntOpenCustomHashMap.DEFAULT_LOAD_FACTOR, openMap.strategy());
        sized.defaultReturnValue(existing.defaultReturnValue());
        if (!existing.isEmpty()) {
            sized.putAll(existing);
        }
        PRESIZED_MAPS.add(sized);
        return sized;
    }
}
