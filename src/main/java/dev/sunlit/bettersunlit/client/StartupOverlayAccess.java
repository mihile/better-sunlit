package dev.sunlit.bettersunlit.client;

/**
 * Exposes reload completion state from {@code LoadingOverlay} without waiting for visual fadeout.
 */
public interface StartupOverlayAccess {
    boolean sunlit$isReloadDone();
}
