package dev.sunlit.bettersunlit.client;

import dev.sunlit.bettersunlit.cavern.SkullCavernElevator;
import dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.components.toasts.ToastComponent;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Crisp Stardew-inspired top-right Toast notification when reaching a 10F milestone
 * or discovering a Copper Dungeon (Trial Chamber) in Skull Cavern.
 */
public final class SkullCavernToast implements Toast {
    private static final long DISPLAY_MS = 4500L;
    private static final int WIDTH = 186;
    private static final int HEIGHT = 34;

    private final String title;
    private final String subtitle;
    private final int accentColor;
    private final ItemStack icon;

    public SkullCavernToast(SkullCavernElevator.ToastPacket packet) {
        boolean isTrial = packet.kind() == SkullCavernElevatorData.KIND_TRIAL_CHAMBER;
        if (isTrial) {
            this.accentColor = SkullCavernElevatorClient.TRIAL_COLOR;
            this.icon = resolveIcon("minecraft:copper_block");
            if (packet.firstDiscovery()) {
                this.title = tr("better_sunlit.elevator.toast_trial_title", "Copper Dungeon Found!");
                this.subtitle = tr("better_sunlit.elevator.toast_trial_sub", "Elevator linked · %s",
                        tr("better_sunlit.elevator.tag_discovered", "%s discovered", packet.pioneer()));
            } else {
                this.title = tr("better_sunlit.elevator.toast_trial_enter", "Copper Dungeon (%sF)", packet.floor());
                this.subtitle = tr("better_sunlit.elevator.tag_discovered", "%s discovered", packet.pioneer());
            }
        } else {
            this.accentColor = SkullCavernElevatorClient.zoneColor(packet.floor());
            this.icon = resolveIcon("minecraft:iron_pickaxe");
            this.title = tr("better_sunlit.elevator.toast_floor_title", "Skull Cavern %sF", packet.floor());
            if (packet.firstDiscovery()) {
                this.subtitle = tr("better_sunlit.elevator.toast_floor_sub", "Elevator opened · %s",
                        tr("better_sunlit.elevator.tag_pioneered", "%s pioneered", packet.pioneer()));
            } else {
                this.subtitle = SkullCavernElevatorClient.zoneLabel(packet.floor());
            }
        }
    }

    private static ItemStack resolveIcon(String itemId) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(itemId));
        return item == null || item == Items.f_41852_ ? ItemStack.f_41583_ : new ItemStack(item);
    }

    @Override
    public int m_7828_() {
        Font font = Minecraft.m_91087_().f_91062_;
        int textW = Math.max(font.m_92895_(title), font.m_92895_(subtitle));
        return Math.max(WIDTH, textW + 44);
    }

    @Override
    public int m_94899_() {
        return HEIGHT;
    }

    @Override
    public Visibility m_7172_(GuiGraphics g, ToastComponent component, long elapsedMs) {
        int w = m_7828_();
        int h = m_94899_();
        // Border + dark slate background + left biome/copper accent strip + top hairline
        g.m_280509_(0, 0, w, h, 0xFF6B5E52);
        g.m_280509_(1, 1, w - 1, h - 1, 0xF21B1821);
        g.m_280509_(1, 1, 5, h - 1, accentColor);

        g.m_280203_(icon, 10, 9);

        Font font = component.m_94929_().f_91062_;
        g.m_280488_(font, title, 32, 7, 0xFFF7EEDB);
        g.m_280488_(font, subtitle, 32, 19, 0xFFC8C0D4);

        return elapsedMs >= DISPLAY_MS ? Visibility.HIDE : Visibility.SHOW;
    }

    private static String tr(String key, String fallback, Object... args) {
        String pattern = Language.m_128107_().m_118919_(key, fallback);
        try {
            return args.length == 0 ? pattern : String.format(pattern, args);
        } catch (RuntimeException ignored) {
            return args.length == 0 ? fallback : String.format(fallback, args);
        }
    }
}
