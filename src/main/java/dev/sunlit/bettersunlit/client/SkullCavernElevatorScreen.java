package dev.sunlit.bettersunlit.client;

import dev.sunlit.bettersunlit.cavern.SkullCavernElevator;
import dev.sunlit.bettersunlit.cavern.SkullCavernElevatorData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-mine Skull Cavern Elevator selector screen.
 * Shows ONLY stops actually discovered in this mine (no locked rows or max-depth spoilers),
 * with the pioneering player's name right-aligned on each stop row.
 */
public final class SkullCavernElevatorScreen extends Screen {
    private record RowMeta(
            SkullCavernElevator.StopEntry entry,
            int y,
            int accentColor,
            String leftLabel,
            String rightBadge,
            int rightColor) {}

    private final BlockPos clickedElevatorPos;
    private final List<SkullCavernElevator.StopEntry> stops;
    private final List<RowMeta> rows = new ArrayList<>();
    private int x, y, boxW, boxH;

    public SkullCavernElevatorScreen(SkullCavernElevator.OpenPacket packet) {
        super(Component.m_264642_("better_sunlit.elevator.title", "Skull Cavern Elevator"));
        this.clickedElevatorPos = packet.clickedElevatorPos();
        this.stops = packet.stops();
    }

    @Override
    public boolean m_7043_() {
        return false;
    }

    @Override
    protected void m_7856_() {
        rows.clear();
        boxW = Math.min(286, f_96543_ - 24);
        int headerH = 46;
        int rowPitch = 28;
        int footerH = 34;
        boxH = headerH + stops.size() * rowPitch + footerH;
        x = (f_96543_ - boxW) / 2;
        y = (f_96544_ - boxH) / 2;

        int rowY = y + headerH;
        for (SkullCavernElevator.StopEntry entry : stops) {
            int accent;
            String left;
            String right = "";
            int rightColor = 0xFFD9C082;

            if (entry.kind() == SkullCavernElevatorData.KIND_SURFACE) {
                accent = 0xFFE8C66D;
                left = entry.isCurrent()
                        ? tr("better_sunlit.elevator.surface_current", "Entrance (Current)")
                        : tr("better_sunlit.elevator.surface", "Entrance (Surface)");
            } else if (entry.kind() == SkullCavernElevatorData.KIND_TRIAL_CHAMBER) {
                accent = SkullCavernElevatorClient.TRIAL_COLOR;
                left = entry.isCurrent()
                        ? tr("better_sunlit.elevator.trial_current", "Copper Dungeon (%sF) (Current)", entry.floor())
                        : tr("better_sunlit.elevator.trial_open", "Copper Dungeon (%sF)", entry.floor());
                if (!entry.pioneer().isEmpty()) {
                    right = tr("better_sunlit.elevator.tag_discovered", "%s discovered", entry.pioneer());
                    rightColor = 0xFF7AE6C5;
                }
            } else {
                accent = SkullCavernElevatorClient.zoneColor(entry.floor());
                String zone = SkullCavernElevatorClient.zoneLabel(entry.floor());
                left = entry.isCurrent()
                        ? tr("better_sunlit.elevator.floor_current", "%sF  [%s]  (Current)", entry.floor(), zone)
                        : tr("better_sunlit.elevator.floor_open", "%sF  [%s]", entry.floor(), zone);
                if (!entry.pioneer().isEmpty()) {
                    right = tr("better_sunlit.elevator.tag_pioneered", "%s pioneered", entry.pioneer());
                    rightColor = 0xFFE8C66D;
                }
            }

            rows.add(new RowMeta(entry, rowY, accent, left, right, rightColor));
            // Empty-label button handles hover/focus/click sound while we render crisp left/right aligned labels on top.
            Button btn = Button.m_253074_(Component.m_237119_(), b -> sendAndClose(entry.key()))
                    .m_252987_(x + 18, rowY, boxW - 30, 22).m_253136_();
            btn.f_93623_ = !entry.isCurrent();
            m_142416_(btn);
            rowY += rowPitch;
        }

        int bottomY = y + boxH - 27;
        m_142416_(Button.m_253074_(
                Component.m_264642_("better_sunlit.update.button.close", "Close"),
                b -> m_7379_())
                .m_252987_(x + 12, bottomY, boxW - 24, 20).m_253136_());
    }

    private void sendAndClose(String targetStopKey) {
        SkullCavernElevator.sendUseElevator(clickedElevatorPos, targetStopKey);
        m_7379_();
    }

    @Override
    public void m_88315_(GuiGraphics g, int mx, int my, float dt) {
        g.m_280509_(0, 0, f_96543_, f_96544_, 0x88000000);

        // Outer border + dark slate panel + gold top accent bar
        g.m_280509_(x - 1, y - 1, x + boxW + 1, y + boxH + 1, 0xFF7B6A58);
        g.m_280509_(x, y, x + boxW, y + boxH, 0xF21D1A22);
        g.m_280509_(x, y, x + boxW, y + 3, 0xFFE5B96B);

        g.m_280430_(f_96547_, f_96539_, x + 14, y + 11, 0xFFF5E9D3);
        String subtitle = tr("better_sunlit.elevator.mine_subtitle", "Discovered stops in this mine");
        g.m_280488_(f_96547_, subtitle, x + 14, y + 25, 0xFFB8B0C2);
        g.m_280509_(x + 12, y + 39, x + boxW - 12, y + 40, 0xFF423A4A);

        for (RowMeta row : rows) {
            g.m_280509_(x + 12, row.y + 1, x + 16, row.y + 21, row.accentColor);
        }

        // Draw buttons first, then overlay crisp left-aligned stop label + right-aligned pioneer badge.
        super.m_88315_(g, mx, my, dt);

        int textLeftX = x + 26;
        int textRightEdge = x + boxW - 20;
        for (RowMeta row : rows) {
            int textY = row.y + 7;
            int leftColor = row.entry.isCurrent() ? 0xFFA8A2B2 : 0xFFF7F2E8;
            g.m_280488_(f_96547_, row.leftLabel, textLeftX, textY, leftColor);
            if (!row.rightBadge.isEmpty()) {
                int badgeW = f_96547_.m_92895_(row.rightBadge);
                int badgeX = textRightEdge - badgeW;
                int leftEnd = textLeftX + f_96547_.m_92895_(row.leftLabel) + 8;
                if (badgeX >= leftEnd) {
                    g.m_280488_(f_96547_, row.rightBadge, badgeX, textY,
                            row.entry.isCurrent() ? 0xFF9C8D6B : row.rightColor);
                }
            }
        }
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
