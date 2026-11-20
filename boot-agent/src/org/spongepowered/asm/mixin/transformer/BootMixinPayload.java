package org.spongepowered.asm.mixin.transformer;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Compile-time payload whose static fields/methods are transplanted into {@code MixinProcessor}
 * by {@code SunlitBootAgent}. Uses only built-in Mixin classes and standard collections.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class BootMixinPayload {
    public static volatile Object[] SUNLIT_SNAPSHOT;

    public static void sunlitRebuildSnapshot(MixinProcessor proc) {
        if (proc == null || proc.errorState || !proc.pendingConfigs.isEmpty() || Mixins.getUnvisitedCount() != 0) {
            SUNLIT_SNAPSHOT = null;
            return;
        }
        MixinEnvironment env = proc.currentEnvironment;
        if (env == null || env != MixinEnvironment.getCurrentEnvironment()) {
            SUNLIT_SNAPSHOT = null;
            return;
        }
        HashSet exact = new HashSet(512);
        ArrayList prefixList = new ArrayList(96);
        HashMap targetMap = new HashMap(512);

        List cfgs = proc.configs;
        for (int i = 0, n = cfgs.size(); i < n; i++) {
            MixinConfig cfg = (MixinConfig) cfgs.get(i);
            String pkg = cfg.getMixinPackage();
            if (pkg != null && !pkg.isEmpty()) {
                prefixList.add(pkg);
            }
            Map mapping = cfg.mixinMapping;
            if (mapping != null && !mapping.isEmpty()) {
                Iterator it = mapping.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry entry = (Map.Entry) it.next();
                    String t = (String) entry.getKey();
                    exact.add(t);
                    String dotted = t;
                    if (t.indexOf('/') >= 0) {
                        dotted = t.replace('/', '.');
                        exact.add(dotted);
                    }
                    List list = (List) entry.getValue();
                    if (list != null && !list.isEmpty()) {
                        TreeSet set = (TreeSet) targetMap.get(dotted);
                        if (set == null) {
                            set = new TreeSet();
                            targetMap.put(dotted, set);
                        }
                        set.addAll(list);
                    }
                }
            }
        }

        Iterator mit = targetMap.entrySet().iterator();
        while (mit.hasNext()) {
            Map.Entry e = (Map.Entry) mit.next();
            TreeSet set = (TreeSet) e.getValue();
            e.setValue(set.toArray(new MixinInfo[0]));
        }

        MixinCoprocessors copros = proc.coprocessors;
        for (int i = 0, n = copros.size(); i < n; i++) {
            Object cp = copros.get(i);
            if (cp instanceof MixinCoprocessorPassthrough) {
                sunlitAddAllStrings(exact, ((MixinCoprocessorPassthrough) cp).loadable);
            } else if (cp instanceof MixinCoprocessorAccessor) {
                Map m = ((MixinCoprocessorAccessor) cp).accessorMixins;
                if (m != null) {
                    sunlitAddAllStrings(exact, m.keySet());
                }
            } else if (cp instanceof MixinCoprocessorNestHost) {
                Map m = ((MixinCoprocessorNestHost) cp).nestHosts;
                if (m != null) {
                    sunlitAddAllStrings(exact, m.keySet());
                }
            } else if (cp instanceof MixinCoprocessorSyntheticInner) {
                sunlitAddAllStrings(exact, ((MixinCoprocessorSyntheticInner) cp).syntheticInnerClasses);
            } else {
                SUNLIT_SNAPSHOT = null;
                return;
            }
        }

        String[] prefixes = (String[]) prefixList.toArray(new String[0]);
        SUNLIT_SNAPSHOT = new Object[]{env, exact, prefixes, targetMap};
    }

    private static void sunlitAddAllStrings(HashSet dst, Set src) {
        if (src == null || src.isEmpty()) {
            return;
        }
        Iterator it = src.iterator();
        while (it.hasNext()) {
            Object o = it.next();
            if (o instanceof String) {
                String s = (String) o;
                dst.add(s);
                if (s.indexOf('/') >= 0) {
                    dst.add(s.replace('/', '.'));
                }
            }
        }
    }

    public static boolean sunlitShouldSkip(Object transformer, String className) {
        Object[] snap = SUNLIT_SNAPSHOT;
        if (snap == null || className == null || !(transformer instanceof MixinTransformer)) {
            return false;
        }
        MixinProcessor proc = ((MixinTransformer) transformer).processor;
        if (proc == null || proc.errorState || !proc.pendingConfigs.isEmpty() || Mixins.getUnvisitedCount() != 0) {
            return false;
        }
        if (snap[0] != MixinEnvironment.getCurrentEnvironment() || snap[0] != proc.currentEnvironment) {
            return false;
        }
        HashSet exact = (HashSet) snap[1];
        if (exact.contains(className)) {
            return false;
        }
        String[] prefixes = (String[]) snap[2];
        for (int i = 0, n = prefixes.length; i < n; i++) {
            if (className.startsWith(prefixes[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns {@code Boolean.FALSE} to fall back to standard MixinConfig scan, {@code null} when
     * no mixins apply, or a fresh {@link SortedSet} of {@link MixinInfo}s.
     */
    public static Object sunlitLookupSortedMixins(MixinProcessor proc, MixinEnvironment env, String transformedName) {
        Object[] snap = SUNLIT_SNAPSHOT;
        if (snap == null || snap.length < 4 || proc == null || transformedName == null) {
            return Boolean.FALSE;
        }
        if (proc.errorState || !proc.pendingConfigs.isEmpty() || Mixins.getUnvisitedCount() != 0) {
            return Boolean.FALSE;
        }
        if (snap[0] != env || snap[0] != proc.currentEnvironment) {
            return Boolean.FALSE;
        }
        String[] prefixes = (String[]) snap[2];
        for (int i = 0, n = prefixes.length; i < n; i++) {
            if (transformedName.startsWith(prefixes[i])) {
                return Boolean.FALSE;
            }
        }
        HashMap targetMap = (HashMap) snap[3];
        MixinInfo[] arr = (MixinInfo[]) targetMap.get(transformedName);
        if (arr == null || arr.length == 0) {
            return null;
        }
        TreeSet set = new TreeSet();
        for (int i = 0, n = arr.length; i < n; i++) {
            set.add(arr[i]);
        }
        return set;
    }

    public static Object[] sunlitBuildMemberIndex(Set set) {
        int size = set.size();
        HashMap map = new HashMap(Math.max(16, size * 2));
        Iterator it = set.iterator();
        while (it.hasNext()) {
            ClassInfo.Member m = (ClassInfo.Member) it.next();
            String key = m.getName();
            Object prev = map.put(key, m);
            if (prev != null) {
                if (prev instanceof ArrayList) {
                    ((ArrayList) prev).add(m);
                    map.put(key, prev);
                } else {
                    ArrayList list = new ArrayList(4);
                    list.add(prev);
                    list.add(m);
                    map.put(key, list);
                }
            }
        }
        return new Object[]{Integer.valueOf(size), map};
    }

    public static ClassInfo.Member sunlitLookupIndexedMember(
            Object[] indexPair, Set initialisers, boolean isMethod, String name, String desc, int flags) {
        HashMap map = (HashMap) indexPair[1];
        Object entry = map.get(name);
        if (entry != null) {
            if (entry instanceof ClassInfo.Member) {
                ClassInfo.Member m = (ClassInfo.Member) entry;
                if (m.equals(name, desc) && m.matchesFlags(flags)) {
                    return m;
                }
            } else {
                ArrayList list = (ArrayList) entry;
                for (int i = 0, n = list.size(); i < n; i++) {
                    ClassInfo.Member m = (ClassInfo.Member) list.get(i);
                    if (m.equals(name, desc) && m.matchesFlags(flags)) {
                        return m;
                    }
                }
            }
        }
        if (isMethod && (flags & ClassInfo.INCLUDE_INITIALISERS) != 0 && initialisers != null && !initialisers.isEmpty()) {
            Iterator it = initialisers.iterator();
            while (it.hasNext()) {
                ClassInfo.Member m = (ClassInfo.Member) it.next();
                if (m.equals(name, desc) && m.matchesFlags(flags)) {
                    return m;
                }
            }
        }
        return null;
    }
}
