package dev.sunlit.bettersunlit.perf;

import dev.latvian.mods.kubejs.KubeJS;
import dev.latvian.mods.kubejs.script.ScriptFile;
import dev.latvian.mods.kubejs.script.ScriptPack;
import dev.latvian.mods.rhino.ClassShutter;
import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.util.Remapper;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;

/**
 * Shared non-Mixin helper for {@code PerfKubeJsScriptManagerMixin} and
 * {@code PerfKubeJsScriptFileMixin} so parallel Rhino compilation holds zero public methods
 * inside Mixin classes.
 */
public final class KubeJsScriptPerf {
    private static final ThreadLocal<IdentityHashMap<ScriptFile, Object>> COMPILED_TL = new ThreadLocal<>();

    private KubeJsScriptPerf() {
    }

    public static void parallelCompile(
            Context mainCx,
            Map<String, ScriptPack> packs,
            ClassShutter shutter) {
        if (packs == null || packs.isEmpty() || mainCx == null) {
            return;
        }
        ArrayList<ScriptFile> files = new ArrayList<>(96);
        int totalChars = 0;
        for (ScriptPack pack : packs.values()) {
            if (pack == null || pack.scripts == null) {
                continue;
            }
            for (ScriptFile file : pack.scripts) {
                if (file != null && file.info != null && file.info.lines != null && file.info.lines.length > 0) {
                    files.add(file);
                    for (String line : file.info.lines) {
                        if (line != null) {
                            totalChars += line.length();
                        }
                    }
                }
            }
        }
        if (files.size() < 8 || totalChars < 16384) {
            return;
        }

        final Remapper remapper = mainCx.getRemapper();
        final ClassLoader appCl = KubeJS.class.getClassLoader();
        final Object[] results = new Object[files.size()];

        int procs = Runtime.getRuntime().availableProcessors();
        int workers = Math.max(2, Math.min(8, Math.min(files.size() / 4, procs)));
        int chunkSize = (files.size() + workers - 1) / workers;
        List<ForkJoinTask<?>> tasks = new ArrayList<>(workers);
        for (int start = 0; start < files.size(); start += chunkSize) {
            final int s = start;
            final int e = Math.min(files.size(), start + chunkSize);
            tasks.add(ForkJoinPool.commonPool().submit(() -> {
                Context cx = Context.enter();
                cx.setClassShutter(shutter);
                cx.setRemapper(remapper);
                cx.setApplicationClassLoader(appCl);
                for (int i = s; i < e; i++) {
                    ScriptFile sf = files.get(i);
                    try {
                        String src = String.join("\n", sf.info.lines);
                        results[i] = cx.compileString(src, sf.info.location, 1, null);
                    } catch (Throwable err) {
                        results[i] = err;
                    }
                }
            }));
        }
        for (int i = 0; i < tasks.size(); i++) {
            tasks.get(i).join();
        }
        IdentityHashMap<ScriptFile, Object> map = new IdentityHashMap<>(files.size() * 2);
        for (int i = 0; i < files.size(); i++) {
            if (results[i] != null) {
                map.put(files.get(i), results[i]);
            }
        }
        COMPILED_TL.set(map);
    }

    public static Object takeCompiled(ScriptFile file) {
        IdentityHashMap<ScriptFile, Object> map = COMPILED_TL.get();
        return map == null || file == null ? null : map.remove(file);
    }

    public static void clearCompiled() {
        COMPILED_TL.remove();
    }
}
