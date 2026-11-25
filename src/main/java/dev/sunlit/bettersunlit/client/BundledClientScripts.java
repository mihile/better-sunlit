package dev.sunlit.bettersunlit.client;

import dev.latvian.mods.kubejs.script.ScriptFile;
import dev.latvian.mods.kubejs.script.ScriptFileInfo;
import dev.latvian.mods.kubejs.script.ScriptManager;
import dev.latvian.mods.kubejs.script.ScriptPack;
import dev.latvian.mods.kubejs.script.ScriptPackInfo;
import dev.latvian.mods.kubejs.script.ScriptSource;
import dev.latvian.mods.kubejs.script.ScriptType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import net.minecraftforge.fml.ModList;

/** Supplies the client recipe cards without writing into the user's KubeJS directory. */
public final class BundledClientScripts {
    private BundledClientScripts() {}

    public static void install(ScriptManager manager) {
        if (manager.scriptType != ScriptType.CLIENT || !ModList.get().isLoaded("jei")
                || !ModList.get().isLoaded("kubejsadditions")) return;
        for (String name : new String[]{"fishingJei.js", "plantJei.js"}) {
            boolean present = manager.packs.values().stream()
                    .flatMap(pack -> pack.scripts.stream())
                    .anyMatch(file -> file.info.file.equals(name) || file.info.file.endsWith("/" + name));
            if (present) continue;
            ScriptPackInfo packInfo = new ScriptPackInfo("better_sunlit", "kubejs");
            ScriptPack pack = manager.packs.computeIfAbsent("better_sunlit",
                    key -> new ScriptPack(manager, packInfo));
            ScriptFileInfo info = new ScriptFileInfo(pack.info, name);
            ScriptSource source = ignored -> {
                try (var input = BundledClientScripts.class.getResourceAsStream(
                        "/assets/better_sunlit/scripts/" + name)) {
                    if (input == null) throw new IOException("Missing bundled script: " + name);
                    return Arrays.asList(new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R", -1));
                }
            };
            try {
                info.preload(source);
                if (info.skipLoading().isEmpty()) pack.scripts.add(new ScriptFile(pack, info, source));
            } catch (Throwable error) {
                throw new IllegalStateException("Cannot prepare Better Sunlit script: " + name, error);
            }
        }
    }
}
