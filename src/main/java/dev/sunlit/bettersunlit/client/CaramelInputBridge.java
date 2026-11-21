package dev.sunlit.bettersunlit.client;

import moe.caramel.chat.util.Rect;
import moe.caramel.chat.wrapper.AbstractIMEWrapper;

/** Connects Caramel Chat's composition handling to a non-vanilla text field. */
public final class CaramelInputBridge extends AbstractIMEWrapper {
    public interface Field {
        boolean editable();
        boolean full();
        String text();
        void preview(String text);
        void insert(String text);
        default void removeSelectionForPreview() { insert(""); }
        int cursor();
        int selection();
        Rect rect();
    }

    private final Field field;

    public CaramelInputBridge(Field field) {
        this.field = field;
        setOrigin(field.text());
    }

    public String probeReplacement() {
        String original = getOrigin();
        int from = Math.max(0, Math.min(original.length(), Math.min(field.cursor(), field.selection())));
        int to = Math.max(from, Math.min(original.length(), Math.max(field.cursor(), field.selection())));
        return original.substring(0, from) + "A" + original.substring(to);
    }

    @Override public boolean editable() { return field.editable(); }
    @Override public boolean blockTyping() { return field.full(); }
    @Override protected String getTextWithPreview() { return field.text(); }
    @Override protected void setPreviewText(String text) { field.preview(text); }
    @Override protected void insert(String text) {
        if (getStatus() == InputStatus.PREVIEW && text.isEmpty()) {
            field.removeSelectionForPreview();
        } else {
            field.insert(text);
        }
    }
    @Override protected int getCursorPos() { return field.cursor(); }
    @Override protected int getHighlightPos() { return field.selection(); }
    @Override public Rect getRect() { return field.rect(); }
}
