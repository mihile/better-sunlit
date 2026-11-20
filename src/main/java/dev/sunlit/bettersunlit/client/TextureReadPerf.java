package dev.sunlit.bettersunlit.client;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * Direct-buffer {@code TextureUtil.readResource(InputStream)} replacement using a pooled
 * heap transfer buffer and initial size hint to avoid per-chunk {@code byte[8192]} allocations
 * inside {@code Channels.newChannel(InputStream)}.
 */
public final class TextureReadPerf {
    private static final int SCRATCH_BYTES = 16384;
    private static final int MAX_AVAILABLE_HINT = 16 * 1024 * 1024;
    private static final ThreadLocal<byte[]> TL_SCRATCH = ThreadLocal.withInitial(() -> new byte[SCRATCH_BYTES]);

    private TextureReadPerf() {
    }

    public static ByteBuffer readResource(InputStream input) throws IOException {
        int hint = 0;
        try {
            hint = input.available();
        } catch (IOException ignored) {
        }
        int initialCap = (hint > 64 && hint <= MAX_AVAILABLE_HINT) ? (hint + 1) : 8192;
        ByteBuffer buffer = MemoryUtil.memAlloc(initialCap);
        byte[] scratch = TL_SCRATCH.get();
        try {
            int read;
            while ((read = input.read(scratch, 0, scratch.length)) != -1) {
                if (read == 0) {
                    continue;
                }
                if (buffer.remaining() < read) {
                    int doubled = buffer.capacity() * 2;
                    int needed = buffer.position() + read + 1;
                    buffer = MemoryUtil.memRealloc(buffer, Math.max(doubled, needed));
                }
                buffer.put(scratch, 0, read);
            }
            return buffer;
        } catch (Throwable t) {
            MemoryUtil.memFree(buffer);
            throw t;
        }
    }
}
