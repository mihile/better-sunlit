package dev.sunlit.bettersunlit.client;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.LiteralContents;

import java.util.Locale;
import java.util.Map;

public final class ClientLocalizer {
    private static final Map<String, String> LITERAL_ADVANCEMENT_KEYS = Map.ofEntries(
            Map.entry("Let's Beautify!", "advancements.beautify.progression.root.title"),
            Map.entry("The devil is in the detailing", "advancements.beautify.progression.root.description"),
            Map.entry("Blinded by the lights", "advancements.beautify.progression.blinds.title"),
            Map.entry("Craft blinds to filter some light", "advancements.beautify.progression.blinds.description"),
            Map.entry("No shelf organization", "advancements.beautify.progression.bookstack.title"),
            Map.entry("Who needs shelves anyways? Craft a bookstack", "advancements.beautify.progression.bookstack.description"),
            Map.entry("Wicked!", "advancements.beautify.progression.candelabra.title"),
            Map.entry("Craft a candelabra", "advancements.beautify.progression.candelabra.description"),
            Map.entry("Left hanging", "advancements.beautify.progression.hanging_pot.title"),
            Map.entry("Craft a hanging pot", "advancements.beautify.progression.hanging_pot.description"),
            Map.entry("Lit!", "advancements.beautify.progression.light.title"),
            Map.entry("Craft a lightsource from Beautify", "advancements.beautify.progression.light.description"),
            Map.entry("Could you knot?", "advancements.beautify.progression.rope.title"),
            Map.entry("Tie String together to receive Rope", "advancements.beautify.progression.rope.description"),
            Map.entry("Kaboom?", "advancements.betterarcheology.used_bomb_item.title"),
            Map.entry("Yes Rico, Kaboom", "advancements.betterarcheology.used_bomb_item.description"),
            Map.entry("Mineshafter", "advancements.hopo.all_mineshaft.title"),
            Map.entry("Discover all the types of mineshafts", "advancements.hopo.all_mineshaft.description"),
            Map.entry("So we back in the mine...", "advancements.hopo.locate_mineshaft.title"),
            Map.entry("Discover the renovated mineshafts", "advancements.hopo.locate_mineshaft.description")
    );

    private ClientLocalizer() {}

    public static String localizeSkillCategory(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String normalized = rawKey.trim().toLowerCase(Locale.ROOT);
        String societyKey = "society_skills." + normalized + ".category.title";
        if (I18n.m_118936_(societyKey)) {
            return I18n.m_118938_(societyKey);
        }
        String customKey = "skillsexpnotifier.category." + normalized;
        if (I18n.m_118936_(customKey)) {
            return I18n.m_118938_(customKey);
        }
        return null;
    }

    public static Component localizeAdvancementComponent(Component original) {
        if (original == null) {
            return null;
        }
        if (original.m_214077_() instanceof LiteralContents literal) {
            String key = LITERAL_ADVANCEMENT_KEYS.get(literal.f_237368_());
            if (key != null && I18n.m_118936_(key)) {
                return Component.m_237115_(key).m_130948_(original.m_7383_());
            }
        }
        return null;
    }
}
