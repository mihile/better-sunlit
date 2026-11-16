package dev.sunlit.bootagent.payload;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time payload spliced into {@code java.lang.module.Resolver.makeGraph(Configuration)}.
 * Replaces the cubic fixed-point requires-transitive loop with BitSet transitive closure.
 * Uses only {@code java.base} collections and avoids lambdas/inner classes.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class BootResolverPayload {
    public static void closeGraph(Map g1, Map g2) {
        if (g1.isEmpty() || g2.isEmpty()) {
            return;
        }
        int g2Size = g2.size();
        ArrayList mods = new ArrayList(g2Size * 2);
        HashMap modIdx = new HashMap(g2Size * 4);
        Set[] g2RawSets = new Set[g2Size];
        int keyCount = 0;

        Iterator entryIt = g2.entrySet().iterator();
        while (entryIt.hasNext()) {
            Map.Entry entry = (Map.Entry) entryIt.next();
            Object key = entry.getKey();
            Set valSet = (Set) entry.getValue();
            if (!modIdx.containsKey(key)) {
                modIdx.put(key, Integer.valueOf(keyCount));
                mods.add(key);
                g2RawSets[keyCount] = valSet;
                keyCount++;
            } else {
                int existing = ((Integer) modIdx.get(key)).intValue();
                g2RawSets[existing] = valSet;
            }
        }

        int totalNodes = keyCount;
        BitSet[] reach = new BitSet[keyCount];
        for (int i = 0; i < keyCount; i++) {
            Set s = g2RawSets[i];
            if (s == null || s.isEmpty()) {
                continue;
            }
            BitSet bs = new BitSet(totalNodes + 8);
            Iterator valIt = s.iterator();
            while (valIt.hasNext()) {
                Object dst = valIt.next();
                Integer idObj = (Integer) modIdx.get(dst);
                int id;
                if (idObj == null) {
                    id = totalNodes++;
                    modIdx.put(dst, Integer.valueOf(id));
                    mods.add(dst);
                } else {
                    id = idObj.intValue();
                }
                bs.set(id);
            }
            reach[i] = bs;
        }

        for (int i = 0; i < keyCount; i++) {
            BitSet row = reach[i];
            if (row == null) {
                continue;
            }
            BitSet frontier = new BitSet(totalNodes);
            frontier.or(row);
            BitSet next = new BitSet(totalNodes);
            while (!frontier.isEmpty()) {
                next.clear();
                for (int j = frontier.nextSetBit(0); j >= 0 && j < keyCount; j = frontier.nextSetBit(j + 1)) {
                    BitSet targetRow = reach[j];
                    if (targetRow != null) {
                        next.or(targetRow);
                    }
                }
                next.andNot(row);
                if (next.isEmpty()) {
                    break;
                }
                row.or(next);
                BitSet tmp = frontier;
                frontier = next;
                next = tmp;
            }
        }

        BitSet cur = new BitSet(totalNodes);
        BitSet extra = new BitSet(totalNodes);
        Iterator g1It = g1.values().iterator();
        while (g1It.hasNext()) {
            Set d1 = (Set) g1It.next();
            if (d1.isEmpty()) {
                continue;
            }
            cur.clear();
            extra.clear();
            Iterator d1It = d1.iterator();
            while (d1It.hasNext()) {
                Object m = d1It.next();
                Integer idObj = (Integer) modIdx.get(m);
                if (idObj != null) {
                    int id = idObj.intValue();
                    cur.set(id);
                    if (id < keyCount) {
                        BitSet row = reach[id];
                        if (row != null) {
                            extra.or(row);
                        }
                    }
                }
            }
            extra.andNot(cur);
            if (!extra.isEmpty()) {
                for (int b = extra.nextSetBit(0); b >= 0; b = extra.nextSetBit(b + 1)) {
                    d1.add(mods.get(b));
                }
            }
        }
    }
}
