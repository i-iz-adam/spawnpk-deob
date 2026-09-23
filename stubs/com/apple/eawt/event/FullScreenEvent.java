package com.apple.eawt.event;

/**
 * Minimal source shim for the Mac-only Apple Java Extensions -- see
 * {@code com.apple.eawt.FullScreenListener}.
 */
public class FullScreenEvent extends java.util.EventObject {
    public FullScreenEvent(Object source) {
        super(source);
    }
}
