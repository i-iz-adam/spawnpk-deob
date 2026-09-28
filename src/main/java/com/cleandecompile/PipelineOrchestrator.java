package com.cleandecompile;

import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.stage0.Stage0Runner;
import com.cleandecompile.stage1.DecompileResult;
import com.cleandecompile.stage1.DecompileResult.Outcome;
import com.cleandecompile.stage1.Stage1Runner;

import com.cleandecompile.stage3.BuildScaffolder;
import com.cleandecompile.stage3.DependencyFingerprinter;
import com.cleandecompile.stage4.CompileFixLoop;

import com.sun.source.util.JavacTask;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

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
     * backend shows up only here, as errors.
     */
    private static final int MAX_SWAP_ROUNDS = 2;

    /** A compile-fix loop to consult, injectable so the swap-round
     *  bookkeeping can be driven without running javac. */
    @FunctionalInterface
    public interface CompileProbe {
        CompileFixLoop.LoopReport compile() throws Exception;
    }

    private CompileFixLoop.LoopReport runSwapRounds(PipelineConfig config, List<ClassInfo> normalizedClasses,
                                                    List<DecompileResult> stage1Results,
                                                    CompileFixLoop.LoopReport loopReport) throws Exception {
        return runSwapRounds(config, normalizedClasses, stage1Results, loopReport, new Stage1Runner(),
                () -> new CompileFixLoop().run(config)).report();
    }

    /**
     * Heuristic scoring can't see compilability, so backing the wrong
     * backend shows up only here, as errors. Each round re-decompiles the
     * currently-failing files with backends that haven't won them yet and
     * keeps the swap only if the whole tree's error count strictly drops
     * (reverting otherwise), which makes a mediocre heuristic harmless:
     * bad swaps can't stick. Bounded and terminating: every file exhausts
     * its untried backends, and only strict improvements are kept.
     *
     * <p>Two things get a file back on its own, before the tree-wide
     * verdict. The first is a swap that does not parse: a decompiler's
     * reconstruction of a control-flow jump can be a construct Java has no
     * syntax for (CFR's {@code ** GOTO lbl-1000}), and one unparseable file
     * stops the compiler attributing the rest of the tree, so the count
     * collapses and the round looks like a triumph while the file is broken.
     * Counts are not a measurement of a tree that does not parse, so the
     * check happens on file text, before anything is compiled -- judged at
     * the same release level the compile uses, so the two cannot disagree.
     * A file that did not parse before the round either is nobody's swap
     * problem: it is put back without spending a backend on it, and it takes
     * the round's count with it, because a tree that cannot attribute is not
     * measurable however few errors it reports. The same goes for a file
     * broken by a fixer the round never touched, which no per-file check can
     * see -- hence the whole tree is parsed again after each compile a round
     * judges, and any file found there makes that count unjudgeable too.
     *
     * <p>The second is a file whose own error count rose. For a bounded
     * swap -- two backends, no other variables -- a rising count means the
     * other backend is simply worse for that file, and one file's worth of
     * damage should not cost the round the files it genuinely fixed. The
     * restore is not reversible: the round consumes the backend it tried, so
     * the file is never offered that backend again, and the next round sees
     * only what is left. That is the intended trade -- keeping a file we know
     * to be worse is not the conservative option.
     *
     * <p>The tree-wide rule is unchanged: a round is kept only if the
     * adjusted count strictly drops, otherwise the whole round is reverted.
     * It is also applied before the per-file comparison, so a round that is
     * going to be thrown away wholesale costs one compile and no per-file
     * bookkeeping.
     *
     * <p>Cost, for a run that does not converge: the whole tree is parsed once
     * up front and once after each compile a round judges -- one such parse per
     * round, plus one more in a round that restores a file and recompiles --
     * and each round also parses its own targets twice, their backups and
     * then their swapped text. That is at most {@code 1 + 2 * 3} whole-tree
     * parses and {@code 2 * 2 * 2} per-file ones. Each round spends one
     * compile to judge it, one more only if the per-file comparison restores
     * something, and one more if the round is reverted wholesale: at most
     * three {@code CompileFixLoop.run} calls per round, so six in the rounds
     * and seven including the caller's own Stage 4 run before them. A run that
     * has already converged does none of this.
     */

    /** The rounds' bookkeeping alongside the report it ends on, so a caller
     *  (and a test) can see what the rounds decided the tree is. */
    public record SwapOutcome(CompileFixLoop.LoopReport report, SwapBookkeeping bookkeeping) {}

    public SwapOutcome runSwapRounds(PipelineConfig config, List<ClassInfo> normalizedClasses,
                                     List<DecompileResult> stage1Results,
                                     CompileFixLoop.LoopReport loopReport,
                                     Stage1Runner stage1, CompileProbe probe) throws Exception {
        Map<String, ClassInfo> byName = new LinkedHashMap<>();
        for (ClassInfo ci : normalizedClasses) byName.put(ci.internalName(), ci);
        SwapBookkeeping bookkeeping = new SwapBookkeeping(stage1Results);

        int bestErrors = loopReport.remainingErrorSummaries().size();
        Path sourceRoot = config.decompiledSourcesDir();
        String releaseLevel = config.releaseLevel();

        // A run that has already converged has no round to improve, so it
        // should not pay for a whole-tree parse to find that out.
        if (loopReport.converged()) {
            return new SwapOutcome(loopReport, new SwapBookkeeping(stage1Results));
        }

        // The floor every round is measured against has to be a real
        // measurement. A file that does not parse silences the compiler for
        // the whole tree, and the per-file gate cannot see it -- that file is
        // not a target, so no round ever rewrote it -- which would leave the
        // rounds comparing every tree against a count that was never taken
        // and reverting forever.
        List<String> unparseableTree = unparseableTree(sourceRoot, releaseLevel);
        if (!unparseableTree.isEmpty()) {
            System.out.printf("  swap rounds: skipped, %d file(s) in the tree do not parse, so the error count "
                    + "is not a measurement: %s%n", unparseableTree.size(), named(unparseableTree));
            return new SwapOutcome(loopReport, new SwapBookkeeping(stage1Results));
        }

        Set<String> resyncAfterCompile = new LinkedHashSet<>();
        for (int round = 1; round <= MAX_SWAP_ROUNDS; round++) {
            if (loopReport.converged()) break;
            Map<ClassInfo, Set<String>> targets = bookkeeping.swapTargets(loopReport.failingFiles(), byName);
            if (targets.isEmpty()) {
                if (round == 1) {
                    System.out.println("  swap rounds: no failing decompiled files with untried backends");
                }
                break;
            }
            System.out.printf("  swap round %d: retrying %d failing files with alternate backends%n",
                    round, targets.size());

            Map<String, Integer> countsBefore = errorCountsByFile(loopReport, sourceRoot);
            List<String> targeted = targets.keySet().stream().map(ClassInfo::internalName).toList();
            resyncAfterCompile.addAll(targeted);
            Map<String, byte[]> backup = new LinkedHashMap<>();
            for (ClassInfo ci : targets.keySet()) {
                backup.put(ci.internalName(), Files.readAllBytes(sourceRoot.resolve(ci.internalName() + ".java")));
            }
            Map<String, Boolean> backupParses = backupParses(backup, targeted, releaseLevel);
            SwapRoundState before = bookkeeping.snapshot();
            List<DecompileResult> swapped = stage1.redecompile(config, normalizedClasses, targets);
            bookkeeping.applySwaps(targets, swapped);

            // Every file this round rewrote has to be valid Java before the
            // count comparison reads anything: an unparseable one silences
            // the compiler for the whole tree, so its "improvement" is an
            // artefact. Text only -- no compile, no fixer, no bookkeeping
            // beyond the one file it undoes.
            List<String> unparseable = unparseableFiles(sourceRoot, targeted, releaseLevel);
            List<String> brokenBySwap = brokenByTheSwap(unparseable, backupParses);
            List<String> needsHand = new ArrayList<>(unparseable);
            needsHand.removeAll(brokenBySwap);
            // A file no backend can fix silences the compiler just as much
            // after it is put back, so from here on this round's count is not
            // a measurement: it must neither be compared against the floor nor
            // become one.
            boolean measurable = needsHand.isEmpty();
            if (!brokenBySwap.isEmpty()) {
                System.out.printf("  swap round %d: restoring %d file(s) the swap left unparseable: %s%n",
                        round, brokenBySwap.size(), brokenBySwap);
            }
            if (!needsHand.isEmpty()) {
                System.out.printf("  swap round %d: %d file(s) did not parse before the round either, so no "
                        + "backend can help them, none is spent, and the count this round measures is not a "
                        + "measurement -- they need a hand: %s%n", round, needsHand.size(), needsHand);
            }
            restoreFiles(sourceRoot, backup, unparseable, bookkeeping, before);
            for (String hopeless : needsHand) bookkeeping.unselect(before, hopeless);

            loopReport = probe.compile();
            int errors = loopReport.remainingErrorSummaries().size();
            measurable = measurable && treeIsMeasurable(round, errors, sourceRoot, releaseLevel);

            // Only a round that is going to be kept is worth adjusting, and
            // only it may cost a second compile. A file just put back is not
            // judged again: Stage 4's own per-file revert cannot vouch for
            // it (it is a failing file by construction), so a fixer that
            // worsens it afterwards is not the swap's doing, and undoing it
            // again would cost a compile to learn nothing.
            if (worthKeeping(measurable, errors, bestErrors)) {
                List<String> regressed = regressedFiles(targeted, countsBefore,
                        errorCountsByFile(loopReport, sourceRoot), unparseable);
                if (!regressed.isEmpty()) {
                    System.out.printf("  swap round %d: restoring %d file(s) the swap made worse: %s%n",
                            round, regressed.size(), regressed);
                    restoreFiles(sourceRoot, backup, regressed, bookkeeping, before);
                    // The verdict below, and every number reported after it,
                    // has to describe the tree as it will actually be left.
                    loopReport = probe.compile();
                    errors = loopReport.remainingErrorSummaries().size();
                    // The restore changed the tree, so what the pre-round
                    // check said about it no longer describes what we are
                    // about to judge.
                    measurable = measurable && treeIsMeasurable(round, errors, sourceRoot, releaseLevel);
                }
            }

            // The compile that closes the round rewrites files whatever the
            // diagnostics say, so the manifest is written from what is on
            // disk afterwards -- on both branches, or a final revert would
            // leave the manifest describing a tree that no longer exists.
            resyncSources(sourceRoot, new ArrayList<>(resyncAfterCompile), bookkeeping);
            if (worthKeeping(measurable, errors, bestErrors)) {
                System.out.printf("  swap round %d kept: %d -> %d errors%n", round, bestErrors, errors);
                bestErrors = errors;
                Stage1Runner.writeManifest(config, bookkeeping.merged());
            } else {
                System.out.printf("  swap round %d reverted: %d -> %d errors%n", round, bestErrors, errors);
                revertRound(sourceRoot, backup, bookkeeping, before);
                loopReport = probe.compile();
                if (measurable) {
                    bestErrors = loopReport.remainingErrorSummaries().size();
                } else {
                    // The reverted tree is the one the floor was taken from,
                    // so the floor still describes it; re-seeding it from a
                    // suppressed count would make every later round lose.
                    System.out.printf("  swap round %d: the floor stays at %d errors, since the count that "
                            + "replaced it was not a measurement%n", round, bestErrors);
                }
                resyncSources(sourceRoot, new ArrayList<>(resyncAfterCompile), bookkeeping);
                Stage1Runner.writeManifest(config, bookkeeping.merged());
            }
        }
        return new SwapOutcome(loopReport, bookkeeping);
    }

    /**
     * The post-round mirror of the whole-tree precheck. A compile's own
     * fixers can break a file the round never touched, and one unparseable
     * file silences the compiler for everything else -- so the count that
     * compile reports is the silence, not the tree, and a round judged on it
     * would be kept for a phantom improvement. Neither the swap round nor its
     * bookkeeping can see such a file: the gate only looks at what the round
     * wrote, and a file no round wrote is nobody's business until the tree is
     * parsed. Hence the same whole-tree pass, after the compile, with the
     * same primitive and the same release level.
     */
    private static boolean treeIsMeasurable(int round, int errors, Path sourceRoot, String releaseLevel) {
        List<String> unparseable = unparseableTree(sourceRoot, releaseLevel);
        if (unparseable.isEmpty()) return true;
        System.out.printf("  swap round %d: %d file(s) in the tree do not parse after this round's compile, so "
                + "its %d error(s) are not a measurement and the round cannot be judged on them: %s%n",
                round, unparseable.size(), errors, named(unparseable));
        return false;
    }

    /**
     * Whether a round is kept. A round whose count is not a measurement --
     * because a file no backend can fix still does not parse, or because one
     * a fixer broke does -- is never kept, however few errors it reports: that
     * count is the suppression talking, and keeping it would make the silence
     * the floor every later round has to beat.
     */
    public static boolean worthKeeping(boolean measurable, int errors, int bestErrors) {
        return measurable && errors < bestErrors;
    }

    private static String named(List<String> internalNames) {
        int named = Math.min(internalNames.size(), 5);
        return String.join(", ", internalNames.subList(0, named))
                + (internalNames.size() > named ? " (and " + (internalNames.size() - named) + " more)" : "");
    }

    /**
     * The targeted files whose source the swap left unparseable. Parsing
     * needs no symbol resolution, so this is decided by the syntax alone and
     * no message has to be classified: whatever the construct, a file javac
     * cannot parse is a file the compiler will stop attributing the rest of
     * the tree over. A file that cannot even be read counts as unparseable
     * -- an unverified swap is not one to keep.
     *
     * <p>Judged at {@code releaseLevel}, the same level
     * {@link CompileFixLoop}'s compile uses: a file the gate calls valid but
     * the compile rejects is exactly the failure the gate is here to stop,
     * and a {@code record} is the easy way to walk into it.
     */
    public static List<String> unparseableFiles(Path sourceRoot, List<String> internalNames,
                                                 String releaseLevel) {
        Map<Path, String> byPath = new LinkedHashMap<>();
        for (String internalName : internalNames) {
            byPath.put(sourceRoot.resolve(internalName + ".java").toAbsolutePath().normalize(), internalName);
        }
        return unparseable(byPath, releaseLevel);
    }

    /**
     * The {@code .java} files in the tree that do not parse, as internal
     * names. The whole tree, not just a round's targets: the floor the rounds
     * compare against is only a measurement if nothing outside it is silencing
     * the compiler, and a file that no round rewrote cannot be caught later.
     *
     * <p>A non-empty result always means "not a measurement", including when
     * the tree could not be read at all: the single element is then the
     * reason, so a caller that skips the rounds on a non-empty result does the
     * right thing rather than the dangerous one.
     */
    public static List<String> unparseableTree(Path sourceRoot, String releaseLevel) {
        Map<Path, String> byPath = new LinkedHashMap<>();
        try (var walk = Files.walk(sourceRoot)) {
            walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> byPath.put(
                    p.toAbsolutePath().normalize(), treeInternalName(p, sourceRoot)));
        } catch (IOException | RuntimeException e) {
            System.out.printf("  swap rounds: the source tree at %s cannot be read (%s: %s), so no swap can be "
                    + "judged parseable%n", sourceRoot, e.getClass().getSimpleName(), e.getMessage());
            return List.of("the source tree at " + sourceRoot);
        }
        return unparseable(byPath, releaseLevel);
    }

    /** Whether each named file's pre-round bytes parse, read from the backup
     *  so the round's own writes cannot be mistaken for the original. */
    public static Map<String, Boolean> backupParses(Map<String, byte[]> backup, List<String> internalNames,
                                                    String releaseLevel) {
        Map<String, Boolean> parses = new LinkedHashMap<>();
        for (String internalName : internalNames) {
            byte[] bytes = backup.get(internalName);
            parses.put(internalName, bytes != null && parses(bytes, internalName, releaseLevel));
        }
        return parses;
    }

    /** The files the swap is to blame for: unparseable now, sound before it.
     *  The rest were already broken, so no backend this round tried is going
     *  to be any good and none of them should be spent. */
    public static List<String> brokenByTheSwap(List<String> unparseable, Map<String, Boolean> backupParses) {
        List<String> broken = new ArrayList<>();
        for (String internalName : unparseable) {
            if (backupParses.getOrDefault(internalName, true)) broken.add(internalName);
        }
        return broken;
    }

    private static String treeInternalName(Path file, Path sourceRoot) {
        String relative = sourceRoot.toAbsolutePath().normalize()
                .relativize(file.toAbsolutePath().normalize()).toString();
        return relative.substring(0, relative.length() - ".java".length())
                .replace(file.getFileSystem().getSeparator(), "/");
    }

    /** The files in {@code byPath} javac cannot parse, by internal name, in
     *  the order they were offered. One task for the lot: each diagnostic
     *  names its own source, so a single parse settles all of them. */
    private static List<String> unparseable(Map<Path, String> byPath, String releaseLevel) {
        List<String> unparseable = new ArrayList<>();
        if (byPath.isEmpty()) return unparseable;
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            System.out.println("  swap rounds: no Java compiler is available, so no source can be judged "
                    + "parseable and every swap is treated as unverified");
            unparseable.addAll(byPath.values());
            return unparseable;
        }
        Set<Path> broken = new LinkedHashSet<>();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fm, diagnostics,
                    List.of("-proc:none", "-nowarn", "--release", releaseLevel), null,
                    fm.getJavaFileObjectsFromPaths(new ArrayList<>(byPath.keySet())));
            task.parse();
        } catch (IOException | RuntimeException e) {
            System.out.printf("  swap rounds: the parse failed (%s: %s), so no source can be judged parseable "
                    + "and every swap is treated as unverified%n", e.getClass().getSimpleName(), e.getMessage());
            unparseable.addAll(byPath.values());
            return unparseable;
        }
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() != Diagnostic.Kind.ERROR || d.getSource() == null) continue;
            try {
                Path file = Path.of(d.getSource().toUri()).toAbsolutePath().normalize();
                if (byPath.containsKey(file)) broken.add(file);
            } catch (RuntimeException e) {
                // Unresolvable source: not attributable to a file we offered.
            }
        }
        for (var entry : byPath.entrySet()) {
            if (broken.contains(entry.getKey())) unparseable.add(entry.getValue());
        }
        return unparseable;
    }

    /** Parses a source that is not in the tree, so a backup can be judged
     *  without writing it back over the swap it stands in for. Unreadable or
     *  unparseable both answer false: the round's own log then names the file
     *  as needing a hand, which is where that verdict is traced. */
    private static boolean parses(byte[] source, String internalName, String releaseLevel) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) return false;
        JavaFileObject unit = new SimpleJavaFileObject(
                URI.create("file:///swap-round-backup/" + internalName + ".java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return new String(source, StandardCharsets.UTF_8);
            }
        };
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fm, diagnostics,
                    List.of("-proc:none", "-nowarn", "--release", releaseLevel), null, List.of(unit));
            task.parse();
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return diagnostics.getDiagnostics().stream()
                .noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
    }

    /**
     * Re-reads the files a round wrote -- kept swaps as much as restored
     * ones, since a compile that follows either of them rewrites files (its
     * artifact and string-concat fixers scan the whole tree whatever the
     * diagnostics say) -- so the bookkeeping, and so the manifest written
     * from it, describes what is on disk rather than what the backend
     * emitted. A file that is not there is left alone: it was not written
     * either.
     */
    public static void resyncSources(Path sourceRoot, List<String> internalNames,
                                     SwapBookkeeping bookkeeping) throws IOException {
        for (String internalName : internalNames) {
            Path file = sourceRoot.resolve(internalName + ".java");
            if (Files.exists(file)) bookkeeping.updateSource(internalName, Files.readString(file));
        }
    }

    /** {@code "<source path>:<line>: <message>"} entries, counted per
     *  slash-form internal name. A file with no diagnostics is simply absent,
     *  which every read of these counts treats as zero. */
    public static Map<String, Integer> errorCountsByFile(CompileFixLoop.LoopReport report, Path sourceRoot) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String summary : report.remainingErrorSummaries()) {
            String internalName = internalNameOf(summary, sourceRoot);
            if (internalName != null) counts.merge(internalName, 1, Integer::sum);
        }
        return counts;
    }

    private static final Pattern SUMMARY_SOURCE = Pattern.compile("^(.*?):(\\d+):");

    /** The internal name a summary's source path denotes, or null when the
     *  diagnostic belongs to no file in the tree: javac spells a source-less
     *  error "?", and a path outside the source root is not ours to restore.
     *  Both sides are absolutized and normalized, as everywhere else a
     *  diagnostic path is turned into a tree key -- a configured root may be
     *  relative, or spelled with {@code ..} and nothing would ever match.
     *  The source is matched lazily, so a Windows drive colon cannot be
     *  mistaken for the line-number separator. */
    static String internalNameOf(String summary, Path sourceRoot) {
        var matcher = SUMMARY_SOURCE.matcher(summary);
        if (!matcher.find()) return null;
        Path file;
        try {
            file = Path.of(matcher.group(1)).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        Path root = sourceRoot.toAbsolutePath().normalize();
        if (!file.startsWith(root)) return null;
        String relative = root.relativize(file).toString();
        if (!relative.endsWith(".java")) return null;
        String internalName = relative.substring(0, relative.length() - ".java".length())
                .replace(file.getFileSystem().getSeparator(), "/");
        return internalName.isEmpty() ? null : internalName;
    }

    /** The targeted files the swap made worse: strictly more errors than
     *  before it, counting an absent file as none. Anything this round has
     *  already put back is skipped -- it is a failing file by construction,
     *  so Stage 4's own per-file revert cannot vouch for it, and a fixer
     *  that worsens it afterwards is not the swap's doing. */
    public static List<String> regressedFiles(List<String> targeted, Map<String, Integer> before,
                                              Map<String, Integer> after, List<String> alreadyRestored) {
        List<String> regressed = new ArrayList<>();
        for (String internalName : targeted) {
            if (alreadyRestored.contains(internalName)) continue;
            if (after.getOrDefault(internalName, 0) > before.getOrDefault(internalName, 0)) {
                regressed.add(internalName);
            }
        }
        return regressed;
    }

    /** Gives back the files a round undid: their sources, and the merged
     *  entries the manifest is written from, so the manifest keeps
     *  describing what is on disk. Unlike {@link #revertRound} this leaves
     *  the backends the round consumed consumed -- it really did try them,
     *  and the file it got is the better of the two. */
    public static void restoreFiles(Path sourceRoot, Map<String, byte[]> backup, List<String> internalNames,
                                    SwapBookkeeping bookkeeping, SwapRoundState before) throws IOException {
        for (String internalName : internalNames) {
            byte[] bytes = backup.get(internalName);
            if (bytes == null) {
                System.out.printf("  swap round: %s has no backup to restore from, and is not recorded in the "
                        + "manifest either%n", internalName);
                continue;
            }
            Path file = sourceRoot.resolve(internalName + ".java");
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
            bookkeeping.restoreOne(before, internalName);
        }
    }

    /** Undoes a reverted round on disk and in the bookkeeping, so a later
     *  kept round's manifest agrees with the files it describes. */
    public static void revertRound(Path sourceRoot, Map<String, byte[]> backup, SwapBookkeeping bookkeeping,
                                   SwapRoundState before) throws IOException {
        for (var entry : backup.entrySet()) {
            Path file = sourceRoot.resolve(entry.getKey() + ".java");
            Files.createDirectories(file.getParent());
            Files.write(file, entry.getValue());
        }
        bookkeeping.restore(before);
    }

    /**
     * The per-file backend bookkeeping swap rounds mutate, kept apart from
     * the round loop so both the target computation and the revert path are
     * unit-testable without a compile-fix loop.
     */
    public static final class SwapBookkeeping {

        /** Merged results in Stage 1 order; the manifest's source of truth. */
        private final List<DecompileResult> merged;
        /** Backends already selected per file (initial winners first). */
        private final Map<String, Set<String>> selected = new LinkedHashMap<>();
        /** Backends that ever succeeded per file (swap candidates). */
        private final Map<String, Set<String>> successful = new LinkedHashMap<>();

        public SwapBookkeeping(List<DecompileResult> stage1Results) {
            this.merged = new ArrayList<>(stage1Results);
            for (DecompileResult r : stage1Results) {
                if (!r.isStub()) {
                    selected.computeIfAbsent(r.internalName(), k -> new LinkedHashSet<>())
                            .add(r.decompilerUsed());
                }
                Set<String> ok = new LinkedHashSet<>();
                for (var entry : r.attemptLog()) {
                    if (entry.outcome() == Outcome.SUCCESS) {
                        ok.add(entry.decompiler());
                    }
                }
                successful.put(r.internalName(), ok);
            }
        }

        public List<DecompileResult> merged() {
            return merged;
        }

        public Map<String, Set<String>> selected() {
            return selected;
        }

        public Map<String, Set<String>> successful() {
            return successful;
        }

        /**
         * Failing files that still have a successful-but-unselected backend,
         * each mapped to the backends to exclude for it. See
         * {@link Stage1Runner#redecompile} for why the value is the winners
         * and not the untried set.
         */
        public Map<ClassInfo, Set<String>> swapTargets(List<String> failingFiles,
                                                        Map<String, ClassInfo> byName) {
            Map<ClassInfo, Set<String>> targets = new LinkedHashMap<>();
            for (String internalName : failingFiles) {
                ClassInfo ci = byName.get(internalName);
                if (ci == null) continue; // hand-written shim, not decompiled
                Set<String> untried = new LinkedHashSet<>(
                        successful.getOrDefault(internalName, Set.of()));
                Set<String> winners = selected.getOrDefault(internalName, Set.of());
                untried.removeAll(winners);
                if (untried.isEmpty()) continue;
                targets.put(ci, new LinkedHashSet<>(winners));
            }
            return targets;
        }

        /** Folds a round's results in: winners and newly-successful backends
         *  recorded, exhausted files marked, merged results replaced. */
        public void applySwaps(Map<ClassInfo, Set<String>> targets, List<DecompileResult> swapped) {
            Map<String, DecompileResult> swappedByName = new LinkedHashMap<>();
            for (DecompileResult r : swapped) {
                swappedByName.put(r.internalName(), r);
                if (!r.isStub()) {
                    selected.computeIfAbsent(r.internalName(), k -> new LinkedHashSet<>())
                            .add(r.decompilerUsed());
                    // A backend that only ever succeeds mid-swap is a real
                    // candidate for later rounds, so record it.
                    successful.computeIfAbsent(r.internalName(), k -> new LinkedHashSet<>())
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
        }

        public SwapRoundState snapshot() {
            return new SwapRoundState(List.copyOf(merged), copyOf(selected));
        }

        /** Puts back a snapshot taken before a round that got reverted. */
        public void restore(SwapRoundState before) {
            merged.clear();
            merged.addAll(before.merged());
            selected.clear();
            selected.putAll(copyOf(before.selected()));
        }

        /**
         * Puts one file's pre-round result back, for a round that is kept but
         * whose swap made that one file worse or unparseable. The backends the
         * round consumed stay consumed: both were tried, and the restored
         * source is the better of the two, so there is nothing left to offer
         * that file.
         */
        public void restoreOne(SwapRoundState before, String internalName) {
            DecompileResult original = null;
            for (DecompileResult r : before.merged()) {
                if (r.internalName().equals(internalName)) {
                    original = r;
                    break;
                }
            }
            if (original == null) {
                // A targeted file is always a decompiled class, so it has a
                // merged entry. If it ever has none, the manifest is about to
                // record the round's output for a file we just put back.
                System.out.printf("  swap round: no pre-round result recorded for %s, "
                        + "leaving its manifest entry as the round left it%n", internalName);
                return;
            }
            for (int i = 0; i < merged.size(); i++) {
                if (merged.get(i).internalName().equals(internalName)) {
                    merged.set(i, original);
                    return;
                }
            }
        }

        /**
         * Gives one file's backend selection back without touching anything
         * else, for a swap that taught us nothing: if neither the old source
         * nor the new one parses, the backend the round tried is no better
         * than what the file already had, and burning it would cost the file
         * its last option for no gain.
         */
        public void unselect(SwapRoundState before, String internalName) {
            Set<String> previous = before.selected().get(internalName);
            if (previous == null) {
                System.out.printf("  swap round: no backend selection recorded for %s to give back%n", internalName);
                return;
            }
            selected.put(internalName, new LinkedHashSet<>(previous));
        }

        /**
         * Replaces the recorded source of one file, keeping the backend and
         * attempt log that go with it, so the manifest follows the tree
         * through a fixer's rewrite. A stub entry carries bytecode-recovered
         * text, so it only stays a stub while the text recorded is still that
         * text: anything else was not written by the stub generator and must
         * not be filed as if it were.
         */
        public void updateSource(String internalName, String source) {
            for (int i = 0; i < merged.size(); i++) {
                DecompileResult r = merged.get(i);
                if (r.internalName().equals(internalName)) {
                    merged.set(i, new DecompileResult(r.internalName(), source, r.decompilerUsed(),
                            r.isStub() && source.equals(r.source()), r.attemptLog()));
                    return;
                }
            }
            System.out.printf("  swap round: %s is not recorded in the manifest, so its source on disk "
                    + "cannot be recorded either%n", internalName);
        }

        private static Map<String, Set<String>> copyOf(Map<String, Set<String>> source) {
            Map<String, Set<String>> copy = new LinkedHashMap<>();
            for (var entry : source.entrySet()) {
                copy.put(entry.getKey(), new LinkedHashSet<>(entry.getValue()));
            }
            return copy;
        }
    }

    /** A round's pre-swap bookkeeping, so a revert can undo it. */
    public record SwapRoundState(List<DecompileResult> merged, Map<String, Set<String>> selected) {}
}
