package dev.sunlit.bettersunlit.client;

import dev.sunlit.bettersunlit.update.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

public final class UpdateScreen extends Screen {
    private record Row(FormattedCharSequence text, int color, int indent) {}

    final Screen parent;
    final UpdateBackdrop backdrop;
    int x, y, boxW, boxH, notesTop, notesBottom, buttonsY, btnW, scroll;
    Button apply, retry, close, automatic;
    List<String> displayed = List.of();
    List<Row> cachedRows;

    public UpdateScreen(Screen parent) {
        super(Component.m_237113_("Better Sunlit"));
        this.parent = parent;
        this.backdrop = UpdateBackdrop.capture();
    }

    static List<String> installedNotes() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            String v = Build.META.getProperty("notes." + i);
            if (v == null) break;
            list.add(v);
        }
        return list.isEmpty()
            ? List.of("자연스러운 낚시 연출 :: 낚은 물고기가 땅에서 펄떡이다가 점점 힘이 빠지고 멈춥니다. 약해지기 시작하면 가까이 가서 원래 별 등급과 수량 그대로 회수할 수 있어요. /realfishing으로 활발한 시간과 약해지는 시간을 설정할 수 있습니다.")
            : List.copyOf(list);
    }

    List<String> notes() {
        return UpdateClient.release != null && UpdateClient.release.build() > Build.NUMBER
            ? UpdateClient.release.notes()
            : installedNotes();
    }

    private List<Row> rows() {
        List<String> current = notes();
        if (cachedRows != null && current.equals(displayed)) return cachedRows;
        displayed = current;
        List<Row> out = new ArrayList<>();
        int wrapWidth = Math.max(80, boxW - 38);
        for (String note : current) {
            String[] parts = note.split(" :: ", 2);
            for (var line : f_96547_.m_92923_(Component.m_237113_("• " + parts[0]), wrapWidth)) {
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
        notesTop = y + 58;
        notesBottom = y + boxH - 58;
        buttonsY = y + boxH - 28;
        btnW = (boxW - 36) / 3;
        cachedRows = null;

        automatic = m_142416_(Button.m_253074_(Component.m_237113_("자동 확인"), b -> UpdateClient.toggleAuto())
            .m_252987_(x + boxW - 106, y + 7, 94, 20).m_253136_());
        apply = m_142416_(Button.m_253074_(Component.m_237113_("업데이트"), b -> {
            if (UpdateClient.queued) f_96541_.m_91395_();
            else UpdateClient.prepare();
        }).m_252987_(x + 12, buttonsY, btnW, 20).m_253136_());
        retry = m_142416_(Button.m_253074_(Component.m_237113_("다시 확인"), b -> UpdateClient.check())
            .m_252987_(x + 18 + btnW, buttonsY, btnW, 20).m_253136_());
        close = m_142416_(Button.m_253074_(Component.m_237113_("닫기"), b -> m_7379_())
            .m_252987_(x + 24 + btnW * 2, buttonsY, btnW, 20).m_253136_());
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
        SignedRelease rel = UpdateClient.release;
        boolean hasUpdate = rel != null && rel.build() > Build.NUMBER;
        String verLine = hasUpdate ? (Build.LABEL + "  →  " + rel.label()) : ("현재 버전 " + Build.LABEL);
        g.m_280488_(f_96547_, verLine, x + 14, y + 31, 0xFFACA6B8);
        g.m_280509_(x + 12, y + 49, x + boxW - 12, y + 50, 0xFF494153);

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

        g.m_280488_(f_96547_, UpdateClient.status, x + 14, buttonsY - 18, UpdateClient.failed ? 0xFFE8A0A0 : 0xFFB8A6D2);
        if (UpdateClient.BUSY.get() && hasUpdate && rel.size() > 0 && UpdateClient.downloaded > 0) {
            int bar = boxW - 28;
            int filled = (int) Math.min(bar, bar * UpdateClient.downloaded / Math.max(1L, rel.size()));
            g.m_280509_(x + 14, buttonsY - 6, x + 14 + bar, buttonsY - 4, 0xFF40364C);
            g.m_280509_(x + 14, buttonsY - 6, x + 14 + filled, buttonsY - 4, 0xFFB59BE0);
        }

        automatic.m_93666_(Component.m_237113_("자동 확인: " + (UpdateClient.auto ? "켜짐" : "꺼짐")));
        apply.f_93623_ = UpdateClient.queued || (!UpdateClient.BUSY.get() && hasUpdate);
        apply.m_93666_(Component.m_237113_(UpdateClient.queued ? "게임 종료" : hasUpdate ? "업데이트" : "최신 상태"));
        retry.f_93623_ = !UpdateClient.BUSY.get() && !UpdateClient.queued;
        close.m_93666_(Component.m_237113_(hasUpdate && !UpdateClient.queued ? "나중에" : "닫기"));

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
}
