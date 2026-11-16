package dev.sunlit.bettersunlit.client;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.font.FontManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Starts Drippy Loading Screen's initial {@link FontManager} preparation off-thread without
 * blocking the Render thread on {@link CompletableFuture#get()}.
 */
public final class DrippyFontPerf {
    private static final Field FONT_MANAGER_FIELD = resolveFontManagerField();
    private static final Method PREPARE_METHOD = resolvePrepareMethod();
    private static final Method APPLY_METHOD = resolveApplyMethod();

    private DrippyFontPerf() {
    }

    private static Field resolveFontManagerField() {
        try {
            Field f = Minecraft.class.getDeclaredField("f_91045_");
            f.setAccessible(true);
            return f;
        } catch (Throwable ignored) {
            for (Field f : Minecraft.class.getDeclaredFields()) {
                if (f.getType() == FontManager.class) {
                    f.setAccessible(true);
                    return f;
                }
            }
            return null;
        }
    }

    private static Method resolvePrepareMethod() {
        try {
            Method m = FontManager.class.getDeclaredMethod("m_284410_", ResourceManager.class, Executor.class);
            m.setAccessible(true);
            return m;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method resolveApplyMethod() {
        for (Method m : FontManager.class.getDeclaredMethods()) {
            if ("m_284460_".equals(m.getName()) && m.getParameterCount() == 2) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    public static void loadFontsAsync() {
        Minecraft mc = Minecraft.m_91087_();
        if (mc == null || FONT_MANAGER_FIELD == null || PREPARE_METHOD == null || APPLY_METHOD == null) {
            return;
        }
        try {
            Object raw = FONT_MANAGER_FIELD.get(mc);
            if (!(raw instanceof FontManager fontManager)) {
                return;
            }
            loadFontsAsync(
                    () -> {
                        try {
                            Object futObj = PREPARE_METHOD.invoke(fontManager, mc.m_91098_(), Util.m_183991_());
                            if (futObj instanceof CompletableFuture<?> prepFuture) {
                                return prepFuture;
                            }
                        } catch (Throwable ignored) {
                        }
                        return CompletableFuture.completedFuture(null);
                    },
                    prep -> {
                        try {
                            APPLY_METHOD.invoke(fontManager, prep, (ProfilerFiller) InactiveProfiler.f_18554_);
                        } catch (Throwable ignored) {
                        }
                    },
                    Util.m_183991_(),
                    mc::execute);
        } catch (Throwable ignored) {
        }
    }

    public static CompletableFuture<Void> loadFontsAsync(
            Supplier<CompletableFuture<?>> prepareSupplier,
            Consumer<Object> applyConsumer,
            Executor backgroundExecutor,
            Executor applyExecutor) {
        if (prepareSupplier == null || applyConsumer == null || backgroundExecutor == null || applyExecutor == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.supplyAsync(() -> {
                    CompletableFuture<?> inner = prepareSupplier.get();
                    return inner != null ? inner : CompletableFuture.completedFuture(null);
                }, backgroundExecutor)
                .thenCompose(fut -> fut)
                .thenAcceptAsync(prep -> {
                    if (prep != null) {
                        applyConsumer.accept(prep);
                    }
                }, applyExecutor);
    }
}
