package com.cleandecompile;

import java.nio.file.Path;
import java.util.List;

/**
 * Everything a pipeline run needs to know. Built once by {@link Main} and
 * passed down read-only to every stage.
 *
 * @param inputJar        the obfuscated jar to process.
 * @param outputDir       root directory for all pipeline output (normalized
 *                        jar, decompiled sources, manifests, final Gradle
 *                        project).
 * @param ownedPackages   binary-name package prefixes (dot form, e.g.
 *                        "com.naxos") that make up the target application's
 *                        own code. Only classes under one of these prefixes
 *                        are renamed and decompiled. Everything else is
 *                        treated as a bundled library: left byte-identical
 *                        (aside from cross-reference fix-ups forced by
 *                        renames on the owned side) and handled in Stage 3
 *                        by fingerprinting/vendoring instead of
 *                        deobfuscating. If empty, every class is treated as
 *                        owned — useful for jars that are entirely the
 *                        target application.
 * @param decompileTimeoutMs per-class hard timeout before falling through
 *                        to the next decompiler in the priority chain.
 * @param maxFixLoopIterations hard stop for Stage 4's compile-fix loop, so
 *                        a jar that never fully converges still terminates.
 * @param customNamesFile optional path to a JSON file of known names --
 *                        package prefixes, full class names, fields and
 *                        methods -- that Stage 0 should use instead of an
 *                        auto-generated one. Null if not given. See
 *                        {@code stage0/CustomNameOverrides.java} for the
 *                        file format.
 * @param decompileLibraries when true, out-of-scope (bundled library)
 *                        classes are ALSO decompiled to source (kept at
 *                        their original names) instead of only vendored as
 *                        bytecode. Default false: libraries stay a bytecode
 *                        jar under {@code src-generated/libs/}, which keeps
 *                        the pipeline fast and avoids compiling third-party
 *                        sources that were never meant to build standalone
 *                        (e.g. an obfuscated trove whose package and
 *                        interface names collide).
 * @param releaseLevel  {@code javac --release} level for Stage 4 and the
 *                        generated Gradle build, e.g. "11" to compile
 *                        against an older platform (applet-era clients).
 *                        Default "17".
 * @param mainClass     dotted main-class name for the generated run
 *                        script, e.g. "rs.Client". Empty (default) means no
 *                        run script is written.
 * @param jreHome       JDK/JRE home whose {@code bin/java} the generated
 *                        run script launches with. Empty (default) means
 *                        plain {@code java} from PATH.
 */
public record PipelineConfig(
        Path inputJar,
        Path outputDir,
        List<String> ownedPackages,
        long decompileTimeoutMs,
        int maxFixLoopIterations,
        Path customNamesFile,
        boolean decompileLibraries,
        String releaseLevel,
        String mainClass,
        String jreHome
) {
    public static final long DEFAULT_TIMEOUT_MS = 15_000L;
    public static final int DEFAULT_MAX_FIX_ITERATIONS = 8;

    /** Binary-name (slash form) prefixes, e.g. "com/naxos". */
    public List<String> ownedPackagePrefixes() {
        return ownedPackages.stream().map(p -> p.replace('.', '/')).toList();
    }

    public Path normalizedJarPath() {
        return outputDir.resolve("stage0-normalized.jar");
    }

    public Path stage0ManifestPath() {
        return outputDir.resolve("manifests/stage0-rename-manifest.json");
    }

    public Path decompiledSourcesDir() {
        return outputDir.resolve("src-generated/src/main/java");
    }

    public Path stage1ManifestPath() {
        return outputDir.resolve("manifests/stage1-decompile-manifest.json");
    }

    /** Bundled-library jar lives INSIDE the generated project so the whole
     *  thing (sources + deps + run script) can be worked from as one folder. */
    public Path vendoredLibsDir() {
        return projectDir().resolve("libs");
    }

    public Path projectDir() {
        return outputDir.resolve("src-generated");
    }
}
