import com.google.gson.Gson;
import dev.sunlit.bettersunlit.client.RottenCreaturesModelCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.server.packs.resources.Resource;
import traben.entity_model_features.models.jem_objects.EMFJemData;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class RottenCreaturesModelCompatTests {
    private static final String CEM = "optifine/cem/";
    private static final String ASSETS = "assets/rottencreatures/" + CEM;
    private static final String PATCH_ASSETS = "/assets/better_sunlit/compat/rottencreatures/";
    private static final List<String> MODELS = List.of(
            "burned", "frostbitten", "immortal", "immortal_overlay", "mummy", "swampy", "undead_miner", "zap");
    private static final List<String> INLINE_ANIMATIONS = List.of("immortal", "immortal_overlay", "mummy");

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);
        try (var manager = open(base)) {
            for (String model : MODELS) {
                assertRepaired(manager, model);
            }
            for (String untouched : List.of("frostbitten_head", "immortal_head", "mummy_head", "swampy_head",
                    "undead_miner_head", "zap_head", "skeleton_lackey", "zombie_lackey", "zombie_lackey_outer_layer")) {
                assertUnchanged(manager, location(untouched));
            }
        }
        try (var disabled = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of())) {
            assertUnchanged(disabled, location("burned"));
        }

        Path overrides = Files.createTempFile("better-sunlit-rotten-creatures-", ".zip");
        try {
            // A differently authored model or animation may have a different part hierarchy.
            try (var zip = new ZipOutputStream(Files.newOutputStream(overrides))) {
                for (var entry : Map.of("burned.jem", "{\"models\":[]}",
                        "mummy_animations.jpm", "{\"animations\":[]}",
                        "immortal_animations.jpm", "{\"animations\":[]}").entrySet()) {
                    zip.putNextEntry(new ZipEntry(ASSETS + entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            try (var manager = open(base, overrides)) {
                for (String custom : List.of("burned", "mummy", "immortal", "immortal_overlay")) {
                    assertUnchanged(manager, location(custom));
                }
                assertRepaired(manager, "frostbitten");
            }
        } finally {
            Files.deleteIfExists(overrides);
        }
        System.out.println("PASS: 7 Rotten Creatures mobs and Immortal overlay, custom overrides, head models, and disabled pack.");
    }

    private static MultiPackResourceManager open(Path base) {
        return new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
                List.of(new FilePackResources("base", base.toFile(), false)));
    }

    private static MultiPackResourceManager open(Path base, Path overrides) {
        return new MultiPackResourceManager(PackType.CLIENT_RESOURCES,
                List.of(new FilePackResources("base", base.toFile(), false),
                        new FilePackResources("custom", overrides.toFile(), false)));
    }

    private static ResourceLocation location(String name) {
        return new ResourceLocation("rottencreatures", CEM + name + ".jem");
    }

    private static void assertRepaired(MultiPackResourceManager manager, String name) throws Exception {
        var location = location(name);
        var original = manager.m_213713_(location);
        Resource repaired = RottenCreaturesModelCompat.repair(manager, location, original).orElseThrow();
        check(repaired != original.orElseThrow(), name + " model substitution");
        check(repaired.m_247173_() == original.get().m_247173_(), name + " resource pack identity");
        check(repaired.m_215509_() == original.get().m_215509_(), name + " resource metadata");
        try (var embedded = RottenCreaturesModelCompat.class.getResourceAsStream(PATCH_ASSETS + name + ".json");
             var stream = repaired.m_215507_()) {
            check(embedded != null && Arrays.equals(embedded.readAllBytes(), stream.readAllBytes()), name + " model bytes");
        }
        try (var reader = repaired.m_215508_()) {
            EMFJemData model = new Gson().fromJson(reader, EMFJemData.class);
            check(model.models.size() >= 3 && model.textureSize.length == 2, name + " parses as an EMF model");
            var root = model.models.stream().filter(part -> "root".equals(part.id)).findFirst().orElseThrow();
            check(INLINE_ANIMATIONS.contains(name) ? root.model.isEmpty() && root.animations.size() == 5
                    : root.model != null && root.model.endsWith("_animations.jpm"), name + " animation source");
        }
    }

    private static void assertUnchanged(MultiPackResourceManager manager, ResourceLocation location) {
        var original = manager.m_213713_(location);
        check(RottenCreaturesModelCompat.repair(manager, location, original) == original, location.toString());
    }

    private static void check(boolean passed, String message) {
        if (!passed) {
            throw new AssertionError(message);
        }
    }
}
