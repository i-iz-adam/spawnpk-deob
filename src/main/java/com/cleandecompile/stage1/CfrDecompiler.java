package com.cleandecompile.stage1;

import java.util.function.Function;

/**
 * Secondary decompiler backend — CFR.
 *
 * <p><b>TODO — wire the real invocation.</b> CFR's public in-process entry
 * point is {@code org.benf.cfr.reader.api.CfrDriver}, built via
 * {@code CfrDriverImpl.CfrDriverFactory} with a custom
 * {@code OutputSinkFactory} (to capture source as a string instead of
 * writing files) and a {@code ClassFileSource} backed by the same
 * classBytesProvider used for Vineflower, so both backends see an
 * identical view of the normalized jar.
 */
public final class CfrDecompiler implements Decompiler {

    @Override
    public String name() {
        return "cfr";
    }

    @Override
    public String decompile(String internalName, Function<String, byte[]> classBytesProvider) throws Exception {
        throw new UnsupportedOperationException(
                "CfrDecompiler not yet wired to org.benf.cfr -- see class javadoc");
    }
}
