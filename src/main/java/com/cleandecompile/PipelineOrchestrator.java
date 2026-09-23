package com.cleandecompile;

import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.stage0.Stage0Runner;
import com.cleandecompile.stage1.Stage1Runner;
import com.cleandecompile.stage3.BuildScaffolder;
import com.cleandecompile.stage3.DependencyFingerprinter;
import com.cleandecompile.stage4.CompileFixLoop;

import java.util.List;

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
        System.out.printf("  %d classes decompiled, %d fell back to a bytecode stub%n",
                stage1Results.size(), stubs);

        System.out.println("== Stage 2: output selection ==");
        System.out.println("  (single-backend-wins mode active; formatting pass runs per-file during Stage 1 write-out"
                + " once OutputSelector is wired into Stage1Runner -- see stage2/OutputSelector.java)");

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

        System.out.println();
        System.out.println("Output project: " + config.projectDir());
        System.out.println("Manifests:      " + config.outputDir().resolve("manifests"));
    }
}
