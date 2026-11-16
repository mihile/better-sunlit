package org.spongepowered.asm.mixin.transformer;

import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        List cfgs = proc.configs;
        for (int i = 0, n = cfgs.size(); i < n; i++) {
            MixinConfig cfg = (MixinConfig) cfgs.get(i);
            String pkg = cfg.getMixinPackage();
            if (pkg != null && !pkg.isEmpty()) {
                prefixList.add(pkg);
            }
            Map mapping = cfg.mixinMapping;
            if (mapping != null && !mapping.isEmpty()) {
                Iterator it = mapping.keySet().iterator();
                while (it.hasNext()) {
                    String t = (String) it.next();
                    exact.add(t);
                    if (t.indexOf('/') >= 0) {
                        exact.add(t.replace('/', '.'));
                    }
                }
            }
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
        SUNLIT_SNAPSHOT = new Object[]{env, exact, prefixes};
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
}
