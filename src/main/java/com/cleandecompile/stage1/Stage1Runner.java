package com.cleandecompile.stage1;

import com.cleandecompile.PipelineConfig;
import com.cleandecompile.model.ClassInfo;
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

    public List<DecompileResult> run(PipelineConfig config, List<ClassInfo> normalizedClasses) throws IOException {
        // Start from a clean tree: renames change output paths between runs,
        // so stale files from a previous mapping would otherwise linger and
        // poison Stage 4 with duplicate/phantom sources.
        deleteTree(config.decompiledSourcesDir());

        // Hand-written shims first: decompiled output wins any path
        // collision, since it reflects the jar's actual contents.
        copyExtraSources(config);

        Map<String, byte[]> byInternalName = new HashMap<>();
        for (ClassInfo ci : normalizedClasses) byInternalName.put(ci.internalName(), ci.bytes());
        Function<String, byte[]> bytesProvider = byInternalName::get;

        DecompileWorker worker = new DecompileWorker(priorityChain, config.decompileTimeoutMs());
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
                DecompileResult result = worker.decompile(ci.internalName(), ci.bytes(), bytesProvider);
                results.add(result);
                writeSourceFile(config, ci.internalName(), result.source());
            }
        } finally {
            VineflowerDecompiler.clearLibraryJar();
            worker.shutdown();
        }

        writeManifest(config, results);
        return results;
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

    private void writeManifest(PipelineConfig config, List<DecompileResult> results) throws IOException {
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
