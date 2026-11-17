package dev.sunlit.bettersunlit.client;

import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Diagnostic: logs the class, visible text fields and calling mods of every toast that is queued,
 * so an unexpected pop-up can be attributed to the mod that raised it.
 */
public final class ToastTrace {
    private static final Logger LOG = LoggerFactory.getLogger("sunlit_fish_visuals");

    private ToastTrace() {
    }

    public static void log(Toast toast) {
        try {
            StringBuilder text = new StringBuilder();
            for (Class<?> type = toast.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }
                    Class<?> kind = field.getType();
                    if (!Component.class.isAssignableFrom(kind) && kind != String.class) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object value = field.get(toast);
                    if (value == null) {
                        continue;
                    }
                    String shown = value instanceof Component c ? c.getString() : value.toString();
                    if (shown.length() > 160) {
                        shown = shown.substring(0, 160);
                    }
                    text.append(' ').append(field.getName()).append("='").append(shown.replace('\n', ' ')).append('\'');
                }
            }
            StringBuilder frames = new StringBuilder();
            int[] kept = {0};
            StackWalker.getInstance().forEach(frame -> {
                String owner = frame.getClassName();
                if (kept[0] >= 10 || owner.startsWith("java.") || owner.startsWith("jdk.")
                        || owner.startsWith("org.spongepowered") || owner.startsWith("dev.sunlit.bettersunlit.client.ToastTrace")
                        || owner.contains("DebugToastTraceMixin")) {
                    return;
                }
                if (kept[0]++ > 0) {
                    frames.append(" <- ");
                }
                frames.append(owner).append('.').append(frame.getMethodName());
            });
            LOG.info("[BetterSunlit-ToastTrace] {}{} | {}", toast.getClass().getName(), text, frames);
        } catch (Throwable error) {
            LOG.info("[BetterSunlit-ToastTrace] {} (inspect failed: {})", toast.getClass().getName(), error.toString());
        }
    }
}
