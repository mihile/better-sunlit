package dev.sunlit.bettersunlit.client;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.network.syncher.EntityDataSerializer;
import net.minecraft.network.syncher.SynchedEntityData.DataItem;
import net.minecraft.network.syncher.SynchedEntityData.DataValue;

import java.util.ArrayList;
import java.util.List;

public final class MinecartDataCompatibility {
    // More Minecarts 1.8.3 allocates both custom fields on FlagCartEntity.
    // The minecart base uses slots 0–13, and class initialization order assigns 14 and 15.
    private static final int FIRST = 14;
    private static final int SECOND = 15;

    private MinecartDataCompatibility() {}

    public static List<DataValue<?>> align(EntityDataSerializer<?> expected, Int2ObjectMap<DataItem<?>> items,
                                            List<DataValue<?>> values) {
        var first = items.get(FIRST);
        var second = items.get(SECOND);
        int local;
        int remote;
        if (first != null && first.m_135396_().m_135016_() == expected && second == null) {
            local = FIRST;
            remote = SECOND;
        } else if (second != null && second.m_135396_().m_135016_() == expected && first == null) {
            local = SECOND;
            remote = FIRST;
        } else {
            return values;
        }

        int alias = -1;
        for (int i = 0; i < values.size(); i++) {
            var value = values.get(i);
            if (value.f_252469_() == local) return values;
            if (value.f_252469_() == remote) {
                if (alias != -1 || value.f_252511_() != expected) return values;
                alias = i;
            }
        }
        if (alias == -1) return values;

        var result = new ArrayList<>(values);
        result.set(alias, renumber(local, values.get(alias)));
        return result;
    }

    private static <T> DataValue<T> renumber(int slot, DataValue<T> value) {
        return new DataValue<>(slot, value.f_252511_(), value.f_252525_());
    }
}
