package dev.sunlit.bootagent.payload;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time payload spliced into {@code java.lang.module.Resolver}.
 * Deduplicates shared automatic-module read graphs, replaces the cubic fixed-point
 * requires-transitive loop with BitSet transitive closure, and accelerates
 * {@code checkExportSuppliers}. Uses only {@code java.base} collections and avoids
 * lambdas/inner classes.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class BootResolverPayload {
    public static void hasTwoAutoModules(Map nameToReference, boolean[] out) {
        if (nameToReference == null || nameToReference.size() < 2) {
            return;
        }
        int autoCount = 0;
        Iterator it = nameToReference.values().iterator();
        while (it.hasNext()) {
            ModuleReference mref = (ModuleReference) it.next();
            if (mref.descriptor().isAutomatic()) {
                autoCount++;
                if (autoCount >= 2) {
                    out[0] = true;
                    return;
                }
            }
        }
    }

    public static void collectParentModules(List parents, Set autoD1, Set autoD2) {
        if (parents == null || parents.isEmpty()) {
            return;
        }
        HashSet seenCfg = new HashSet(parents.size() * 4 + 4);
        ArrayList stack = new ArrayList(parents);
        while (!stack.isEmpty()) {
            Configuration pcf = (Configuration) stack.remove(stack.size() - 1);
            if (pcf == null || !seenCfg.add(pcf)) {
                continue;
            }
            Set pMods = pcf.modules();
            Iterator pmIt = pMods.iterator();
            while (pmIt.hasNext()) {
                ResolvedModule pm = (ResolvedModule) pmIt.next();
                autoD1.add(pm);
                if (pm.reference().descriptor().isAutomatic()) {
                    autoD2.add(pm);
                }
            }
            List gp = pcf.parents();
            if (gp != null && !gp.isEmpty()) {
                stack.addAll(gp);
            }
        }
    }

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
        IdentityHashMap sharedInitRows = new IdentityHashMap(16);
        for (int i = 0; i < keyCount; i++) {
            Set s = g2RawSets[i];
            if (s == null || s.isEmpty()) {
                continue;
            }
            BitSet cached = (BitSet) sharedInitRows.get(s);
            if (cached != null) {
                reach[i] = cached;
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
            sharedInitRows.put(s, bs);
            reach[i] = bs;
        }

        IdentityHashMap closedRows = new IdentityHashMap(16);
        for (int i = 0; i < keyCount; i++) {
            BitSet row = reach[i];
            if (row == null || closedRows.containsKey(row)) {
                continue;
            }
            closedRows.put(row, Boolean.TRUE);
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
        IdentityHashMap seenD1Sets = new IdentityHashMap(32);
        Iterator g1It = g1.values().iterator();
        while (g1It.hasNext()) {
            Set d1 = (Set) g1It.next();
            if (d1.isEmpty() || seenD1Sets.containsKey(d1)) {
                continue;
            }
            seenD1Sets.put(d1, Boolean.TRUE);
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

    /**
     * Generation-stamped int-array verifier equivalent to {@code Resolver.checkExportSuppliers}.
     * Sets {@code okOut[0] = true} when all checks pass, or leaves false to delegate error formatting to JDK code.
     */
    public static void fastCheckExportSuppliers(Map g1, boolean[] okOut) {
        if (g1 == null || g1.size() <= 8) {
            return;
        }
        IdentityHashMap modIds = new IdentityHashMap(g1.size() * 2 + 32);
        ArrayList descs = new ArrayList(g1.size() * 2);
        IdentityHashMap seenInitReads = new IdentityHashMap(32);

        Iterator initIt = g1.entrySet().iterator();
        while (initIt.hasNext()) {
            Map.Entry e = (Map.Entry) initIt.next();
            ModuleDescriptor md = ((ResolvedModule) e.getKey()).reference().descriptor();
            if (!modIds.containsKey(md)) {
                modIds.put(md, Integer.valueOf(descs.size()));
                descs.add(md);
            }
            Set reads = (Set) e.getValue();
            if (seenInitReads.containsKey(reads)) {
                continue;
            }
            seenInitReads.put(reads, Boolean.TRUE);
            Iterator rIt = reads.iterator();
            while (rIt.hasNext()) {
                ModuleDescriptor od = ((ResolvedModule) rIt.next()).reference().descriptor();
                if (!modIds.containsKey(od)) {
                    modIds.put(od, Integer.valueOf(descs.size()));
                    descs.add(od);
                }
            }
        }

        int mCount = descs.size();
        boolean[] isAuto = new boolean[mCount];
        String[] modNames = new String[mCount];
        HashMap modNameIds = new HashMap(mCount * 2);
        int[] nameIdPerMod = new int[mCount];
        HashMap pkgIds = new HashMap(8192);
        int[][] selfPkgs = new int[mCount][];
        int[][] unqualExports = new int[mCount][];
        int[][] qualPkgIds = new int[mCount][];
        Set[][] qualTargets = new Set[mCount][];
        int[][] servicePkgs = new int[mCount][];

        int pkgCount = 0;
        int nameCount = 0;
        for (int i = 0; i < mCount; i++) {
            ModuleDescriptor d = (ModuleDescriptor) descs.get(i);
            String mName = d.name();
            modNames[i] = mName;
            Integer nid = (Integer) modNameIds.get(mName);
            if (nid == null) {
                nid = Integer.valueOf(nameCount++);
                modNameIds.put(mName, nid);
            }
            nameIdPerMod[i] = nid.intValue();
            isAuto[i] = d.isAutomatic();

            Set pkgs = d.packages();
            int[] sp = new int[pkgs.size()];
            int si = 0;
            Iterator pIt = pkgs.iterator();
            while (pIt.hasNext()) {
                String p = (String) pIt.next();
                Integer pid = (Integer) pkgIds.get(p);
                if (pid == null) {
                    pid = Integer.valueOf(pkgCount++);
                    pkgIds.put(p, pid);
                }
                sp[si++] = pid.intValue();
            }
            selfPkgs[i] = sp;

            if (isAuto[i]) {
                unqualExports[i] = sp;
            } else {
                Set exports = d.exports();
                if (!exports.isEmpty()) {
                    int[] uTmp = new int[exports.size()];
                    int uCnt = 0;
                    int[] qTmp = new int[exports.size()];
                    Set[] qtTmp = new Set[exports.size()];
                    int qCnt = 0;
                    Iterator exIt = exports.iterator();
                    while (exIt.hasNext()) {
                        ModuleDescriptor.Exports ex = (ModuleDescriptor.Exports) exIt.next();
                        String src = ex.source();
                        Integer pid = (Integer) pkgIds.get(src);
                        if (pid == null) {
                            pid = Integer.valueOf(pkgCount++);
                            pkgIds.put(src, pid);
                        }
                        if (ex.isQualified()) {
                            qTmp[qCnt] = pid.intValue();
                            qtTmp[qCnt] = ex.targets();
                            qCnt++;
                        } else {
                            uTmp[uCnt++] = pid.intValue();
                        }
                    }
                    if (uCnt > 0) {
                        int[] trimmed = new int[uCnt];
                        System.arraycopy(uTmp, 0, trimmed, 0, uCnt);
                        unqualExports[i] = trimmed;
                    }
                    if (qCnt > 0) {
                        int[] qTrim = new int[qCnt];
                        Set[] qtTrim = new Set[qCnt];
                        System.arraycopy(qTmp, 0, qTrim, 0, qCnt);
                        System.arraycopy(qtTmp, 0, qtTrim, 0, qCnt);
                        qualPkgIds[i] = qTrim;
                        qualTargets[i] = qtTrim;
                    }
                }
            }
        }

        // Resolve required service packages AFTER pkgIds includes exports from all modules.
        for (int i = 0; i < mCount; i++) {
            if (isAuto[i]) {
                continue;
            }
            ModuleDescriptor d = (ModuleDescriptor) descs.get(i);
            Set uses = d.uses();
            Set provides = d.provides();
            int scTotal = uses.size() + provides.size();
            if (scTotal > 0) {
                int[] sArr = new int[scTotal];
                int k = 0;
                Iterator uIt = uses.iterator();
                while (uIt.hasNext()) {
                    String svc = (String) uIt.next();
                    int dot = svc.lastIndexOf('.');
                    String spkg = dot >= 0 ? svc.substring(0, dot) : "";
                    Integer pid = (Integer) pkgIds.get(spkg);
                    if (pid == null) {
                        return;
                    }
                    sArr[k++] = pid.intValue();
                }
                Iterator prIt = provides.iterator();
                while (prIt.hasNext()) {
                    String svc = ((ModuleDescriptor.Provides) prIt.next()).service();
                    int dot = svc.lastIndexOf('.');
                    String spkg = dot >= 0 ? svc.substring(0, dot) : "";
                    Integer pid = (Integer) pkgIds.get(spkg);
                    if (pid == null) {
                        return;
                    }
                    sArr[k++] = pid.intValue();
                }
                servicePkgs[i] = sArr;
            }
        }

        int[] pkgGen = new int[pkgCount];
        int[] nameGen = new int[nameCount];
        int gen = 0;

        Set cachedAutoReads = null;
        boolean cachedAutoTried = false;
        boolean cachedAutoClean = false;
        boolean[] cachedAutoMembers = null;
        HashSet cachedQualTargets = null;

        Iterator checkIt = g1.entrySet().iterator();
        while (checkIt.hasNext()) {
            Map.Entry e = (Map.Entry) checkIt.next();
            ModuleDescriptor mDesc = ((ResolvedModule) e.getKey()).reference().descriptor();
            int mId = ((Integer) modIds.get(mDesc)).intValue();
            String mName = modNames[mId];
            Set reads = (Set) e.getValue();

            if (isAuto[mId] && servicePkgs[mId] == null && reads.size() > 16) {
                if (!cachedAutoTried) {
                    cachedAutoTried = true;
                    cachedAutoReads = reads;
                    cachedAutoMembers = new boolean[mCount];
                    cachedQualTargets = new HashSet(64);
                    boolean clean = true;
                    gen++;
                    Iterator preIt = reads.iterator();
                    while (preIt.hasNext()) {
                        ModuleDescriptor oDesc = ((ResolvedModule) preIt.next()).reference().descriptor();
                        int oId = ((Integer) modIds.get(oDesc)).intValue();
                        cachedAutoMembers[oId] = true;
                        int oNid = nameIdPerMod[oId];
                        if (nameGen[oNid] == gen) {
                            clean = false;
                        } else {
                            nameGen[oNid] = gen;
                        }
                        int[] uq = unqualExports[oId];
                        if (uq != null) {
                            for (int i = 0; i < uq.length; i++) {
                                int p = uq[i];
                                if (pkgGen[p] == gen) {
                                    clean = false;
                                } else {
                                    pkgGen[p] = gen;
                                }
                            }
                        }
                        if (qualTargets[oId] != null) {
                            Set[] qts = qualTargets[oId];
                            for (int i = 0; i < qts.length; i++) {
                                cachedQualTargets.addAll(qts[i]);
                            }
                        }
                    }
                    cachedAutoClean = clean;
                }
                if (cachedAutoClean
                        && reads == cachedAutoReads
                        && cachedAutoMembers[mId]
                        && !cachedQualTargets.contains(mName)) {
                    continue;
                }
            }

            gen++;
            nameGen[nameIdPerMod[mId]] = gen;

            int[] sp = selfPkgs[mId];
            for (int i = 0; i < sp.length; i++) {
                pkgGen[sp[i]] = gen;
            }

            Iterator rIt = reads.iterator();
            while (rIt.hasNext()) {
                ModuleDescriptor oDesc = ((ResolvedModule) rIt.next()).reference().descriptor();
                int oId = ((Integer) modIds.get(oDesc)).intValue();
                if (oId != mId) {
                    int oNid = nameIdPerMod[oId];
                    if (nameGen[oNid] == gen) {
                        return;
                    }
                    nameGen[oNid] = gen;
                }
                if (isAuto[oId] && oId == mId) {
                    continue;
                }
                int[] uq = unqualExports[oId];
                if (uq != null) {
                    for (int i = 0; i < uq.length; i++) {
                        int p = uq[i];
                        if (pkgGen[p] == gen) {
                            return;
                        }
                        pkgGen[p] = gen;
                    }
                }
                int[] qp = qualPkgIds[oId];
                if (qp != null) {
                    Set[] qts = qualTargets[oId];
                    for (int i = 0; i < qp.length; i++) {
                        if (qts[i].contains(mName)) {
                            int p = qp[i];
                            if (pkgGen[p] == gen) {
                                return;
                            }
                            pkgGen[p] = gen;
                        }
                    }
                }
            }

            int[] reqSvc = servicePkgs[mId];
            if (reqSvc != null) {
                for (int i = 0; i < reqSvc.length; i++) {
                    if (pkgGen[reqSvc[i]] != gen) {
                        return;
                    }
                }
            }
        }
        okOut[0] = true;
    }
}
