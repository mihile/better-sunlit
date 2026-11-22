package dev.sunlit.bettersunlit.client;

import com.google.common.hash.Hashing;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

public final class BurnedModelCompat {
    private static final ResourceLocation MODEL =
            new ResourceLocation("rottencreatures", "optifine/cem/burned.jem");
    private static final ResourceLocation ANIMATIONS =
            new ResourceLocation("rottencreatures", "optifine/cem/burned_animations.jpm");
    private static final String REPAIRED_MODEL = "/assets/better_sunlit/compat/burned.json";
    private static final String ORIGINAL_MODEL_SHA256 =
            "e48b793909bb2af6d1b681c8bd3e54b4d346c48777a11ef891009104076d34cd";
    private static final String ORIGINAL_ANIMATIONS_SHA256 =
            "cc574cda7184f3b38d8f518149b9fe29a47aa74436d380e40abe8c1ee070ea66";

    private BurnedModelCompat() {
    }

    public static Optional<Resource> repair(
            ResourceManager manager, ResourceLocation location, Optional<Resource> selected) {
        if (!MODEL.equals(location) || selected.isEmpty()) {
            return selected;
        }
        Resource model = selected.get();
        try {
            if (!matches(model, 3349, ORIGINAL_MODEL_SHA256)) {
                return selected;
            }
            Optional<Resource> animations = manager.m_213713_(ANIMATIONS);
            if (animations.isEmpty() || !matches(animations.get(), 9022, ORIGINAL_ANIMATIONS_SHA256)) {
                return selected;
            }
            try (InputStream patch = BurnedModelCompat.class.getResourceAsStream(REPAIRED_MODEL)) {
                if (patch == null) {
                    return selected;
                }
                byte[] replacement = patch.readAllBytes();
                return Optional.of(new Resource(model.m_247173_(),
                        () -> new ByteArrayInputStream(replacement), model::m_215509_));
            }
        } catch (IOException ignored) {
            return selected;
        }
    }

    private static boolean matches(Resource resource, int size, String digest) throws IOException {
        try (InputStream stream = resource.m_215507_()) {
            byte[] bytes = stream.readNBytes(size + 1);
            return bytes.length == size && Hashing.sha256().hashBytes(bytes).toString().equals(digest);
        }
    }
}
