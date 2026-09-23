package com.cleandecompile.stage1;

import com.cleandecompile.PipelineConfig;
import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.stage2.OutputSelector;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Stage 1 entry point: normalized jar's in-scope classes in, one
 * {@code .java} file per class out (real decompilation where possible,
 * bytecode-recovered stub otherwise), plus a manifest of every
 * fallback/failure so it's obvious which classes need manual attention.
 *
 * <p>Only in-scope classes are decompiled to source -- out-of-scope
 * (library) classes stay as bytecode and are handled in Stage 3.
 */
public final class Stage1Runner {

    private final List<Decompiler> priorityChain = List.of(
            new VineflowerDecompiler(),
            new CfrDecompiler(),
            new ProcyonDecompiler()
    );

    /** Backend names in priority order, for swap-round bookkeeping. */
    public List<String> backendNames() {
        return priorityChain.stream().map(Decompiler::name).toList();
    }

    public List<DecompileResult> run(PipelineConfig config, List<ClassInfo> normalizedClasses) throws IOException {
        // Start from a clean tree: renames change output paths between runs,
        // so stale files from a previous mapping would otherwise linger and
        // poison Stage 4 with duplicate/phantom sources.
        deleteTree(config.decompiledSourcesDir());

        // Hand-written shims first: decompiled output wins any path
        // collision, since it reflects the jar's actual contents.
        copyExtraSources(config);

        Map<String, byte[]> byInternalName = tableOf(normalizedClasses);
        Function<String, byte[]> bytesProvider = bytesProvider(byInternalName);

        DecompileWorker worker = new DecompileWorker(priorityChain, config.decompileTimeoutMs());
        OutputSelector selector = new OutputSelector();
        List<DecompileResult> results = new java.util.ArrayList<>();

        // Full normalized jar as Vineflower's type-resolution library (its
        // own output, written by Stage 0 just before this runs).
        VineflowerDecompiler.setLibraryJar(config.normalizedJarPath());
        try {
            for (ClassInfo ci : normalizedClasses) {
                // Library classes are decompiled too when --decompile-libraries
                // is set (kept at original names -- Stage 0 never renames
                // them); otherwise see Stage 3 vendoring.
                if (!ci.inScope() && !config.decompileLibraries()) continue;
                results.add(decompileOne(ci, worker, selector, bytesProvider, config));
            }
        } finally {
            VineflowerDecompiler.clearLibraryJar();
            worker.shutdown();
        }

        writeManifest(config, results);
        return results;
    }

    /**
     * Class table plus running-JDK fallback, shared by every decompile call
     * so both backends resolve platform types instead of degrading.
     */
    private static Function<String, byte[]> bytesProvider(Map<String, byte[]> byInternalName) {
        return name -> {
            byte[] bytes = byInternalName.get(name);
            return bytes != null ? bytes : JdkClasses.get(name);
        };
    }

    private static Map<String, byte[]> tableOf(List<ClassInfo> normalizedClasses) {
        Map<String, byte[]> table = new HashMap<>();
        for (ClassInfo ci : normalizedClasses) table.put(ci.internalName(), ci.bytes());
        return table;
    }

    /**
     * Re-decompiles a subset of classes and overwrites their files -- the
     * swap-round path: Stage 4 names failing files, the orchestrator retries
     * them while excluding backends already selected for them, recompiles,
     * and keeps the swap only if the whole tree got better. No cleaning, no
     * manifest write (the orchestrator merges). Returns null entries for
     * files with no remaining untried backend.
     *
     * @param allNormalizedClasses the full table (cross-class resolution),
     *                             not just the targets.
     */
    public List<DecompileResult> redecompile(PipelineConfig config, List<ClassInfo> allNormalizedClasses,
                                             Map<ClassInfo, java.util.Set<String>> targets)
            throws IOException {
        if (targets.isEmpty()) return List.of();
        java.util.Set<String> union = new java.util.LinkedHashSet<>();
        for (var backends : targets.values()) union.addAll(backends);
        List<Decompiler> chain = priorityChain.stream()
                .filter(d -> union.contains(d.name()))
                .toList();
        if (chain.isEmpty()) return List.of();
        Function<String, byte[]> bytesProvider = bytesProvider(tableOf(allNormalizedClasses));
        DecompileWorker worker = new DecompileWorker(chain, config.decompileTimeoutMs());
        OutputSelector selector = new OutputSelector();
        List<DecompileResult> results = new java.util.ArrayList<>();
        VineflowerDecompiler.setLibraryJar(config.normalizedJarPath());
        try {
            for (var entry : targets.entrySet()) {
                DecompileResult swapped = decompileOne(entry.getKey(), worker, selector, bytesProvider, config,
                        entry.getValue());
                if (swapped != null) results.add(swapped);
            }
        } finally {
            VineflowerDecompiler.clearLibraryJar();
            worker.shutdown();
        }
        return results;
    }

    private DecompileResult decompileOne(ClassInfo ci, DecompileWorker worker, OutputSelector selector,
                                         Function<String, byte[]> bytesProvider, PipelineConfig config)
            throws IOException {
        return decompileOne(ci, worker, selector, bytesProvider, config, java.util.Set.of());
    }

    /**
     * @param excludeBackends winners already tried for this file (swap
     *                        rounds); empty in the normal path. A null return
     *                        means every successful backend was excluded --
     *                        the file is left untouched.
     */
    private DecompileResult decompileOne(ClassInfo ci, DecompileWorker worker, OutputSelector selector,
                                         Function<String, byte[]> bytesProvider, PipelineConfig config,
                                         java.util.Set<String> excludeBackends) throws IOException {
        var outputs = worker.decompileAll(ci.internalName(), ci.bytes(), bytesProvider);
        List<DecompileResult.AttemptLogEntry> logs = new java.util.ArrayList<>();
        Map<String, String> candidates = new java.util.LinkedHashMap<>();
        for (var output : outputs) {
            logs.add(output.logEntry());
            if (output.source() != null && !excludeBackends.contains(output.decompilerName())) {
                candidates.put(output.decompilerName(), output.source());
            }
        }
        if (candidates.isEmpty()) {
            if (!excludeBackends.isEmpty()) return null;
            String stub = DecompileWorker.StubGenerator.generate(
                    ci.internalName(), ci.bytes(), bytesProvider);
            DecompileResult stubResult =
                    new DecompileResult(ci.internalName(), stub, "stub", true, logs);
            writeSourceFile(config, ci.internalName(), stubResult.source());
            return stubResult;
        }
        DecompileResult result;
        if (candidates.size() == 1) {
            var only = candidates.entrySet().iterator().next();
            result = new DecompileResult(ci.internalName(), only.getValue(), only.getKey(), false, logs);
        } else {
            var best = selector.pickBest(candidates);
            result = new DecompileResult(
                    ci.internalName(), best.source(), best.decompilerName(), false, logs);
        }
        writeSourceFile(config, ci.internalName(), result.source());
        return result;
    }

    private void writeSourceFile(PipelineConfig config, String internalName, String source) throws IOException {
        Path outFile = config.decompiledSourcesDir().resolve(internalName + ".java");
        Files.createDirectories(outFile.getParent());
        Files.writeString(outFile, source);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                Files.delete(p);
            }
        }
    }

    private static void copyExtraSources(PipelineConfig config) throws IOException {
        Path extra = config.extraSources();
        if (extra == null) return;
        int copied = 0;
        try (var walk = Files.walk(extra)) {
            for (Path p : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Path out = config.decompiledSourcesDir().resolve(extra.relativize(p).toString());
                Files.createDirectories(out.getParent());
                Files.copy(p, out);
                copied++;
            }
        }
        if (copied > 0) System.out.printf("  %d extra source shims copied%n", copied);
    }

    public static void writeManifest(PipelineConfig config, List<DecompileResult> results) throws IOException {
        Files.createDirectories(config.stage1ManifestPath().getParent());
        long stubs = results.stream().filter(DecompileResult::isStub).count();
        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        var doc = Map.of(
                "totalClasses", results.size(),
                "stubCount", stubs,
                "results", results
        );
        mapper.writeValue(config.stage1ManifestPath().toFile(), doc);
    }
}
