package com.cleandecompile.stage1;

import java.util.function.Function;

/**
 * A single decompiler backend, wired in as a library (never a CLI/subprocess
 * — that's what makes per-class isolation with a hard timeout possible
 * without paying process-spawn overhead per class).
 *
 * <p>Implementations should be pure/stateless w.r.t. a single
 * {@link #decompile} call so {@link DecompileWorker} can safely run them on
 * a worker thread and abandon them mid-flight on timeout.
 */
public interface Decompiler {

    /** Short identifier used in manifests and Stage 2's selection, e.g. "vineflower". */
    String name();

    /**
     * @param internalName      JVM internal name of the class to decompile, e.g. "com/naxos/Client".
     * @param classBytesProvider looks up bytes for any class by internal name, so the
     *                           decompiler can resolve types across the whole normalized jar
     *                           (both in-scope and out-of-scope classes are available through
     *                           this, since decompiling needs to see the full type graph even
     *                           though only in-scope classes get written out as .java files).
     * @return decompiled Java source text.
     * @throws Exception on any decompiler-internal failure; the caller decides whether to
     *                    fall through to the next decompiler.
     */
    String decompile(String internalName, Function<String, byte[]> classBytesProvider) throws Exception;
}
