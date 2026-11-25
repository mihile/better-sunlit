import com.google.gson.Gson;
import dev.sunlit.bettersunlit.client.RottenCreaturesModelCompat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.DrownedModel;
import net.minecraft.client.model.SkeletonModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import traben.entity_model_features.EMFManager;
import traben.entity_model_features.models.EMFModel_ID;
import traben.entity_model_features.models.parts.EMFModelPartRoot;
import traben.entity_model_features.utils.EMFDirectoryHandler;
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

    private static final Map<String, String> LACKEYS = Map.of(
            "skeleton_lackey", "skeleton_lackey_animations",
            "zombie_lackey", "zombie_lackey_animations",
            "zombie_lackey_outer_layer", "zombie_lackey_animations");

    public static void main(String[] args) throws Exception {
        Path base = Path.of(args[0]);
        net.minecraftforge.fml.loading.FMLPaths.loadAbsolutePaths(Path.of("build/emf-compat/game").toAbsolutePath());
        checkPrebakeExclusion();
        try (var manager = open(base)) {
            for (String model : MODELS) {
                assertRepaired(manager, model);
            }
            for (var entry : LACKEYS.entrySet()) {
                assertInlined(manager, entry.getKey(), entry.getValue());
            }
            for (String head : List.of("frostbitten_head", "immortal_head", "mummy_head", "swampy_head",
                    "undead_miner_head", "zap_head")) {
                assertUnchanged(manager, location(head));
                try (var reader = manager.m_213713_(location(head)).orElseThrow().m_215508_()) {
                    var model = new Gson().fromJson(reader, EMFJemData.class);
                    check(model.models.stream().allMatch(part -> (part.model == null || part.model.isEmpty())
                            && (part.animations == null || part.animations.isEmpty())),
                            head + " is a static skull model");
                }
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
                        "immortal_animations.jpm", "{\"animations\":[]}",
                        "zombie_lackey_animations.jpm", "{\"animations\":[]}").entrySet()) {
                    zip.putNextEntry(new ZipEntry(ASSETS + entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            try (var manager = open(base, overrides)) {
                for (String custom : List.of("burned", "mummy", "immortal", "immortal_overlay", "zombie_lackey", "zombie_lackey_outer_layer")) {
                    assertUnchanged(manager, location(custom));
                }
                assertRepaired(manager, "frostbitten");
            }
        } finally {
            Files.deleteIfExists(overrides);
        }
        System.out.println("PASS: Rotten Creatures animation registration for 9 mobs and 2 layers, static heads, pack overrides, and render-thread bake selection.");
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
            checkAnimationRegistration(model, name);
            check(root.model.isEmpty() && root.animations.size() == 5, name + " embedded animation sequence");
        }
    }

    private static void assertInlined(MultiPackResourceManager manager, String name, String animation) throws Exception {
        var location = location(name);
        var original = manager.m_213713_(location);
        var repaired = RottenCreaturesModelCompat.repair(manager, location, original).orElseThrow();
        check(repaired != original.orElseThrow(), name + " animation substitution");
        try (var reader = repaired.m_215508_();
             var originalAnimation = manager.m_213713_(
                     new ResourceLocation("rottencreatures", CEM + animation + ".jpm")).orElseThrow().m_215508_()) {
            var model = new Gson().fromJson(reader, EMFJemData.class);
            var source = com.google.gson.JsonParser.parseReader(originalAnimation).getAsJsonObject();
            var root = model.models.stream().filter(part -> "root".equals(part.id)).findFirst().orElseThrow();
            check(root.model.isEmpty() && new Gson().toJsonTree(root.animations).equals(source.get("animations")),
                    name + " keeps original animation sequence");
            checkAnimationRegistration(model, name);
        }
    }

    private static void checkPrebakeExclusion() throws Exception {
        var method = Class.forName("dev.sunlit.bettersunlit.client.EmfModelPrebakePerf")
                .getDeclaredMethod("isSpecialLayer", ModelLayerLocation.class);
        method.setAccessible(true);
        for (String name : MODELS) {
            var layer = new ModelLayerLocation(new ResourceLocation("rottencreatures", name), "main");
            check((boolean) method.invoke(null, layer), name + " stays in the renderer bake");
        }
        check(!(boolean) method.invoke(null, new ModelLayerLocation(new ResourceLocation("minecraft", "zombie"), "main")),
                "vanilla zombie still prebakes");
    }

    private static void checkAnimationRegistration(EMFJemData model, String name) throws Exception {
        var id = new EMFModel_ID(name);
        id.namespace = "rottencreatures";
        var directory = fixtureDirectory(name);
        LayerDefinition layer = switch (name) {
            case "skeleton_lackey" -> SkeletonModel.m_170942_();
            case "zombie_lackey", "zombie_lackey_outer_layer" -> DrownedModel.m_170535_(CubeDeformation.f_171458_);
            default -> rottenLayer(name);
        };
        model.prepare(directory, id);
        var root = new EMFModelPartRoot(id, directory, layer.m_171564_(), List.of(), new java.util.HashMap<>());
        root.addAndSetVariantOfJem(model, 1);
        EMFManager.getInstance().setupAnimationsFromJemToModel(model, root, 1);
        check(root.containsCustomAnims, name + " animation handler registered");
    }

    private static LayerDefinition rottenLayer(String name) throws Exception {
        String className = switch (name) {
            case "undead_miner" -> "UndeadMiner";
            case "immortal_overlay" -> "ImmortalOverlay";
            default -> Character.toUpperCase(name.charAt(0)) + name.substring(1);
        };
        var type = Class.forName("com.github.teamfusion.rottencreatures.client.rendering.model.entities." + className + "Model");
        return (LayerDefinition) type.getMethod(name.equals("immortal_overlay") ? "createLayer" : "createBodyLayer")
                .invoke(null);
    }

    // The animation validator reads Minecraft's frame timer, even without an active render state.
    private static EMFDirectoryHandler fixtureDirectory(String name) throws Exception {
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        var singleton = Minecraft.class.getDeclaredField("f_90981_");
        singleton.setAccessible(true);
        if (singleton.get(null) == null) {
            Object minecraft = unsafe.allocateInstance(Minecraft.class);
            var timer = Minecraft.class.getDeclaredField("f_90991_");
            timer.setAccessible(true);
            timer.set(minecraft, unsafe.allocateInstance(timer.getType()));
            singleton.set(null, minecraft);
        }
        var directory = (EMFDirectoryHandler) unsafe.allocateInstance(EMFDirectoryHandler.class);
        for (var entry : Map.of("namespace", "rottencreatures", "rawFileName", name,
                "suffixAndFileType", ".jem").entrySet()) {
            var member = EMFDirectoryHandler.class.getDeclaredField(entry.getKey());
            member.setAccessible(true);
            member.set(directory, entry.getValue());
        }
        return directory;
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
