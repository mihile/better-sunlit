package dev.sunlit.bettersunlit.client;

/** Bounded immutable names for the common vanilla integer-property values. */
public final class PropertyNameCache {
    private static final String[] SMALL = new String[256];
    static { for (int i = 0; i < SMALL.length; i++) SMALL[i] = Integer.toString(i); }
    private PropertyNameCache() {}
    public static String integerName(int value) {
        return value >= 0 && value < SMALL.length ? SMALL[value] : Integer.toString(value);
    }
}
