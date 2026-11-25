package dev.sunlit.bettersunlit.client;

import com.google.common.hash.Hashing;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonParseException;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

public final class RottenCreaturesModelCompat {
    private static final String NAMESPACE = "rottencreatures";
    private static final String CEM_PATH = "optifine/cem/";
    private static final String PATCH_PATH = "/assets/better_sunlit/compat/rottencreatures/";
    private static final Fingerprint ZOMBIE_ANIMATIONS = new Fingerprint(9022,
            "cc574cda7184f3b38d8f518149b9fe29a47aa74436d380e40abe8c1ee070ea66");
    private static final Fingerprint IMMORTAL_ANIMATIONS = new Fingerprint(10049,
            "9612d2d0e9900877c2c6d2ba68c4b17bf3c8521f4b7e7a46eb010009417ae05d");
    private static final Fingerprint ZAP_ANIMATIONS = new Fingerprint(10223,
            "d64cdeeea16e7dc24b30d3b21f48dcf207d3ba8be67368766cbea3bffeda27f2");
    private static final Map<String, ModelPatch> PATCHES = Map.ofEntries(
            entry("burned", 3349,
                    "e48b793909bb2af6d1b681c8bd3e54b4d346c48777a11ef891009104076d34cd",
                    "burned_animations.jpm", ZOMBIE_ANIMATIONS),
            entry("frostbitten", 3446,
                    "19219d88e0de99b5a1f6303d85d9197dff6de0b381d76f89447c9a698cbf5d4d",
                    "frostbitten_animations.jpm", ZOMBIE_ANIMATIONS),
            entry("immortal", 4282,
                    "8f641839db0e3da9d3ae7ef376297e2d96f5c4e7ac9a1693d5261acd3f4313e3",
                    "immortal_animations.jpm", IMMORTAL_ANIMATIONS),
            entry("immortal_overlay", 1162,
                    "bad701e7fd7a1472ab9c89b03c5dd81bc12f9a49e1bcb529e20832b355c1fbba",
                    "immortal_animations.jpm", IMMORTAL_ANIMATIONS),
            entry("mummy", 5404,
                    "bd0f8c75934543c7da1bdc7b13e62beb474c9cff3e5aa1ddffe2aff8bd0b233b",
                    "mummy_animations.jpm", new Fingerprint(10107,
                            "3452afbb8aff85503ad0d37e48efeed4b5aad47383102175e0a6a21dd8bdd8b4")),
            entry("swampy", 5277,
                    "eeb34b7da06402d427026aa2371516bdb6d4a12b700e852c3c2a7d1ecb0eed99",
                    "swampy_animations.jpm", new Fingerprint(10309,
                            "6fa5ca03d60a1a4be295d9c28fea04aadeed875c78f00982ce352b96cf82bd11")),
            entry("undead_miner", 3080,
                    "848b134dec8c454b76a39c01e9da3716ba8467c1ab152a43c18dc1714ec3b175",
                    "undead_miner_animations.jpm", ZOMBIE_ANIMATIONS),
            entry("zap", 3739,
                    "2b9692b50ad877ba66adc0b3a9bb63d3d4698735937cdd5f1f72d977b70f73eb",
                    "zap_animations.jpm", ZAP_ANIMATIONS),
            inlineEntry("skeleton_lackey", 1734,
                    "f3d493908257430b2ea162fe7bd941e9fd06f1111fdd56218fe23cc32c520ff4",
                    "skeleton_lackey_animations.jpm", new Fingerprint(9584,
                            "76ccd18db9fa0455c570f83231208c11b1ddb8d4ea157dbef188898ac731faba")),
            inlineEntry("zombie_lackey", 3549,
                    "0d65c84f543eeb331320f03ba634a12672439ee33b460d8a4b048a1b2177b4ed",
                    "zombie_lackey_animations.jpm", ZAP_ANIMATIONS),
            inlineEntry("zombie_lackey_outer_layer", 2870,
                    "286531db0493b047d6897ce93938d5c085d094243f4f0eeea36bd19da5ffb504",
                    "zombie_lackey_animations.jpm", ZAP_ANIMATIONS)
    );

    private RottenCreaturesModelCompat() {
    }

    public static Optional<Resource> repair(
            ResourceManager manager, ResourceLocation location, Optional<Resource> selected) {
        if (!NAMESPACE.equals(location.m_135827_()) || selected.isEmpty()) {
            return selected;
        }
        ModelPatch patch = PATCHES.get(location.m_135815_());
        if (patch == null) {
            return selected;
        }

        // Keep custom overrides when either the model or its animation source has changed.
        Resource model = selected.get();
        try {
            byte[] modelBytes = patch.model.readIfMatching(model);
            if (modelBytes == null) return selected;
            Optional<Resource> animations = manager.m_213713_(patch.animationLocation);
            if (animations.isEmpty()) return selected;
            byte[] animationBytes = patch.animations.readIfMatching(animations.get());
            if (animationBytes == null) return selected;

            byte[] replacement;
            if (patch.asset == null) {
                replacement = inlineAnimations(modelBytes, animationBytes, patch.animationLocation);
            } else {
                try (InputStream corrected = RottenCreaturesModelCompat.class.getResourceAsStream(patch.asset)) {
                    if (corrected == null) return selected;
                    replacement = corrected.readAllBytes();
                }
            }
            if (replacement == null) return selected;
            return Optional.of(new Resource(model.m_247173_(),
                    () -> new ByteArrayInputStream(replacement), model::m_215509_));
        } catch (IOException | JsonParseException ignored) {
            return selected;
        }
    }

    private static byte[] inlineAnimations(byte[] modelBytes, byte[] animationBytes, ResourceLocation source) {
        JsonObject model = JsonParser.parseString(new String(modelBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject animation = JsonParser.parseString(new String(animationBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        String filename = source.m_135815_().substring(CEM_PATH.length());
        for (var element : model.getAsJsonArray("models")) {
            JsonObject part = element.getAsJsonObject();
            if (part.has("model") && filename.equals(part.get("model").getAsString())) {
                part.remove("model");
                part.add("animations", animation.getAsJsonArray("animations"));
                return model.toString().getBytes(StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static Map.Entry<String, ModelPatch> inlineEntry(
            String name, int size, String digest, String animationsName, Fingerprint animations) {
        return Map.entry(CEM_PATH + name + ".jem", new ModelPatch(new Fingerprint(size, digest),
                null, new ResourceLocation(NAMESPACE, CEM_PATH + animationsName), animations));
    }

    private static Map.Entry<String, ModelPatch> entry(
            String name, int size, String digest, String animationsName, Fingerprint animations) {
        return Map.entry(CEM_PATH + name + ".jem",
                new ModelPatch(new Fingerprint(size, digest), PATCH_PATH + name + ".json",
                        new ResourceLocation(NAMESPACE, CEM_PATH + animationsName), animations));
    }

    private record ModelPatch(Fingerprint model, String asset,
                              ResourceLocation animationLocation, Fingerprint animations) {
    }

    private record Fingerprint(int size, String sha256) {
        private byte[] readIfMatching(Resource resource) throws IOException {
            try (InputStream stream = resource.m_215507_()) {
                byte[] bytes = stream.readNBytes(size + 1);
                return bytes.length == size && Hashing.sha256().hashBytes(bytes).toString().equals(sha256) ? bytes : null;
            }
        }
    }
}
