package com.cleandecompile.stage1;

import java.util.List;

/**
 * @param internalName    class that was decompiled.
 * @param source           the Java source text (real decompilation, or a
 *                         generated stub if every backend failed).
 * @param decompilerUsed   name of the backend that produced {@code source},
 *                         or "stub" if every real backend failed/timed out.
 * @param isStub           true if this is a bytecode-recovered stub rather
 *                         than real decompiler output — these are the
 *                         classes the manifest should point a human at
 *                         first.
 * @param attemptLog       one entry per backend tried, in priority order,
 *                         recording success/failure/timeout — this is what
 *                         lets Stage 2 pick between multiple *successful*
 *                         outputs when more than one backend didn't fail.
 */
public record DecompileResult(
        String internalName,
        String source,
        String decompilerUsed,
        boolean isStub,
        List<AttemptLogEntry> attemptLog
) {
    public record AttemptLogEntry(String decompiler, Outcome outcome, String detail) {}

    public enum Outcome { SUCCESS, FAILED, TIMED_OUT }
}
