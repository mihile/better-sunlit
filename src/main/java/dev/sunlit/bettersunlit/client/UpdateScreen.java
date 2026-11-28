package dev.sunlit.bettersunlit.client;

import dev.sunlit.bettersunlit.update.Build;
import dev.sunlit.bettersunlit.update.ReleaseNotes;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class UpdateScreen extends Screen {
    private record Row(FormattedCharSequence text, int color, int indent) {}

    final Screen parent;
    final UpdateBackdrop backdrop;
    int x, y, boxW, boxH, notesTop, notesBottom, scroll;
    List<Row> cachedRows;
    private static final List<String> INSTALLED_NOTES = ReleaseNotes.read(Build.META);

    public UpdateScreen(Screen parent) {
        super(Component.m_237113_("Better Sunlit"));
        this.parent = parent;
        this.backdrop = UpdateBackdrop.capture();
    }

    private List<Row> rows() {
        if (cachedRows != null) return cachedRows;
        List<Row> out = new ArrayList<>();
        int wrapWidth = Math.max(80, boxW - 38);
        for (String note : INSTALLED_NOTES) {
            String[] parts = note.split(" :: ", 2);
            String titlePart = note.equals(ReleaseNotes.EMPTY_NOTE)
                    ? tr("better_sunlit.update.notes.empty", ReleaseNotes.EMPTY_NOTE)
                    : parts[0];
            for (var line : f_96547_.m_92923_(Component.m_237113_("• " + titlePart), wrapWidth)) {
                out.add(new Row(line, 0xFFF1E7FF, 0));
            }
            if (parts.length > 1 && !parts[1].isBlank()) {
                for (var line : f_96547_.m_92923_(Component.m_237113_(parts[1]), wrapWidth - 8)) {
                    out.add(new Row(line, 0xFFE3DFE8, 8));
                }
            }
            out.add(new Row(FormattedCharSequence.f_13691_, 0xFFE3DFE8, 0));
        }
        return cachedRows = List.copyOf(out);
    }

    @Override
    protected void m_7856_() {
        boxW = Math.min(320, f_96543_ - 24);
        boxH = Math.min(216, f_96544_ - 24);
        x = (f_96543_ - boxW) / 2;
        y = (f_96544_ - boxH) / 2;
        notesTop = y + 71;
        notesBottom = y + boxH - 36;
        cachedRows = null;

        int closeWidth = Math.min(96, boxW - 24);
        m_142416_(Button.m_253074_(Component.m_264642_("better_sunlit.update.button.close", "Close"), b -> m_7379_())
                .m_252987_(x + boxW - 12 - closeWidth, y + boxH - 28, closeWidth, 20).m_253136_());
    }

    @Override
    public void m_88315_(GuiGraphics g, int mx, int my, float dt) {
        g.m_280262_();
        if (backdrop != null) backdrop.draw(g, f_96543_, f_96544_);
        else g.m_280509_(0, 0, f_96543_, f_96544_, 0xFF17141E);
        g.m_280509_(0, 0, f_96543_, f_96544_, 0x66000000);

        g.m_280509_(x - 1, y - 1, x + boxW + 1, y + boxH + 1, 0xFF75688E);
        g.m_280509_(x, y, x + boxW, y + boxH, 0xFF211E29);
        g.m_280509_(x, y, x + boxW, y + 3, 0xFFB59BE0);

        g.m_280430_(f_96547_, f_96539_, x + 14, y + 13, 0xFFF1E7FF);
        String verLine = tr("better_sunlit.update.current_version", "Current version %s", Build.LABEL);
        g.m_280488_(f_96547_, verLine, x + 14, y + 31, 0xFFACA6B8);
        g.m_280509_(x + 12, y + 49, x + boxW - 12, y + 50, 0xFF494153);
        String notesLabel = tr("better_sunlit.update.changelog_for", "%s Changelog", Build.LABEL);
        if (f_96547_.m_92895_(notesLabel) > boxW - 28) notesLabel = tr("better_sunlit.update.changelog", "Changelog");
        g.m_280488_(f_96547_, notesLabel, x + 14, y + 55, 0xFFB8A6D2);

        List<Row> list = rows();
        int visible = notesBottom - notesTop;
        int content = list.size() * 11;
        scroll = Math.max(0, Math.min(scroll, Math.max(0, content - visible)));

        g.m_280588_(x + 12, notesTop, x + boxW - 12, notesBottom);
        int lineY = notesTop - scroll;
        for (Row row : list) {
            g.m_280649_(f_96547_, row.text(), x + 16 + row.indent(), lineY, row.color(), false);
            lineY += 11;
        }
        g.m_280618_();

        if (content > visible) {
            int track = visible;
            int thumb = Math.max(14, track * visible / content);
            int offset = (track - thumb) * scroll / Math.max(1, content - visible);
            g.m_280509_(x + boxW - 12, notesTop, x + boxW - 10, notesBottom, 0xFF3C3548);
            g.m_280509_(x + boxW - 12, notesTop + offset, x + boxW - 10, notesTop + offset + thumb, 0xFFB39AD8);
        }

        super.m_88315_(g, mx, my, dt);
        g.m_280262_();
    }

    @Override
    public boolean m_6050_(double mx, double my, double amount) {
        if (mx >= x && mx <= x + boxW && my >= notesTop && my <= notesBottom) {
            scroll -= (int) (amount * 22);
            return true;
        }
        return super.m_6050_(mx, my, amount);
    }

    @Override
    public boolean m_7043_() {
        return true;
    }

    @Override
    public void m_7861_() {
        try {
            super.m_7861_();
        } finally {
            if (backdrop != null) backdrop.release();
        }
    }

    @Override
    public void m_7379_() {
        f_96541_.m_91152_(parent);
    }

    private static String tr(String key, String fallback, Object... args) {
        String fmt = Language.m_128107_().m_118919_(key, fallback);
        return args.length == 0 ? fmt : String.format(Locale.ROOT, fmt, args);
    }
}
