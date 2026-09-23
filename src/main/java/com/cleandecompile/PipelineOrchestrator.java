package com.cleandecompile;

import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.stage0.Stage0Runner;
import com.cleandecompile.stage1.DecompileResult;
import com.cleandecompile.stage1.DecompileResult.Outcome;
import com.cleandecompile.stage1.Stage1Runner;
import com.cleandecompile.stage3.BuildScaffolder;
import com.cleandecompile.stage3.DependencyFingerprinter;
import com.cleandecompile.stage4.CompileFixLoop;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the full 5-stage pipeline in order. Each stage's runner is
 * independently usable (see the project plan's "independently testable"
 * goal) -- this class just wires their outputs to the next stage's inputs.
 */
public final class PipelineOrchestrator {

    public void runFull(PipelineConfig config) throws Exception {
        System.out.println("== Stage 0: bytecode normalization ==");
        Stage0Runner.Stage0Output stage0 = new Stage0Runner().run(config);
        long memberRenamed = stage0.memberRenames().stream().filter(e -> !e.kept()).count();
        System.out.printf("  %d classes total, %d in scope, %d classes/packages renamed, "
                        + "%d fields/methods renamed, %d normalization warnings%n",
                stage0.normalizedClasses().size(), stage0.inScopeCount(),
                stage0.renames().size(), memberRenamed, stage0.warnings().size());

        System.out.println("== Stage 1: multi-decompiler harness ==");
        var stage1Results = new Stage1Runner().run(config, stage0.normalizedClasses());
        long stubs = stage1Results.stream().filter(r -> r.isStub()).count();
        var wins = new java.util.TreeMap<String, Long>();
        for (var r : stage1Results) wins.merge(r.decompilerUsed(), 1L, Long::sum);
        System.out.printf("  %d classes decompiled (%s), %d fell back to a bytecode stub%n",
                stage1Results.size(), wins, stubs);

        System.out.println("== Stage 2: output selection ==");
        System.out.println("  (multi-backend mode: every backend runs per class, OutputSelector picks the winner)");

        System.out.println("== Stage 3: resource & build scaffolding ==");
        List<ClassInfo> outOfScope = stage0.normalizedClasses().stream().filter(c -> !c.inScope()).toList();
        var fingerprints = new DependencyFingerprinter().fingerprint(outOfScope);
        new BuildScaffolder().scaffold(config, stage0.resources(), outOfScope, fingerprints);
        System.out.printf("  %d bundled library classes: %d identified, %d vendored%n",
                outOfScope.size(), fingerprints.identifiedByInternalName().size(),
                fingerprints.unidentifiedInternalNames().size());

        System.out.println("== Stage 4: iterative compile-fix loop ==");
        var loopReport = new CompileFixLoop().run(config);
        System.out.printf("  converged=%s after %d iteration(s), %d error(s) remaining%n",
                loopReport.converged(), loopReport.iterations().size(), loopReport.remainingErrorSummaries().size());

        loopReport = runSwapRounds(config, stage0.normalizedClasses(), stage1Results, loopReport);
        System.out.printf("  final: %d error(s) remaining%n",
                loopReport.remainingErrorSummaries().size());

        System.out.println();
        System.out.println("Output project: " + config.projectDir());
        System.out.println("Manifests:      " + config.outputDir().resolve("manifests"));
    }

    /**
     * Heuristic scoring can't see compilability, so backing the wrong
     * backend shows up only here, as errors. Each round re-decompiles the
     * currently-failing files with backends that haven't won them yet and
     * keeps the swap only if the whole tree's error count strictly drops
     * (reverting otherwise), which makes a mediocre heuristic harmless:
     * bad swaps can't stick. Bounded and terminating: every file exhausts
     * its untried backends, and only strict improvements are kept.
     */
    private static final int MAX_SWAP_ROUNDS = 2;

    private CompileFixLoop.LoopReport runSwapRounds(PipelineConfig config, List<ClassInfo> normalizedClasses,
                                                        List<DecompileResult> stage1Results,
                                                        CompileFixLoop.LoopReport loopReport) throws Exception {
        Map<String, ClassInfo> byName = new LinkedHashMap<>();
        for (ClassInfo ci : normalizedClasses) byName.put(ci.internalName(), ci);
        List<DecompileResult> merged = new ArrayList<>(stage1Results);
        // Backends already SELECTED per file (initial winners first).
        Map<String, Set<String>> selected = new LinkedHashMap<>();
        // Backends that ever succeeded per file (swap candidates).
        Map<String, Set<String>> successful = new LinkedHashMap<>();
        for (DecompileResult r : stage1Results) {
            if (!r.isStub()) selected.computeIfAbsent(r.internalName(), k -> new LinkedHashSet<>())
                    .add(r.decompilerUsed());
            Set<String> ok = new LinkedHashSet<>();
            for (var entry : r.attemptLog()) {
                if (entry.outcome() == Outcome.SUCCESS) {
                    ok.add(entry.decompiler());
                }
            }
            successful.put(r.internalName(), ok);
        }

        int bestErrors = loopReport.remainingErrorSummaries().size();
        Stage1Runner stage1 = new Stage1Runner();

        for (int round = 1; round <= MAX_SWAP_ROUNDS; round++) {
            if (loopReport.converged()) break;
            // Failing files with a successful-but-unselected backend left.
            Map<ClassInfo, Set<String>> targets = new LinkedHashMap<>();
            for (String internalName : loopReport.failingFiles()) {
                ClassInfo ci = byName.get(internalName);
                if (ci == null) continue; // hand-written shim, not decompiled
                Set<String> untried = new LinkedHashSet<>(
                        successful.getOrDefault(internalName, Set.of()));
                untried.removeAll(selected.getOrDefault(internalName, Set.of()));
                if (untried.isEmpty()) continue;
                targets.put(ci, untried);
            }
            if (targets.isEmpty()) {
                if (round == 1) {
                    System.out.println("  swap rounds: no failing decompiled files with untried backends");
                }
                break;
            }
            System.out.printf("  swap round %d: retrying %d failing files with alternate backends%n",
                    round, targets.size());

            Map<String, byte[]> backup = new LinkedHashMap<>();
            for (ClassInfo ci : targets.keySet()) {
                Path file = config.decompiledSourcesDir().resolve(ci.internalName() + ".java");
                backup.put(ci.internalName(), Files.readAllBytes(file));
            }
            List<DecompileResult> swapped = stage1.redecompile(config, normalizedClasses, targets);
            Map<String, DecompileResult> swappedByName = new LinkedHashMap<>();
            for (DecompileResult r : swapped) {
                swappedByName.put(r.internalName(), r);
                if (!r.isStub()) {
                    selected.computeIfAbsent(r.internalName(), k -> new LinkedHashSet<>())
                            .add(r.decompilerUsed());
                }
            }
            // Files with no swap output keep their current selection, but
            // their attempted backends are now exhausted for next rounds.
            for (ClassInfo target : targets.keySet()) {
                String internalName = target.internalName();
                if (!swappedByName.containsKey(internalName)) {
                    selected.computeIfAbsent(internalName, k -> new LinkedHashSet<>())
                            .addAll(successful.getOrDefault(internalName, Set.of()));
                }
            }
            for (int i = 0; i < merged.size(); i++) {
                DecompileResult replacement = swappedByName.get(merged.get(i).internalName());
                if (replacement != null) merged.set(i, replacement);
            }

            loopReport = new CompileFixLoop().run(config);
            int errors = loopReport.remainingErrorSummaries().size();
            if (errors < bestErrors) {
                System.out.printf("  swap round %d kept: %d -> %d errors%n", round, bestErrors, errors);
                bestErrors = errors;
                Stage1Runner.writeManifest(config, merged);
            } else {
                System.out.printf("  swap round %d reverted: %d -> %d errors%n", round, bestErrors, errors);
                for (var entry : backup.entrySet()) {
                    Path file = config.decompiledSourcesDir().resolve(entry.getKey() + ".java");
                    Files.createDirectories(file.getParent());
                    Files.write(file, entry.getValue());
                }
                loopReport = new CompileFixLoop().run(config);
                bestErrors = loopReport.remainingErrorSummaries().size();
            }
        }
        return loopReport;
    }
}
