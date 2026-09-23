package com.apple.eawt;

/**
 * Minimal source shim for the Mac-only Apple Java Extensions -- see
 * {@link FullScreenListener}. Parameters are widened to
 * {@link java.awt.Component} (the real API takes {@code Window}; every
 * {@code Window} is a {@code Component}, so all genuine call sites still
 * compile).
 */
public final class FullScreenUtilities {
    private FullScreenUtilities() {
    }

    public static void addFullScreenListenerTo(java.awt.Component window, FullScreenListener listener) {
    }

    public static void setWindowCanFullScreen(java.awt.Component window, boolean canFullScreen) {
    }
}
