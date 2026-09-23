package com.apple.eawt;

/**
 * Minimal source shim for the Mac-only Apple Java Extensions -- see
 * {@link FullScreenListener}.
 */
public class Application {
    public static Application getApplication() {
        return null;
    }

    public void requestUserAttention(boolean critical) {
    }

    public void requestForeground(boolean allWindows) {
    }
}
