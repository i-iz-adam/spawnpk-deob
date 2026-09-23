package com.apple.eawt;

/**
 * Minimal source shim for the Mac-only Apple Java Extensions, which exist
 * in neither the input jar nor any OpenJDK. The decompiled client
 * references this API in two files (fullscreen handling); the shim keeps
 * those files compiling. Semantics on macOS are intentionally absent --
 * methods are no-ops. Provided via --extra-sources, not vendored bytecode.
 */
public interface FullScreenListener {
}
