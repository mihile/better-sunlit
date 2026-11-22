package dev.sunlit.bettersunlit.client;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

/**
 * Translates Mystical Oak Tree wisdom lines on the client only when Korean is selected.
 */
public final class MysticalOakTreePerf {
    private static final String RESOURCE_PATH = "/assets/better_sunlit/mysticaloaktree/tree_wisdom_ko_kr.json";
    private static final Map<String, String> KO_TRANSLATIONS = loadTranslations();

    private MysticalOakTreePerf() {}

    public static String translateIfKorean(String text) {
        if (text == null || text.isEmpty() || KO_TRANSLATIONS.isEmpty()) {
            return text;
        }
        Minecraft mc = Minecraft.m_91087_();
        if (mc == null || mc.m_91102_() == null) {
            return text;
        }
        String lang = mc.m_91102_().m_264236_();
        if (lang == null || !lang.startsWith("ko")) {
            return text;
        }
        return KO_TRANSLATIONS.getOrDefault(text, text);
    }

    private static Map<String, String> loadTranslations() {
        try (InputStream in = MysticalOakTreePerf.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                return Collections.emptyMap();
            }
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> loaded = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), type);
            return loaded == null ? Collections.emptyMap() : Collections.unmodifiableMap(loaded);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
