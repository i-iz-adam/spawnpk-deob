package com.apple.eawt;

import com.apple.eawt.event.FullScreenEvent;

/**
 * Minimal source shim for the Mac-only Apple Java Extensions -- see
 * {@link FullScreenListener}. Mirrors the real method names so renamed
 * overrides keep working if mappings ever restore them.
 */
public class FullScreenAdapter implements FullScreenListener {
    public void windowEnteredFullScreen(FullScreenEvent event) {
    }

    public void windowExitedFullScreen(FullScreenEvent event) {
    }
}
