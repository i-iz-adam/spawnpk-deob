package com.cleandecompile.stage1;

import java.util.function.Function;

/**
 * Tertiary fallback decompiler backend — Procyon.
 *
 * <p><b>TODO — wire the real invocation.</b> Procyon's
 * {@code com.strobel.decompiler.Decompiler.decompile(String, DecompilerSettings, ITypeLoader)}
 * takes a custom {@code ITypeLoader} to resolve classes -- back it with
 * classBytesProvider the same way as the other two backends. Procyon tends
 * to succeed on some malformed-generic-signature cases that trip up
 * Vineflower/CFR, which is why it's kept as a third distinct implementation
 * rather than just retrying one of the others.
 */
public final class ProcyonDecompiler implements Decompiler {

    @Override
    public String name() {
        return "procyon";
    }

    @Override
    public String decompile(String internalName, Function<String, byte[]> classBytesProvider) throws Exception {
        throw new UnsupportedOperationException(
                "ProcyonDecompiler not yet wired to com.strobel.decompiler -- see class javadoc");
    }
}
