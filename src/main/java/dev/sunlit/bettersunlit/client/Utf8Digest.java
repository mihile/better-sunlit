package dev.sunlit.bettersunlit.client;

import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.security.MessageDigest;

/** Same length-prefixed UTF-8 bytes as String.getBytes, with bounded scratch for large GLSL sources. */
public final class Utf8Digest {
    private static final int BUFFER_SIZE = 16384;
    private static final ThreadLocal<byte[]> BUFFER = ThreadLocal.withInitial(() -> new byte[BUFFER_SIZE]);
    private static final class UnicodeBuffer {
        final char[] chars = new char[8192];
        final byte[] bytes = new byte[chars.length * 3];
        final CharBuffer input = CharBuffer.wrap(chars);
        final ByteBuffer output = ByteBuffer.wrap(bytes);
        final CharsetEncoder encoder = StandardCharsets.UTF_8.newEncoder()
                .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
    }
    private static final ThreadLocal<UnicodeBuffer> UNICODE_BUFFER = ThreadLocal.withInitial(UnicodeBuffer::new);
    private Utf8Digest() {}

    @SuppressWarnings("deprecation")
    public static void write(MessageDigest digest, String value) {
        if (value == null) { writeLength(digest, -1); return; }
        if (value.length() < 4096) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writeLength(digest, bytes.length); digest.update(bytes); return;
        }
        byte[] buffer = BUFFER.get();
        if (isAscii(value)) {
            writeLength(digest, value.length());
            for (int begin = 0; begin < value.length();) {
                int count = Math.min(buffer.length, value.length() - begin);
                value.getBytes(begin, begin + count, buffer, 0); digest.update(buffer, 0, count); begin += count;
            }
            return;
        }
        long size = value.length();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 128) continue;
            if (c < 2048) size++;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) { size += 2; i++; }
            else if (!Character.isSurrogate(c)) size += 2;
            // String's default UTF-8 encoder replaces each unpaired surrogate with one '?'.
        }
        if (size > Integer.MAX_VALUE) throw new OutOfMemoryError("UTF-8 encoded string too large");
        writeLength(digest, (int)size);
        UnicodeBuffer unicode = UNICODE_BUFFER.get();
        for (int begin = 0; begin < value.length();) {
            int count = Math.min(unicode.chars.length, value.length() - begin);
            if (begin + count < value.length() && Character.isHighSurrogate(value.charAt(begin + count - 1))
                    && Character.isLowSurrogate(value.charAt(begin + count))) count--;
            value.getChars(begin, begin + count, unicode.chars, 0);
            unicode.input.clear().limit(count); unicode.output.clear(); unicode.encoder.reset();
            if (!unicode.encoder.encode(unicode.input, unicode.output, true).isUnderflow()
                    || !unicode.encoder.flush(unicode.output).isUnderflow()) throw new IllegalStateException("UTF-8 digest scratch overflow");
            digest.update(unicode.bytes, 0, unicode.output.position());
            begin += count;
        }
    }
    private static boolean isAscii(String value) {
        int i = 0, end = value.length() - 7;
        for (; i < end; i += 8) {
            int combined = value.charAt(i) | value.charAt(i + 1) | value.charAt(i + 2) | value.charAt(i + 3)
                    | value.charAt(i + 4) | value.charAt(i + 5) | value.charAt(i + 6) | value.charAt(i + 7);
            if (combined > 127) return false;
        }
        for (; i < value.length(); i++) if (value.charAt(i) > 127) return false;
        return true;
    }
    private static void writeLength(MessageDigest digest, int value) {
        digest.update((byte)(value >>> 24)); digest.update((byte)(value >>> 16));
        digest.update((byte)(value >>> 8)); digest.update((byte)value);
    }
}
