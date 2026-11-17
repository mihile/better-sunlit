package dev.sunlit.bettersunlit.client;

/** Initial allocation only; the original map may still grow and shrink normally. */
public final class RenderSectionIndexCapacity {
    private RenderSectionIndexCapacity() {}
    public static int expected(int renderDistance, int verticalSections) {
        long side = 2L * Math.max(0, Math.min(32, renderDistance)) + 1;
        long sections = side * side * Math.max(1, Math.min(256, verticalSections));
        return (int)Math.max(256, Math.min(65536, sections));
    }
}
