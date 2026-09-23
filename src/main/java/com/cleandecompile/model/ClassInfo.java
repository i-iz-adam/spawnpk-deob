package com.cleandecompile.model;

/**
 * One {@code .class} entry from the input jar, tracked through Stage 0.
 *
 * @param internalName JVM internal name, e.g. "rs/a/a" (slash form, no ".class").
 *                      The ORIGINAL pre-rename name as loaded from the input
 *                      jar; after {@link com.cleandecompile.stage0.BytecodeNormalizer}
 *                      runs it holds the FINAL post-rename name (out-of-scope
 *                      classes are unchanged, so both readings coincide for them).
 * @param bytes         raw class bytes, updated in place as normalization runs.
 * @param inScope       true if this class falls under one of the configured
 *                      owned-package prefixes and should be renamed/decompiled;
 *                      false if it's a bundled library class that should be
 *                      left alone (aside from cross-reference fix-ups).
 */
public record ClassInfo(String internalName, byte[] bytes, boolean inScope) {

    public ClassInfo withBytes(byte[] newBytes) {
        return new ClassInfo(internalName, newBytes, inScope);
    }

    public ClassInfo withInternalName(String newInternalName) {
        return new ClassInfo(newInternalName, bytes, inScope);
    }

    /** e.g. "rs/a/a" -> "a" */
    public String simpleName() {
        int idx = internalName.lastIndexOf('/');
        return idx < 0 ? internalName : internalName.substring(idx + 1);
    }

    /** e.g. "rs/a/a" -> "rs/a" (empty string for default package) */
    public String packagePath() {
        int idx = internalName.lastIndexOf('/');
        return idx < 0 ? "" : internalName.substring(0, idx);
    }
}
