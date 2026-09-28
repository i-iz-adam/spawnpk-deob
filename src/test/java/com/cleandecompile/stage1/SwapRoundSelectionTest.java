package com.cleandecompile.stage1;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cleandecompile.PipelineConfig;
import com.cleandecompile.PipelineOrchestrator;
import com.cleandecompile.PipelineOrchestrator.SwapBookkeeping;
import com.cleandecompile.PipelineOrchestrator.SwapRoundState;
import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.stage4.CompileFixLoop;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Swap-round selection: the round hands Stage 1 a per-file set of backends
 * to EXCLUDE (the ones already selected for that file), so the alternate
 * backend actually runs. It used to be handed the untried set instead, which
 * made the round exclude exactly the backend it wanted to try -- a
 * guaranteed no-op that rewrote nothing and kept the original winner in the
 * manifest (e.g. {@code MenuEntrySwapperPlugin} stayed on Vineflower's
 * broken {@code this::methodNNNN} refs even though CFR's attempt had
 * succeeded).
 *
 * <p>Also guards the bookkeeping the rounds mutate: a reverted round must
 * restore the merged results the manifest is written from, not just the
 * files, and must give back the backends it burned. And the round's
 * tree-wide verdict: an individual file the swap made worse is restored on
 * its own (source and manifest entry) and the rest of the round is kept,
 * because a round that fixes sixty files must not be thrown away over the
 * one it breaks -- and a file whose swapped-in source does not even parse is
 * restored before any count is read, since an unparseable file silences the
 * compiler for the whole tree and would otherwise look like a huge win.
 * Files that did not parse before the round are nobody's swap problem, and a
 * tree that does not parse is not judged at all.
 */
class SwapRoundSelectionTest {

    private static final String INTERNAL_NAME = "rs/plugins/menuswapper/MenuEntrySwapperPlugin";
    private static final String OTHER_NAME = "rs/plugins/menuswapper/OtherSwapperPlugin";

    /** Vineflower-shaped damage: a capturing lambda collapsed into a static
     *  method ref whose arity no same-file declaration can satisfy. */
    private static final String BROKEN_SOURCE = """
            package rs.plugins.menuswapper;

            public class MenuEntrySwapperPlugin {
                public void register() {
                    actions.stream().anyMatch(MenuEntrySwapperPlugin::walk);
                }

                public static void walk(int action, int state) {
                }
            }
            """;

    private static final String CLEAN_SOURCE = """
            package rs.plugins.menuswapper;

            public class MenuEntrySwapperPlugin {
                public void register() {
                    actions.stream().anyMatch(action -> MenuEntrySwapperPlugin.walk(action, 516));
                }

                public static void walk(int action, int state) {
                }
            }
            """;

    private static final String ORIGINAL_SOURCE = "// placeholder written before the swap round\n";

    private record FixedDecompiler(String name, String source) implements Decompiler {

        @Override
        public String decompile(String internalName, Function<String, byte[]> classBytesProvider) {
            return source;
        }
    }

    private PipelineConfig config(Path outputDir) {
        return new PipelineConfig(outputDir.resolve("in.jar"), outputDir, List.of(), 5_000L, 8, null,
                false, "17", "", "", null, null);
    }

    /** Trivial, well-formed bytes: the fake backends never read them, but
     *  Stage 1 passes them through the worker's size-based timeout. */
    private ClassInfo targetClass(String internalName) {
        return new ClassInfo(internalName, new byte[] {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}, true);
    }

    private ClassInfo targetClass() {
        return targetClass(INTERNAL_NAME);
    }

    private Stage1Runner runner() {
        return new Stage1Runner(List.of(
                new FixedDecompiler("first", BROKEN_SOURCE),
                new FixedDecompiler("second", CLEAN_SOURCE)
        ));
    }

    private Path seedFile(PipelineConfig config, String internalName, String source) throws Exception {
        Path file = config.decompiledSourcesDir().resolve(internalName + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        return file;
    }

    private Path seedFile(PipelineConfig config) throws Exception {
        return seedFile(config, INTERNAL_NAME, ORIGINAL_SOURCE);
    }

    @Test
    void excludingTheWinnerRunsTheAlternateBackendAndOverwritesTheFile(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path file = seedFile(config);

        List<DecompileResult> results = runner().redecompile(
                config, List.of(targetClass()), Map.of(targetClass(), Set.of("first")));

        assertEquals(1, results.size(), "the alternate backend must produce a result");
        DecompileResult swapped = results.get(0);
        assertEquals("second", swapped.decompilerUsed(),
                "excluding 'first' must leave 'second' as the only candidate");
        assertEquals(CLEAN_SOURCE, swapped.source());
        assertEquals(CLEAN_SOURCE, Files.readString(file),
                "the file on disk must be overwritten with the alternate backend's source");
    }

    @Test
    void excludingEveryBackendLeavesTheFileUntouched(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path file = seedFile(config);
        byte[] before = Files.readAllBytes(file);

        List<DecompileResult> results = runner().redecompile(
                config, List.of(targetClass()), Map.of(targetClass(), Set.of("first", "second")));

        assertEquals(0, results.size(), "no surviving backend means no result");
        assertArrayEquals(before, Files.readAllBytes(file),
                "a fully excluded file must be left byte-identical");
    }

    @Test
    void excludingNothingLetsTheSelectorPick(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path file = seedFile(config);

        List<DecompileResult> results = runner().redecompile(
                config, List.of(targetClass()), Map.of(targetClass(), Set.of()));

        assertEquals(1, results.size());
        DecompileResult picked = results.get(0);
        assertEquals("second", picked.decompilerUsed(),
                "with nothing excluded both backends compete and the selector rejects the broken ref");
        assertEquals(CLEAN_SOURCE, Files.readString(file));
    }

    /**
     * The worker chain is shared by every target in a round, so it may only
     * drop a backend every target excludes. Dropping the union instead
     * empties the chain whenever two targets disagree, silently no-op'ing
     * the whole round again.
     */
    @Test
    void targetsExcludingDifferentBackendsBothGetRewritten(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        ClassInfo a = targetClass(INTERNAL_NAME);
        ClassInfo b = targetClass(OTHER_NAME);
        seedFile(config, INTERNAL_NAME, ORIGINAL_SOURCE);
        Path otherFile = seedFile(config, OTHER_NAME, ORIGINAL_SOURCE);

        List<DecompileResult> results = runner().redecompile(
                config, List.of(a, b), Map.of(a, Set.of("first"), b, Set.of("second")));

        assertEquals(2, results.size(), "one result per target: neither target may starve the other");
        DecompileResult forA = results.stream().filter(r -> r.internalName().equals(INTERNAL_NAME))
                .findFirst().orElseThrow();
        DecompileResult forB = results.stream().filter(r -> r.internalName().equals(OTHER_NAME))
                .findFirst().orElseThrow();
        assertEquals("second", forA.decompilerUsed(), "A excluded 'first', so it must land on 'second'");
        assertEquals("first", forB.decompilerUsed(), "B excluded 'second', so it must land on 'first'");
        assertEquals(CLEAN_SOURCE, Files.readString(config.decompiledSourcesDir()
                .resolve(INTERNAL_NAME + ".java")));
        assertEquals(BROKEN_SOURCE, Files.readString(otherFile));
    }

    /**
     * Guards the root-cause line itself: {@link SwapBookkeeping#swapTargets}
     * must return the already-selected winners, since that is the exclude
     * set Stage 1 needs. Returning the untried set here -- the original bug
     * -- keeps every other test in this class green while making every swap
     * round a no-op.
     */
    @Test
    void swapTargetsExcludesTheSelectedWinners() {
        ClassInfo a = targetClass(INTERNAL_NAME);
        DecompileResult stage1 = new DecompileResult(INTERNAL_NAME, ORIGINAL_SOURCE, "first", false, List.of(
                new DecompileResult.AttemptLogEntry("first", DecompileResult.Outcome.SUCCESS, null),
                new DecompileResult.AttemptLogEntry("second", DecompileResult.Outcome.SUCCESS, null)));
        SwapBookkeeping bookkeeping = new SwapBookkeeping(List.of(stage1));

        Map<ClassInfo, Set<String>> targets =
                bookkeeping.swapTargets(List.of(INTERNAL_NAME), Map.of(INTERNAL_NAME, a));

        assertEquals(Set.of("first"), targets.get(a),
                "the exclusion set must be the already-selected winner, not the untried 'second'");
    }

    /** A file with no successful-but-unselected backend has nothing to swap
     *  to, so it must not be retried. */
    @Test
    void swapTargetsSkipsFilesWithNoUnsuccessfulBackendLeft() {
        ClassInfo a = targetClass(INTERNAL_NAME);
        DecompileResult stage1 = new DecompileResult(INTERNAL_NAME, ORIGINAL_SOURCE, "first", false, List.of(
                new DecompileResult.AttemptLogEntry("first", DecompileResult.Outcome.SUCCESS, null)));
        SwapBookkeeping bookkeeping = new SwapBookkeeping(List.of(stage1));

        assertTrue(bookkeeping.swapTargets(List.of(INTERNAL_NAME), Map.of(INTERNAL_NAME, a)).isEmpty(),
                "'first' is both successful and selected, so nothing is untried");
    }

    /** Failing files with no decompiled class (hand-written shims) are not
     *  swap targets either. */
    @Test
    void swapTargetsSkipsFilesOutsideTheJar(@TempDir Path tmp) {
        SwapBookkeeping bookkeeping = new SwapBookkeeping(List.of());

        assertTrue(bookkeeping.swapTargets(List.of(INTERNAL_NAME), Map.of()).isEmpty(),
                "a shim with no ClassInfo cannot be re-decompiled");
    }

    /**
     * A reverted round restores files but must restore the merged results
     * too: the manifest is written from {@code merged}, so leaving the
     * swapped source there makes a later kept round record source text that
     * disagrees with the tree on disk.
     */
    @Test
    void revertedRoundLeavesManifestAgreeingWithDisk(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        ClassInfo a = targetClass(INTERNAL_NAME);
        ClassInfo b = targetClass(OTHER_NAME);
        Path fileA = seedFile(config, INTERNAL_NAME, ORIGINAL_SOURCE);
        Path fileB = seedFile(config, OTHER_NAME, ORIGINAL_SOURCE);
        DecompileResult resultA = new DecompileResult(INTERNAL_NAME, ORIGINAL_SOURCE, "first", false, List.of(
                new DecompileResult.AttemptLogEntry("first", DecompileResult.Outcome.SUCCESS, null),
                new DecompileResult.AttemptLogEntry("second", DecompileResult.Outcome.SUCCESS, null)));
        DecompileResult resultB = new DecompileResult(OTHER_NAME, ORIGINAL_SOURCE, "first", false, List.of(
                new DecompileResult.AttemptLogEntry("first", DecompileResult.Outcome.SUCCESS, null),
                new DecompileResult.AttemptLogEntry("second", DecompileResult.Outcome.SUCCESS, null)));
        SwapBookkeeping bookkeeping = new SwapBookkeeping(List.of(resultA, resultB));
        Map<String, ClassInfo> byName = Map.of(INTERNAL_NAME, a, OTHER_NAME, b);

        // Round 1 swaps A, regresses, and reverts.
        Map<ClassInfo, Set<String>> round1 = bookkeeping.swapTargets(List.of(INTERNAL_NAME), byName);
        Map<String, byte[]> backup = Map.of(INTERNAL_NAME, Files.readAllBytes(fileA));
        SwapRoundState before = bookkeeping.snapshot();
        Files.writeString(fileA, CLEAN_SOURCE);
        bookkeeping.applySwaps(round1, List.of(
                new DecompileResult(INTERNAL_NAME, CLEAN_SOURCE, "second", false, List.of())));
        PipelineOrchestrator.revertRound(config.decompiledSourcesDir(), backup, bookkeeping, before);

        // Round 2 swaps B and is kept, so its manifest gets written.
        Map<ClassInfo, Set<String>> round2 = bookkeeping.swapTargets(List.of(OTHER_NAME), byName);
        Files.writeString(fileB, CLEAN_SOURCE);
        bookkeeping.applySwaps(round2, List.of(
                new DecompileResult(OTHER_NAME, CLEAN_SOURCE, "second", false, List.of())));
        Stage1Runner.writeManifest(config, bookkeeping.merged());

        assertEquals(ORIGINAL_SOURCE, Files.readString(fileA), "round 1 was reverted, so the file is the original");
        assertEquals(ORIGINAL_SOURCE, recordedSource(config, INTERNAL_NAME),
                "the manifest kept round 1's swapped source, which is not what is on disk for A");
        assertEquals(CLEAN_SOURCE, Files.readString(fileB));
        assertEquals(CLEAN_SOURCE, recordedSource(config, OTHER_NAME),
                "the kept round's swap must be recorded for B");
    }

    /** A reverted round must give back the backends it consumed, otherwise
     *  round 2 has fewer options than the attempt log says. */
    @Test
    void revertedRoundGivesBackItsBackends(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path file = seedFile(config);
        ClassInfo a = targetClass();
        DecompileResult stage1 = new DecompileResult(INTERNAL_NAME, ORIGINAL_SOURCE, "first", false, List.of(
                new DecompileResult.AttemptLogEntry("first", DecompileResult.Outcome.SUCCESS, null),
                new DecompileResult.AttemptLogEntry("second", DecompileResult.Outcome.SUCCESS, null)));
        SwapBookkeeping bookkeeping = new SwapBookkeeping(List.of(stage1));

        Map<ClassInfo, Set<String>> round1 = bookkeeping.swapTargets(List.of(INTERNAL_NAME), Map.of(INTERNAL_NAME, a));
        SwapRoundState before = bookkeeping.snapshot();
        Map<String, byte[]> backup = Map.of(INTERNAL_NAME, Files.readAllBytes(file));
        Files.writeString(file, CLEAN_SOURCE);
        bookkeeping.applySwaps(round1, List.of(
                new DecompileResult(INTERNAL_NAME, CLEAN_SOURCE, "second", false, List.of())));
        assertEquals(Set.of("first", "second"), bookkeeping.selected().get(INTERNAL_NAME),
                "'second' won the swap");

        PipelineOrchestrator.revertRound(config.decompiledSourcesDir(), backup, bookkeeping, before);

        assertEquals(Set.of("first"), bookkeeping.selected().get(INTERNAL_NAME),
                "the reverted round must not leave 'second' burned");
        assertEquals(Set.of("first"), bookkeeping.swapTargets(List.of(INTERNAL_NAME), Map.of(INTERNAL_NAME, a))
                        .get(a),
                "round 2 must still be a target, excluding only the still-selected 'first'");
    }

    /** A backend that fails in Stage 1 but succeeds during a swap round is a
     *  real candidate afterwards, so it has to be recorded as successful. */
    @Test
    void backendThatSucceedsOnlyDuringASwapBecomesACandidate(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path file = seedFile(config);
        ClassInfo a = targetClass();
        DecompileResult stage1 = new DecompileResult(INTERNAL_NAME, ORIGINAL_SOURCE, "first", false, List.of(
                new DecompileResult.AttemptLogEntry("first", DecompileResult.Outcome.SUCCESS, null),
                new DecompileResult.AttemptLogEntry("second", DecompileResult.Outcome.FAILED, "boom")));
        SwapBookkeeping bookkeeping = new SwapBookkeeping(List.of(stage1));

        // Stage 1 saw 'second' fail, so it is not a target on its own.
        assertTrue(bookkeeping.swapTargets(List.of(INTERNAL_NAME), Map.of(INTERNAL_NAME, a)).isEmpty());

        Map<ClassInfo, Set<String>> round1 = bookkeeping.swapTargets(List.of(INTERNAL_NAME), Map.of(INTERNAL_NAME, a));
        bookkeeping.applySwaps(round1, List.of(
                new DecompileResult(INTERNAL_NAME, CLEAN_SOURCE, "second", false, List.of())));

        assertEquals(Set.of("first", "second"), bookkeeping.successful().get(INTERNAL_NAME),
                "a swap-round success must be recorded as successful");
        assertEquals(ORIGINAL_SOURCE, Files.readString(file), "sanity: bookkeeping alone touches no files");
    }

    /**
     * Counts errors per source file the way a swap round needs them: a round
     * is judged tree-wide, so a single file that ends up worse than it was
     * has to be recognizable, and a file with no diagnostics left must read
     * as zero rather than "missing".
     */
    @Test
    void perFileErrorCountsMapSummariesOntoInternalNames(@TempDir Path tmp) {
        PipelineConfig config = config(tmp);
        Path fileA = config.decompiledSourcesDir().resolve(INTERNAL_NAME + ".java");
        CompileFixLoop.LoopReport report = report(List.of(
                fileA + ":7: illegal start of expression",
                fileA + ":40: ';' expected",
                fileA + ":41: ';' expected",
                fileA + ":12: class OtherSwapperPlugin is public, should be declared in a file named OtherSwapperPlugin.java"
        ), List.of(INTERNAL_NAME));

        Map<String, Integer> counts =
                PipelineOrchestrator.errorCountsByFile(report, config.decompiledSourcesDir());

        assertEquals(Map.of(INTERNAL_NAME, 4), counts,
                "every diagnostic on the file counts, and the source path resolves to its internal name");
        assertEquals(0, counts.getOrDefault(OTHER_NAME, 0),
                "a file with no diagnostics counts as zero, not as missing");
    }

    @Test
    void perFileErrorCountsIgnoreDiagnosticsWithNoFileInTheTree(@TempDir Path tmp) {
        PipelineConfig config = config(tmp);
        CompileFixLoop.LoopReport report = report(List.of(
                "?:1: cannot find symbol",
                "?:2: cannot find symbol",
                tmp.resolve("somewhere/else/Other.java") + ":3: ';' expected"
        ), List.of());

        assertTrue(PipelineOrchestrator.errorCountsByFile(report, config.decompiledSourcesDir()).isEmpty(),
                "'?' and paths outside the source tree belong to no file the orchestrator can restore");
    }

    /**
     * A swap that wrecks one file but helps the others must not be thrown
     * away whole: the wrecked file goes back to its backup (and its manifest
     * entry too, so the manifest keeps describing the file that is actually
     * there), the helped files keep their swap. Reverting only the loser is
     * what keeps a round that fixes sixty files from losing sixty because of
     * the one it breaks.
     *
     * <p>B is given ten errors before the round and one after, because the
     * round only gets its per-file comparison -- and so a second compile --
     * once the tree-wide count says the round is worth keeping at all.
     */
    @Test
    void roundThatRegressesOneFileRestoresItAndKeepsTheOthers(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path fileA = seedFile(config, INTERNAL_NAME, ORIGINAL_A);
        Path fileB = seedFile(config, OTHER_NAME, ORIGINAL_B);
        Path fileC = seedFile(config, THIRD_NAME, ORIGINAL_C);
        List<DecompileResult> stage1Results = List.of(stage1Result(INTERNAL_NAME, ORIGINAL_A),
                stage1Result(OTHER_NAME, ORIGINAL_B), stage1Result(THIRD_NAME, ORIGINAL_C));
        // Swapping A turns 1 error into 8; B's ten collapse to one, C holds.
        Map<String, Integer> errorsBySource = Map.of(
                ORIGINAL_A, 1, ORIGINAL_B, 10, ORIGINAL_C, 1,
                SWAPPED_A, 8, SWAPPED_B, 1, SWAPPED_C, 1);
        TreeProbe probe = new TreeProbe(config, errorsBySource);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator();

        PipelineOrchestrator.SwapOutcome outcome = orchestrator.runSwapRounds(
                config, targetClasses(), stage1Results, probe.peek(),
                roundRunner(SWAPPED_A, SWAPPED_B, SWAPPED_C), probe);
        SwapBookkeeping bookkeeping = outcome.bookkeeping();
        CompileFixLoop.LoopReport finalReport = outcome.report();

        assertEquals(ORIGINAL_A, Files.readString(fileA),
                "A regressed, so the round must have put its pre-swap bytes back");
        assertEquals(SWAPPED_B, Files.readString(fileB), "B improved, so the swap must be kept");
        assertEquals(SWAPPED_C, Files.readString(fileC), "C did not regress, so its swap must stay");
        DecompileResult mergedA = mergedFor(bookkeeping, INTERNAL_NAME);
        assertEquals(ORIGINAL_A, mergedA.source(), "A's manifest entry must be the pre-round result");
        assertEquals("first", mergedA.decompilerUsed());
        DecompileResult mergedB = mergedFor(bookkeeping, OTHER_NAME);
        assertEquals(SWAPPED_B, mergedB.source(), "B's manifest entry must be the swapped result");
        assertEquals("second", mergedB.decompilerUsed());
        assertEquals(ORIGINAL_A, recordedSource(config, INTERNAL_NAME), "the written manifest must match disk");
        assertEquals(SWAPPED_B, recordedSource(config, OTHER_NAME));
        assertEquals(SWAPPED_C, recordedSource(config, THIRD_NAME));
        assertEquals(Set.of("first", "second"), bookkeeping.selected().get(INTERNAL_NAME),
                "a kept round burns the backend it tried, restored file or not");
        assertEquals(3, finalReport.remainingErrorSummaries().size(),
                "the reported total must be the post-restore one (A: 1, B: 1, C: 1), not the 10 the swap produced");
        assertEquals(2, probe.compiles(),
                "one compile to judge the round, one more because the per-file comparison restored A");
    }

    /** When every targeted file got worse there is nothing to keep, so the
     *  round falls back to the whole-round revert -- which also has to give
     *  back the backends it consumed, not just the files. A doomed round is
     *  recognised from its first compile, so no per-file restore is attempted
     *  and no third compile is spent proving it. */
    @Test
    void roundThatRegressesEveryFileFallsBackToTheWholeRoundRevert(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path fileA = seedFile(config, INTERNAL_NAME, ORIGINAL_A);
        Path fileB = seedFile(config, OTHER_NAME, ORIGINAL_B);
        Path fileC = seedFile(config, THIRD_NAME, ORIGINAL_C);
        List<DecompileResult> stage1Results = List.of(stage1Result(INTERNAL_NAME, ORIGINAL_A),
                stage1Result(OTHER_NAME, ORIGINAL_B), stage1Result(THIRD_NAME, ORIGINAL_C));
        Map<String, Integer> errorsBySource = Map.of(
                ORIGINAL_A, 1, ORIGINAL_B, 1, ORIGINAL_C, 1,
                SWAPPED_A, 8, SWAPPED_B, 4, SWAPPED_C, 3);
        TreeProbe probe = new TreeProbe(config, errorsBySource);
        PipelineOrchestrator orchestrator = new PipelineOrchestrator();

        PipelineOrchestrator.SwapOutcome outcome = orchestrator.runSwapRounds(
                config, targetClasses(), stage1Results, probe.peek(),
                roundRunner(SWAPPED_A, SWAPPED_B, SWAPPED_C), probe);
        SwapBookkeeping bookkeeping = outcome.bookkeeping();
        CompileFixLoop.LoopReport finalReport = outcome.report();

        assertEquals(ORIGINAL_A, Files.readString(fileA));
        assertEquals(ORIGINAL_B, Files.readString(fileB));
        assertEquals(ORIGINAL_C, Files.readString(fileC));
        assertEquals(ORIGINAL_A, mergedFor(bookkeeping, INTERNAL_NAME).source());
        assertEquals(ORIGINAL_B, mergedFor(bookkeeping, OTHER_NAME).source());
        assertEquals(ORIGINAL_C, mergedFor(bookkeeping, THIRD_NAME).source());
        assertEquals(Set.of("first"), bookkeeping.selected().get(INTERNAL_NAME),
                "a whole-round revert must give 'second' back; a per-file restore would leave it burned");
        assertEquals(3, finalReport.remainingErrorSummaries().size());
        assertFalse(finalReport.converged());
        assertEquals(4, probe.compiles(),
                "two rounds, two compiles each: one to judge, one to re-measure after the wholesale revert. "
                        + "The per-file comparison must be skipped for a round that is already lost.");
    }

    /**
     * The hole the count comparison cannot see. CFR hands back a class with a
     * {@code ** GOTO lbl-1000} pseudo-label, and one unparseable file aborts
     * javac's attribution tree-wide -- so the swap makes the reported count
     * collapse (here 6 errors become 1) while the tree is in fact no better.
     * On counts alone the round reads as a rout and is kept, broken file and
     * all. The parse gate runs on file text before the compile, sees that the
     * swapped-in source is not valid Java, and puts it back: the tree parses
     * before any count is read, so the counts mean what they say, and the
     * files whose errors the broken one was hiding are neither lost nor
     * falsely reverted.
     */
    @Test
    void unparseableSwapIsCaughtByTheParseGateBeforeAnyCountIsRead(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path fileA = seedFile(config, INTERNAL_NAME, ORIGINAL_A);
        seedFile(config, OTHER_NAME, ORIGINAL_B);
        seedFile(config, THIRD_NAME, ORIGINAL_C);
        List<DecompileResult> stage1Results = List.of(stage1Result(INTERNAL_NAME, ORIGINAL_A),
                stage1Result(OTHER_NAME, ORIGINAL_B), stage1Result(THIRD_NAME, ORIGINAL_C));
        Map<String, Integer> errorsBySource = Map.of(
                ORIGINAL_A, 1, SWAPPED_A_UNPARSEABLE, 1,
                ORIGINAL_B, 2, SWAPPED_B, 1,
                ORIGINAL_C, 3, SWAPPED_C, 1);
        TreeProbe probe = new TreeProbe(config, errorsBySource);

        // The mirage, demonstrated rather than asserted: with the broken file
        // in place the compiler reports its one parse error and nothing for
        // B or C, so a count-only round would read 6 -> 1 and keep the swap.
        Files.writeString(fileA, SWAPPED_A_UNPARSEABLE);
        assertEquals(1, probe.compile().remainingErrorSummaries().size(),
                "sanity: an unparseable file hides every other file's errors, so counts alone are not a verdict");
        assertEquals(List.of(INTERNAL_NAME), probe.unparseableFilesSeen());
        Files.writeString(fileA, ORIGINAL_A);
        probe.forget();

        PipelineOrchestrator.SwapOutcome outcome = new PipelineOrchestrator().runSwapRounds(
                config, targetClasses(), stage1Results, probe.peek(),
                roundRunner(SWAPPED_A_UNPARSEABLE, SWAPPED_B, SWAPPED_C), probe);
        SwapBookkeeping bookkeeping = outcome.bookkeeping();

        assertEquals(List.of(), probe.unparseableFilesSeen(),
                "no compile may ever see the swapped-in unparseable source: the gate restores it first");
        assertEquals(ORIGINAL_A, Files.readString(fileA), "the parse gate put A's bytes back");
        assertEquals(ORIGINAL_A, mergedFor(bookkeeping, INTERNAL_NAME).source(),
                "and its manifest entry with them, although the count comparison never flagged it");
        assertEquals("first", mergedFor(bookkeeping, INTERNAL_NAME).decompilerUsed());
        assertEquals(SWAPPED_B, Files.readString(config.decompiledSourcesDir()
                .resolve(OTHER_NAME + ".java")), "B's swap is not collateral damage: it must be kept");
        assertEquals(SWAPPED_C, Files.readString(config.decompiledSourcesDir()
                .resolve(THIRD_NAME + ".java")), "and neither is C's");
        assertEquals(SWAPPED_B, mergedFor(bookkeeping, OTHER_NAME).source());
        assertEquals(SWAPPED_C, mergedFor(bookkeeping, THIRD_NAME).source());
        assertEquals(3, outcome.report().remainingErrorSummaries().size(),
                "A: 1, B: 1, C: 1 -- the counts of the parseable tree, not the mirage's 1");
        assertEquals(1, probe.compiles(), "the gate runs on file text, so restoring A costs no extra compile");
    }

    /**
     * The manifest is written from the bookkeeping, but the compile-fix loop
     * rewrites the tree it just measured -- its artifact and string-concat
     * fixers scan every file whatever the diagnostics say. So the source
     * recorded for a restored file goes stale the moment the tree is
     * recompiled, and a manifest written from it would describe a file that
     * is not there.
     */
    @Test
    void manifestRecordsWhatTheLastCompileLeftOnDisk(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path fileA = seedFile(config, INTERNAL_NAME, ORIGINAL_A);
        seedFile(config, OTHER_NAME, ORIGINAL_B);
        seedFile(config, THIRD_NAME, ORIGINAL_C);
        List<DecompileResult> stage1Results = List.of(stage1Result(INTERNAL_NAME, ORIGINAL_A),
                stage1Result(OTHER_NAME, ORIGINAL_B), stage1Result(THIRD_NAME, ORIGINAL_C));
        String fixerPass1 = "// a fixer rewrote this, pass 1\n" + ORIGINAL_A;
        String fixerPass2 = "// a fixer rewrote this, pass 2\n" + ORIGINAL_A;
        Map<String, Integer> errorsBySource = Map.of(
                ORIGINAL_A, 1, ORIGINAL_B, 10, ORIGINAL_C, 1,
                SWAPPED_A, 8, SWAPPED_B, 1, SWAPPED_C, 1,
                fixerPass1, 8, fixerPass2, 1);
        TreeProbe probe = new TreeProbe(config, errorsBySource)
                .rewriting(INTERNAL_NAME, fixerPass1, fixerPass2);

        PipelineOrchestrator.SwapOutcome outcome = new PipelineOrchestrator().runSwapRounds(
                config, targetClasses(), stage1Results, probe.peek(),
                roundRunner(SWAPPED_A, SWAPPED_B, SWAPPED_C), probe);

        assertEquals(fixerPass2, Files.readString(fileA),
                "sanity: the compile rewrote A both times, once before the restore and once after it");
        assertEquals(fixerPass2, recordedSource(config, INTERNAL_NAME),
                "the manifest must record the file the final compile left, not the bytes the restore put back");
        assertEquals(fixerPass2, mergedFor(outcome.bookkeeping(), INTERNAL_NAME).source());
    }

    /**
     * A per-file count comparison runs only on a tree the parse gate has
     * already cleared of syntax errors, so a zero it reads is a measurement
     * and not the silence of an aborted compilation: a clean file traded for
     * a broken one really is a regression, and
     * {@link #unparseableSwapIsCaughtByTheParseGateBeforeAnyCountIsRead} is
     * what makes that so. Swap targets come from the failing-file list, so in
     * the real pipeline every target already has at least one error; the rule
     * is stated for the general case rather than the accidental one.
     */
    @Test
    void regressedFilesAreTheOnesWhoseErrorCountRose() {
        assertEquals(List.of(INTERNAL_NAME), PipelineOrchestrator.regressedFiles(
                        List.of(INTERNAL_NAME, OTHER_NAME),
                        Map.of(INTERNAL_NAME, 1, OTHER_NAME, 3),
                        Map.of(INTERNAL_NAME, 8, OTHER_NAME, 1), List.of()),
                "a file that went 1 -> 8 regressed; one that went 3 -> 1 did not");
        assertEquals(List.of(INTERNAL_NAME), PipelineOrchestrator.regressedFiles(
                        List.of(INTERNAL_NAME), Map.<String, Integer>of(), Map.of(INTERNAL_NAME, 1), List.of()),
                "0 -> 1 is a regression: on a parseable tree a clean file traded for a broken one is worse");
        assertEquals(List.of(), PipelineOrchestrator.regressedFiles(
                        List.of(INTERNAL_NAME), Map.<String, Integer>of(), Map.of(INTERNAL_NAME, 1),
                        List.of(INTERNAL_NAME)),
                "but a file this round has already put back is not judged again: whatever a fixer does to it "
                        + "next is not the swap's doing, and re-restoring it would cost a compile to learn nothing");
    }

    /**
     * The mirror case of the parse gate: a file that does not parse is not
     * automatically a swap's fault. If the bytes the round backed up were
     * already broken, the alternate backend is no worse, there is nothing to
     * restore, and no backend should be spent pretending otherwise -- the
     * file stays in the running for whatever backend is left, and what it
     * really needs is a hand.
     *
     * <p>Driven through the pieces rather than a round: the whole-tree
     * baseline check means a file that does not parse at the start of a run
     * stops rounds from starting at all (see
     * {@link #aTreeThatDoesNotParseIsNotJudgedByItsErrorCount}), so a round
     * can only reach this branch if a fixer breaks a file between rounds, and
     * then the count is suppressed, which forces the round to be reverted
     * wholesale -- a revert hands every backend back anyway and would hide
     * what the per-file path does.
     */
    @Test
    void aFileThatDidNotParseBeforeTheRoundIsNotTheSwapsFault(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path fileA = seedFile(config, INTERNAL_NAME, ORIGINAL_A_UNPARSEABLE);
        Path fileB = seedFile(config, OTHER_NAME, ORIGINAL_B);
        SwapBookkeeping bookkeeping = new SwapBookkeeping(List.of(
                stage1Result(INTERNAL_NAME, ORIGINAL_A_UNPARSEABLE), stage1Result(OTHER_NAME, ORIGINAL_B)));
        ClassInfo a = targetClass(INTERNAL_NAME);
        ClassInfo b = targetClass(OTHER_NAME);
        Map<String, ClassInfo> byName = Map.of(INTERNAL_NAME, a, OTHER_NAME, b);
        Map<String, byte[]> backup = Map.of(
                INTERNAL_NAME, Files.readAllBytes(fileA), OTHER_NAME, Files.readAllBytes(fileB));
        SwapRoundState before = bookkeeping.snapshot();
        Map<ClassInfo, Set<String>> targets = bookkeeping.swapTargets(
                List.of(INTERNAL_NAME, OTHER_NAME), byName);

        // The round swaps both, and both swaps come back broken -- but only B's
        // backup was sound.
        Files.writeString(fileA, SWAPPED_A_UNPARSEABLE);
        Files.writeString(fileB, OTHER_UNPARSEABLE);
        bookkeeping.applySwaps(targets, List.of(
                new DecompileResult(INTERNAL_NAME, SWAPPED_A_UNPARSEABLE, "second", false, List.of()),
                new DecompileResult(OTHER_NAME, OTHER_UNPARSEABLE, "second", false, List.of())));
        List<String> unparseable = PipelineOrchestrator.unparseableFiles(
                config.decompiledSourcesDir(), List.of(INTERNAL_NAME, OTHER_NAME), "17");
        Map<String, Boolean> backupParses = PipelineOrchestrator.backupParses(
                backup, List.of(INTERNAL_NAME, OTHER_NAME), "17");

        assertEquals(List.of(INTERNAL_NAME, OTHER_NAME), unparseable);
        assertEquals(Map.of(INTERNAL_NAME, false, OTHER_NAME, true), backupParses,
                "A's backup is as broken as its swap, B's is sound");
        assertEquals(List.of(OTHER_NAME), PipelineOrchestrator.brokenByTheSwap(unparseable, backupParses),
                "only B's breakage is the swap's doing; A needs a fixer that does not exist, not another backend");

        PipelineOrchestrator.restoreFiles(config.decompiledSourcesDir(), backup, unparseable, bookkeeping, before);
        bookkeeping.unselect(before, INTERNAL_NAME);

        assertEquals(Set.of("first"), bookkeeping.selected().get(INTERNAL_NAME),
                "the backend a hopeless swap tried must not stay burned");
        assertEquals(Set.of("first", "second"), bookkeeping.selected().get(OTHER_NAME),
                "B's swap was sound, so its backend is spent");
        assertTrue(bookkeeping.swapTargets(List.of(INTERNAL_NAME), byName).containsKey(a),
                "a later round must still be offered A's remaining backend");
        assertEquals(Set.of("first"), bookkeeping.swapTargets(List.of(INTERNAL_NAME), byName).get(a));
    }

    /**
     * A file the fixers left unparseable between rounds is named as needing a
     * hand rather than another backend: the round's per-file restore puts its
     * bytes back and gives the backend it tried straight back, so the next
     * round can try something else. The round itself is then reverted, since
     * the file's parse error suppresses the whole count and nothing can look
     * like an improvement.
     */
    @Test
    void aFileThatDidNotParseBeforeTheRoundIsNamedAsNeedingAHand(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        seedFile(config, INTERNAL_NAME, ORIGINAL_A);
        seedFile(config, OTHER_NAME, ORIGINAL_B);
        seedFile(config, THIRD_NAME, ORIGINAL_C);
        seedFile(config, FOURTH_NAME, ORIGINAL_D);
        List<DecompileResult> stage1Results = List.of(stage1Result(INTERNAL_NAME, ORIGINAL_A),
                stage1Result(OTHER_NAME, ORIGINAL_B), stage1Result(THIRD_NAME, ORIGINAL_C),
                stage1Result(FOURTH_NAME, ORIGINAL_D));
        // Round 1 trades D in as clean and swaps A, B, C; the "fixer" then
        // leaves D unparseable, which is what round 2 has to cope with.
        Map<String, Integer> errorsBySource = Map.of(
                ORIGINAL_A, 1, ORIGINAL_B, 20, ORIGINAL_C, 1, ORIGINAL_D, 0,
                SWAPPED_A, 8, SWAPPED_B, 1, SWAPPED_C, 1,
                D_UNPARSEABLE, 1, SWAPPED_D_UNPARSEABLE, 1);
        TreeProbe probe = new TreeProbe(config, errorsBySource).rewriting(FOURTH_NAME, ORIGINAL_D, D_UNPARSEABLE);

        String log = capturingStdout(() -> new PipelineOrchestrator().runSwapRounds(
                config, targetClasses(), stage1Results, probe.peek(),
                roundRunner(SWAPPED_A, SWAPPED_B, SWAPPED_C, SWAPPED_D_UNPARSEABLE), probe));

        assertTrue(log.contains("did not parse before the round either"), log);
        assertTrue(log.contains("not a measurement"), log);
        assertTrue(log.contains(FOURTH_NAME), "the file is named: " + log);
        assertTrue(log.contains("swap round 2 reverted"), log);
        assertTrue(log.contains("the floor stays at"),
                "a count that is not a measurement must not re-seed the floor the next round has to beat: " + log);
    }

    /**
     * {@code bestErrors} is a floor the rounds are measured against, so it
     * has to be a real measurement. One unparseable file anywhere in the tree
     * silences the compiler for everything else, and the per-file gate cannot
     * see it -- that file is not a swap target, it was not rewritten by the
     * round. So the tree is parsed once up front, and a tree that does not
     * parse is reported and left alone: no round is attempted at all.
     */
    @Test
    void aTreeThatDoesNotParseIsNotJudgedByItsErrorCount(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        seedFile(config, INTERNAL_NAME, ORIGINAL_A);
        seedFile(config, OTHER_NAME, ORIGINAL_B);
        seedFile(config, THIRD_NAME, ORIGINAL_C);
        // D is clean, so it is not a swap target, and it does not parse.
        Path fileD = seedFile(config, FOURTH_NAME, ORIGINAL_D);
        Files.writeString(fileD, D_UNPARSEABLE);
        List<DecompileResult> stage1Results = List.of(stage1Result(INTERNAL_NAME, ORIGINAL_A),
                stage1Result(OTHER_NAME, ORIGINAL_B), stage1Result(THIRD_NAME, ORIGINAL_C),
                stage1Result(FOURTH_NAME, ORIGINAL_D));
        Map<String, Integer> errorsBySource = Map.of(
                ORIGINAL_A, 1, ORIGINAL_B, 10, ORIGINAL_C, 1, ORIGINAL_D, 0,
                SWAPPED_A, 1, SWAPPED_B, 1, SWAPPED_C, 1, D_UNPARSEABLE, 1);
        TreeProbe probe = new TreeProbe(config, errorsBySource);
        CompileFixLoop.LoopReport incoming = probe.peek();

        String log = capturingStdout(() -> new PipelineOrchestrator().runSwapRounds(
                config, targetClasses(), stage1Results, incoming,
                roundRunner(SWAPPED_A, SWAPPED_B, SWAPPED_C), probe));

        assertEquals(0, probe.compiles(), "no round may be attempted: the count it would compare is not a measurement");
        assertEquals(incoming, new PipelineOrchestrator().runSwapRounds(
                        config, targetClasses(), stage1Results, incoming,
                        roundRunner(SWAPPED_A, SWAPPED_B, SWAPPED_C), probe).report(),
                "the report handed in is the report handed back");
        assertEquals(ORIGINAL_A, Files.readString(config.decompiledSourcesDir().resolve(INTERNAL_NAME + ".java")),
                "and nothing was swapped");
        assertTrue(log.contains("do not parse"), log);
        assertTrue(log.contains(FOURTH_NAME), "the unparseable file is named: " + log);
    }

    /**
     * {@code source} in the manifest means "what is on disk", and the compile
     * that follows every round rewrites files whatever the diagnostics say --
     * so a swap that is KEPT is as stale as a restored one until it is
     * re-read. Here the fixer rewrites the kept swap, twice.
     */
    @Test
    void manifestRecordsWhatTheLastCompileRewroteInAKeptSwap(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        seedFile(config, INTERNAL_NAME, ORIGINAL_A);
        Path fileB = seedFile(config, OTHER_NAME, ORIGINAL_B);
        seedFile(config, THIRD_NAME, ORIGINAL_C);
        List<DecompileResult> stage1Results = List.of(stage1Result(INTERNAL_NAME, ORIGINAL_A),
                stage1Result(OTHER_NAME, ORIGINAL_B), stage1Result(THIRD_NAME, ORIGINAL_C));
        String pass1 = "// a fixer rewrote the kept swap, pass 1\n" + SWAPPED_B;
        String pass2 = "// a fixer rewrote the kept swap, pass 2\n" + SWAPPED_B;
        Map<String, Integer> errorsBySource = Map.of(
                ORIGINAL_A, 1, ORIGINAL_B, 10, ORIGINAL_C, 1,
                SWAPPED_A, 8, SWAPPED_B, 1, SWAPPED_C, 1, pass1, 1, pass2, 1);
        TreeProbe probe = new TreeProbe(config, errorsBySource).rewriting(OTHER_NAME, pass1, pass2);

        PipelineOrchestrator.SwapOutcome outcome = new PipelineOrchestrator().runSwapRounds(
                config, targetClasses(), stage1Results, probe.peek(),
                roundRunner(SWAPPED_A, SWAPPED_B, SWAPPED_C), probe);

        assertEquals(pass2, Files.readString(fileB), "sanity: the kept swap was rewritten by each compile");
        assertEquals("second", outcome.bookkeeping().merged().stream()
                        .filter(r -> r.internalName().equals(OTHER_NAME)).findFirst().orElseThrow().decompilerUsed(),
                "the manifest still says which backend produced the class");
        assertEquals(pass2, recordedSource(config, OTHER_NAME),
                "a kept swap the compile rewrote must be recorded as it now stands");
        assertEquals(pass2, mergedFor(outcome.bookkeeping(), OTHER_NAME).source());
    }

    /**
     * If the last round is reverted, the manifest left by the previous kept
     * round describes a tree that no longer exists. Reverting hands back the
     * files, but the compile that re-measures them rewrites files again, so
     * the revert branch has to re-read and rewrite the manifest too.
     *
     * <p>D is only ever a target in the second round: a fixer gives it three
     * errors during the first, which makes it fail, which is what puts it in
     * the second round's target set. The second round then wrecks it and is
     * reverted, and the manifest still has to describe D.
     */
    @Test
    void manifestMatchesDiskAfterAFinalRoundRevert(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path fileA = seedFile(config, INTERNAL_NAME, ORIGINAL_A);
        Path fileB = seedFile(config, OTHER_NAME, ORIGINAL_B);
        Path fileC = seedFile(config, THIRD_NAME, ORIGINAL_C);
        Path fileD = seedFile(config, FOURTH_NAME, ORIGINAL_D);
        List<DecompileResult> stage1Results = List.of(stage1Result(INTERNAL_NAME, ORIGINAL_A),
                stage1Result(OTHER_NAME, ORIGINAL_B), stage1Result(THIRD_NAME, ORIGINAL_C),
                stage1Result(FOURTH_NAME, ORIGINAL_D));
        String dFixerOutput = "// a fixer left D with three errors\n" + ORIGINAL_D;
        Map<String, Integer> errorsBySource = Map.of(
                ORIGINAL_A, 1, ORIGINAL_B, 20, ORIGINAL_C, 1, ORIGINAL_D, 0, dFixerOutput, 3,
                SWAPPED_A, 8, SWAPPED_B, 1, SWAPPED_C, 1, SWAPPED_D, 9);
        TreeProbe probe = new TreeProbe(config, errorsBySource)
                .rewriting(FOURTH_NAME, ORIGINAL_D, dFixerOutput, dFixerOutput, dFixerOutput);

        PipelineOrchestrator.SwapOutcome outcome = new PipelineOrchestrator().runSwapRounds(
                config, targetClasses(), stage1Results, probe.peek(),
                roundRunner(SWAPPED_A, SWAPPED_B, SWAPPED_C, SWAPPED_D), probe);

        assertEquals(4, probe.compiles(), "round 1 judged, re-measured after restoring A; round 2 judged, "
                + "re-measured after reverting it");
        assertTrue(outcome.report().remainingErrorSummaries().size() == 6, "A: 1, B: 1, C: 1, D: 3");
        for (Map.Entry<Path, String> file : Map.of(
                fileA, INTERNAL_NAME, fileB, OTHER_NAME, fileC, THIRD_NAME, fileD, FOURTH_NAME).entrySet()) {
            assertEquals(Files.readString(file.getKey()), recordedSource(config, file.getValue()),
                    file.getValue() + ": the manifest must describe the tree the reverted run left behind");
        }
    }

    /**
     * A restored file is, by construction, still a failing file -- so Stage
     * 4's own per-file revert cannot vouch for it, and a fixer that makes it
     * worse afterwards would look like the round's doing and be undone a
     * second time, at the price of a compile. Files the round has just put
     * back are therefore not re-judged by the count comparison.
     */
    @Test
    void aJustRestoredFileIsNotJudgedAgain(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path fileA = seedFile(config, INTERNAL_NAME, ORIGINAL_A);
        seedFile(config, OTHER_NAME, ORIGINAL_B);
        seedFile(config, THIRD_NAME, ORIGINAL_C);
        List<DecompileResult> stage1Results = List.of(stage1Result(INTERNAL_NAME, ORIGINAL_A),
                stage1Result(OTHER_NAME, ORIGINAL_B), stage1Result(THIRD_NAME, ORIGINAL_C));
        // A fixer rewrites A: badly before the restore, worse still after it.
        String badRewrite = "// a fixer's rewrite\n" + ORIGINAL_A;
        String worseRewrite = "// a fixer's worse rewrite\n" + ORIGINAL_A;
        Map<String, Integer> errorsBySource = Map.of(
                ORIGINAL_A, 1, ORIGINAL_B, 10, ORIGINAL_C, 1,
                SWAPPED_A, 8, SWAPPED_B, 1, SWAPPED_C, 1, badRewrite, 8, worseRewrite, 5);
        TreeProbe probe = new TreeProbe(config, errorsBySource)
                .rewriting(INTERNAL_NAME, badRewrite, worseRewrite);

        PipelineOrchestrator.SwapOutcome[] run = new PipelineOrchestrator.SwapOutcome[1];
        String log = capturingStdout(() -> run[0] = new PipelineOrchestrator().runSwapRounds(
                config, targetClasses(), stage1Results, probe.peek(),
                roundRunner(SWAPPED_A, SWAPPED_B, SWAPPED_C), probe));

        assertEquals(2, probe.compiles(),
                "one to judge, one to re-measure the restore -- A's worse rewrite must not trigger a third");
        assertEquals(1, countOccurrences(log, "the swap made worse"),
                "A is restored once, not once per compile that annoys it");
        assertEquals(worseRewrite, Files.readString(fileA),
                "the second compile's rewrite of the restored file stands");
        assertEquals(worseRewrite, mergedFor(run[0].bookkeeping(), INTERNAL_NAME).source());
    }

    /** A stub entry is bytecode-recovered text, and the flag is what points a
     *  human at the classes worth looking at first. Re-reading a file must not
     *  leave that flag attached to text the stub generator never wrote. */
    @Test
    void reRecordingASourceDoesNotLeaveAStubClaimingRealText(@TempDir Path tmp) {
        String stubSource = "// stub: signatures recovered from bytecode\n";
        SwapBookkeeping real = new SwapBookkeeping(List.of(
                new DecompileResult(INTERNAL_NAME, stubSource, "stub", true, List.of())));
        SwapBookkeeping kept = new SwapBookkeeping(List.of(
                new DecompileResult(INTERNAL_NAME, stubSource, "stub", true, List.of())));

        real.updateSource(INTERNAL_NAME, "// a real decompilation\n");
        kept.updateSource(INTERNAL_NAME, stubSource);

        assertEquals("// a real decompilation\n", mergedFor(real, INTERNAL_NAME).source());
        assertFalse(mergedFor(real, INTERNAL_NAME).isStub(),
                "text the stub generator did not write is not a stub");
        assertTrue(mergedFor(kept, INTERNAL_NAME).isStub(),
                "an unchanged stub is still a stub");
        assertEquals("stub", mergedFor(kept, INTERNAL_NAME).decompilerUsed(),
                "re-reading a source says nothing about which backend produced it");
    }

    /** Both bookkeeping repairs work by name, so a name that matches nothing
     *  means the manifest cannot be corrected -- say so rather than write a
     *  manifest that quietly disagrees with the tree. */
    @Test
    void aRestoreOrResyncWithNothingToFixIsReported(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        SwapBookkeeping bookkeeping = new SwapBookkeeping(List.of(stage1Result(INTERNAL_NAME, ORIGINAL_A)));

        String log = capturingStdout(() -> {
            PipelineOrchestrator.restoreFiles(config.decompiledSourcesDir(),
                    Map.of(), List.of(OTHER_NAME), bookkeeping, bookkeeping.snapshot());
            bookkeeping.updateSource(OTHER_NAME, "// nothing here\n");
            bookkeeping.unselect(bookkeeping.snapshot(), OTHER_NAME);
        });

        assertTrue(log.contains(OTHER_NAME), "the unfixable file is named: " + log);
        assertTrue(log.contains("not recorded in the manifest"), log);
    }

    /**
     * The gate exists to keep a file that will not compile out of the tree, so
     * it has to judge syntax at the level the real compile will use: this
     * project compiles with {@code --release 11}, and a record is perfectly
     * good Java 17. Judged at the running JDK's default level the gate would
     * wave a file through that the compile it gates then rejects.
     */
    @Test
    void theGateJudgesSyntaxAtTheProjectsReleaseLevel(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path fileA = seedFile(config, INTERNAL_NAME, MODERN_SYNTAX);
        Map<String, byte[]> backup = Map.of(INTERNAL_NAME, Files.readAllBytes(fileA));

        assertEquals(List.of(INTERNAL_NAME),
                PipelineOrchestrator.unparseableFiles(
                        config.decompiledSourcesDir(), List.of(INTERNAL_NAME), "11"),
                "a record is not valid Java 11, and the gate must say so before the real compile does");
        assertEquals(Map.of(INTERNAL_NAME, false),
                PipelineOrchestrator.backupParses(backup, List.of(INTERNAL_NAME), "11"),
                "the same has to hold for a backup judged off the tree");
        assertEquals(List.of(INTERNAL_NAME),
                PipelineOrchestrator.unparseableTree(config.decompiledSourcesDir(), "11"),
                "and for the whole-tree pass that decides whether rounds run at all");
        assertEquals(List.of(),
                PipelineOrchestrator.unparseableFiles(
                        config.decompiledSourcesDir(), List.of(INTERNAL_NAME), "17"),
                "the same file is legal at 17, so the gate must not cry wolf when the project targets 17");
    }

    /**
     * A file the fixers left unparseable silences the compiler for everything
     * else, so the count the round measures is not a measurement -- and a
     * suppressed count looks like a triumph: one error against a floor taken
     * on a healthy tree of twelve. This is the whole-tree precheck's bug
     * arriving one step later, and the numbers here are the ones that would
     * make it bite.
     *
     * <p>Judged on the predicate rather than through a round, because the round
     * cannot be walked into that state: the file is already unparseable when
     * the round reads its backup, and the only thing that can have broken it
     * is the compile the floor was taken from -- so by the time such a round
     * runs, the floor is a suppression too, and the comparison would have
     * reverted it for that reason alone. The guard is what stops that from
     * being an accident of arithmetic, and the floor is separately kept from
     * being re-seeded from such a count
     * ({@link #aFileThatDidNotParseBeforeTheRoundIsNamedAsNeedingAHand}).
     */
    @Test
    void aRoundWhoseCountIsSuppressedIsNeverKeptHoweverGoodItLooks() {
        assertFalse(PipelineOrchestrator.worthKeeping(false, 1, 12),
                "one error against a floor of twelve is suppression talking, not an improvement");
        assertTrue(PipelineOrchestrator.worthKeeping(true, 1, 12),
                "the same numbers on a tree that parses are a real improvement");
        assertFalse(PipelineOrchestrator.worthKeeping(true, 12, 12), "a tie is not an improvement");
    }

    /**
     * A tree that cannot be read cannot be judged, and a feature that cannot
     * judge should say so rather than take the whole pipeline down -- the
     * rounds are skipped, which is the same answer as "not parseable".
     */
    @Test
    void aSourceTreeThatCannotBeReadIsNamedRatherThanFatal(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        List<String>[] verdict = new List[1];
        String log = capturingStdout(() -> verdict[0] =
                PipelineOrchestrator.unparseableTree(config.decompiledSourcesDir(), "11"));

        assertFalse(verdict[0].isEmpty(), "cannot judge must read as 'not a measurement', never as 'clean'");
        assertTrue(log.contains("cannot be read"), log);

        TreeProbe probe = new TreeProbe(config, Map.of());
        CompileFixLoop.LoopReport incoming = report(List.of("/nowhere/A.java:1: boom"), List.of(INTERNAL_NAME));
        PipelineOrchestrator.SwapOutcome[] run = new PipelineOrchestrator.SwapOutcome[1];
        String runLog = capturingStdout(() -> run[0] = new PipelineOrchestrator().runSwapRounds(
                config, List.of(), List.of(), incoming,
                roundRunner(SWAPPED_A, SWAPPED_B, SWAPPED_C), probe));

        assertEquals(incoming, run[0].report(), "the report handed in is handed back untouched");
        assertEquals(0, probe.compiles());
        assertTrue(runLog.contains("swap rounds: skipped"), runLog);
    }

    /**
     * The suppression mirage the per-file gate cannot see. A Stage 4 fixer
     * breaks a file <i>during</i> the round's compile, and that file is not a
     * target of the round -- so nothing in the round's own bookkeeping knows,
     * the compiler goes silent for the whole tree, and the round is handed a
     * count of one error against a floor of eleven taken on a healthy tree.
     * That is the precheck's defect produced by a fixer instead of a swap, so
     * the post-round check has to be its mirror: any file in the tree that
     * does not parse when the round finishes makes the round's count
     * unjudgeable, whichever backend wrote it.
     */
    @Test
    void aRoundIsRevertedWhenAFixerBreaksAFileTheRoundNeverTouched(@TempDir Path tmp) throws Exception {
        PipelineConfig config = config(tmp);
        Path fileA = seedFile(config, INTERNAL_NAME, ORIGINAL_A);
        Path fileB = seedFile(config, OTHER_NAME, ORIGINAL_B);
        Path fileD = seedFile(config, FOURTH_NAME, ORIGINAL_D);
        List<DecompileResult> stage1Results = List.of(stage1Result(INTERNAL_NAME, ORIGINAL_A),
                stage1Result(OTHER_NAME, ORIGINAL_B), stage1Result(FOURTH_NAME, ORIGINAL_D));
        // D starts clean, so no round ever targets it, and "a fixer" leaves it
        // unparseable on the first compile and every compile after.
        Map<String, Integer> errorsBySource = Map.of(
                ORIGINAL_A, 1, ORIGINAL_B, 10, ORIGINAL_C, 1, ORIGINAL_D, 0,
                SWAPPED_A, 1, SWAPPED_B, 1, SWAPPED_D, 1, D_UNPARSEABLE, 1);
        TreeProbe probe = new TreeProbe(config, errorsBySource).rewriting(FOURTH_NAME, D_UNPARSEABLE);
        CompileFixLoop.LoopReport incoming = probe.peek();
        assertEquals(11, incoming.remainingErrorSummaries().size(),
                "sanity: the floor was measured on a tree that parses, with D still clean");
        assertEquals(List.of(INTERNAL_NAME, OTHER_NAME), incoming.failingFiles(),
                "sanity: D has no errors yet, so no round will target it");

        String log = capturingStdout(() -> new PipelineOrchestrator().runSwapRounds(
                config, targetClasses(), stage1Results, incoming,
                roundRunner(SWAPPED_A, SWAPPED_B, SWAPPED_C, SWAPPED_D_UNPARSEABLE), probe));

        assertTrue(log.contains("after this round's compile"), log);
        assertTrue(log.contains(FOURTH_NAME), "the file a fixer broke is named: " + log);
        assertEquals(0, countOccurrences(log, "kept:"),
                "one error against a floor of eleven is the compiler going quiet, not an improvement: " + log);
        assertEquals(2, countOccurrences(log, "reverted:"));
        assertEquals(2, countOccurrences(log, "the floor stays at 11 errors"),
                "the floor is never replaced by a count that was not measured: " + log);
        assertEquals(4, probe.compiles(), "two rounds, judged and re-measured after each wholesale revert");
        assertEquals(ORIGINAL_A, Files.readString(fileA), "A is back to its pre-round bytes");
        assertEquals(ORIGINAL_B, Files.readString(fileB), "and so is B");
        assertEquals(D_UNPARSEABLE, Files.readString(fileD),
                "the check refuses to judge the round; it does not pretend it can fix the file");
    }

    private static final String THIRD_NAME = "rs/plugins/menuswapper/ThirdSwapperPlugin";
    private static final String FOURTH_NAME = "rs/plugins/menuswapper/FourthSwapperPlugin";

    private static final List<String> ROUND_FILES =
            List.of(INTERNAL_NAME, OTHER_NAME, THIRD_NAME, FOURTH_NAME);

    private static final String ORIGINAL_A = """
            package rs.plugins.menuswapper;

            public class MenuEntrySwapperPlugin {
                public void run() { helper(1); }

                void helper(int n) { original(); }

                void original() { }
            }
            """;

    private static final String ORIGINAL_B = """
            package rs.plugins.menuswapper;

            public class OtherSwapperPlugin {
                public void run() { helper(1); }

                void helper(int n) { original(); }

                void original() { }
            }
            """;

    private static final String ORIGINAL_C = """
            package rs.plugins.menuswapper;

            public class ThirdSwapperPlugin {
                public void run() { helper(1); }

                void helper(int n) { original(); }

                void original() { }
            }
            """;

    private static final String ORIGINAL_D = """
            package rs.plugins.menuswapper;

            public class FourthSwapperPlugin {
                public void run() { helper(1); }

                void helper(int n) { original(); }

                void original() { }
            }
            """;

    private static final String SWAPPED_A = """
            package rs.plugins.menuswapper;

            public class MenuEntrySwapperPlugin {
                public void run() { helper(1); }

                void helper(int n) { swapped(); }

                void swapped() { }
            }
            """;

    private static final String SWAPPED_B = """
            package rs.plugins.menuswapper;

            public class OtherSwapperPlugin {
                public void run() { helper(1); }

                void helper(int n) { swapped(); }

                void swapped() { }
            }
            """;

    private static final String SWAPPED_C = """
            package rs.plugins.menuswapper;

            public class ThirdSwapperPlugin {
                public void run() { helper(1); }

                void helper(int n) { swapped(); }

                void swapped() { }
            }
            """;

    private static final String SWAPPED_D = """
            package rs.plugins.menuswapper;

            public class FourthSwapperPlugin {
                public void run() { helper(1); }

                void helper(int n) { swapped(); }

                void swapped() { }
            }
            """;

    /** What CFR does to a switch fall-through: a goto the language has no
     *  syntax for, so the file no longer parses. */
    private static final String SWAPPED_A_UNPARSEABLE = """
            package rs.plugins.menuswapper;

            public class MenuEntrySwapperPlugin {
                public void run() {
                    if (helper(1)) ** GOTO lbl-1000;
                }

                void helper(int n) { return; }
            }
            """;

    /** A backup that was already broken before the round started, so no
     *  backend can be blamed for -- and none should be spent. */
    private static final String ORIGINAL_A_UNPARSEABLE = """
            package rs.plugins.menuswapper;

            public class MenuEntrySwapperPlugin {
                public void run() {
                    if (helper(1)) ** GOTO lbl-2000;
                }

                void helper(int n) { return; }
            }
            """;

    /** What a fixer leaves behind when it rewrites a class it should not
     *  have touched. */
    private static final String D_UNPARSEABLE = """
            package rs.plugins.menuswapper;

            public class FourthSwapperPlugin {
                public void run() {
                    if (helper(1)) ** GOTO lbl-3000;
                }

                void helper(int n) { return; }
            }
            """;

    private static final String SWAPPED_D_UNPARSEABLE = """
            package rs.plugins.menuswapper;

            public class FourthSwapperPlugin {
                public void run() {
                    if (helper(1)) ** GOTO lbl-4000;
                }

                void helper(int n) { return; }
            }
            """;

    private static final String OTHER_UNPARSEABLE = """
            package rs.plugins.menuswapper;

            public class OtherSwapperPlugin {
                public void run() {
                    if (helper(1)) ** GOTO lbl-5000;
                }

                void helper(int n) { return; }
            }
            """;

    private static final String THIRD_UNPARSEABLE = """
            package rs.plugins.menuswapper;

            public class ThirdSwapperPlugin {
                public void run() {
                    if (helper(1)) ** GOTO lbl-6000;
                }

                void helper(int n) { return; }
            }
            """;

    /** Legal Java 17, not legal Java 11: the gate has to be told the release
     *  level or it will pass a file the real compile rejects. */
    private static final String MODERN_SYNTAX = """
            package rs.plugins.menuswapper;

            public record MenuEntrySwapperPlugin(int health, String name) {
            }
            """;

    /** The swap-round backend: a different body per class, so a test can say
     *  "this swap helps B and wrecks A". */
    private record PerFileDecompiler(String name, Map<String, String> sources) implements Decompiler {

        @Override
        public String decompile(String internalName, Function<String, byte[]> classBytesProvider) {
            return sources.get(internalName);
        }
    }

    private Stage1Runner roundRunner(String swappedA, String swappedB, String swappedC) {
        return roundRunner(swappedA, swappedB, swappedC, SWAPPED_D);
    }

    private Stage1Runner roundRunner(String swappedA, String swappedB, String swappedC, String swappedD) {
        return new Stage1Runner(List.of(
                new PerFileDecompiler("first", Map.of()),
                new PerFileDecompiler("second", Map.of(
                        INTERNAL_NAME, swappedA,
                        OTHER_NAME, swappedB,
                        THIRD_NAME, swappedC,
                        FOURTH_NAME, swappedD))
        ));
    }

    private List<ClassInfo> targetClasses() {
        return ROUND_FILES.stream().map(this::targetClass).toList();
    }

    private DecompileResult stage1Result(String internalName, String source) {
        return new DecompileResult(internalName, source, "first", false, List.of(
                new DecompileResult.AttemptLogEntry("first", DecompileResult.Outcome.SUCCESS, null),
                new DecompileResult.AttemptLogEntry("second", DecompileResult.Outcome.SUCCESS, null)));
    }

    private DecompileResult mergedFor(SwapBookkeeping bookkeeping, String internalName) {
        return bookkeeping.merged().stream()
                .filter(r -> r.internalName().equals(internalName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no merged result for " + internalName));
    }

    private CompileFixLoop.LoopReport report(List<String> summaries, List<String> failingFiles) {
        return new CompileFixLoop.LoopReport(summaries.isEmpty(), List.of(), summaries, failingFiles, 1);
    }

    /**
     * A stand-in for the compile-fix loop, reading the tree as it currently
     * stands: one error per line for whatever the on-disk source is worth, so
     * a round's verdict is driven by the files the round really left behind.
     *
     * <p>It also reproduces the compiler habit that makes counts alone an
     * unsafe verdict. A file that does not parse ({@link #isUnparseable})
     * aborts attribution, so every other file then reports nothing at all --
     * which is how a {@code ** GOTO} swap turns a tree of six errors into a
     * report of one. {@link #rewriting} makes a compile rewrite a file,
     * standing in for the fixers that mutate the tree whatever the
     * diagnostics say.
     */
    private final class TreeProbe implements PipelineOrchestrator.CompileProbe {

        private final PipelineConfig config;
        private final Map<String, Integer> errorsBySource;
        private final Map<String, List<String>> rewriting = new LinkedHashMap<>();
        private final List<String> unparseableSeen = new ArrayList<>();
        private int compiles;

        TreeProbe(PipelineConfig config, Map<String, Integer> errorsBySource) {
            this.config = config;
            this.errorsBySource = errorsBySource;
        }

        /** Makes each compile rewrite {@code internalName} with the next
         *  text, so the tree moves under the round's feet. */
        TreeProbe rewriting(String internalName, String... perPass) {
            rewriting.put(internalName, List.of(perPass));
            return this;
        }

        int compiles() {
            return compiles;
        }

        List<String> unparseableFilesSeen() {
            return List.copyOf(unparseableSeen);
        }

        void forget() {
            compiles = 0;
            unparseableSeen.clear();
        }

        @Override
        public CompileFixLoop.LoopReport compile() throws Exception {
            compiles++;
            for (var rewrite : rewriting.entrySet()) {
                List<String> perPass = rewrite.getValue();
                write(rewrite.getKey(), perPass.get(Math.min(compiles, perPass.size()) - 1));
            }
            return measure(true);
        }

        /** The report for the tree as it stands, without counting as a
         *  compile: what the pipeline hands the rounds to start from. */
        CompileFixLoop.LoopReport peek() throws Exception {
            return measure(false);
        }

        private CompileFixLoop.LoopReport measure(boolean observe) throws Exception {
            Map<String, String> onDisk = new LinkedHashMap<>();
            for (String internalName : ROUND_FILES) onDisk.put(internalName, read(internalName));
            boolean attributionStopped = onDisk.values().stream()
                    .filter(java.util.Objects::nonNull)
                    .anyMatch(TreeProbe::isUnparseable);

            List<String> summaries = new ArrayList<>();
            List<String> failing = new ArrayList<>();
            for (String internalName : ROUND_FILES) {
                String source = onDisk.get(internalName);
                if (source == null) continue;
                boolean unparseable = isUnparseable(source);
                if (unparseable && observe) unparseableSeen.add(internalName);
                int errors = unparseable ? 1
                        : attributionStopped ? 0
                        : errorsBySource.getOrDefault(source, 0);
                if (errors == 0) continue;
                failing.add(internalName);
                Path file = config.decompiledSourcesDir().resolve(internalName + ".java");
                for (int line = 1; line <= errors; line++) {
                    summaries.add(file + ":" + line + ": error in " + internalName);
                }
            }
            return report(summaries, failing);
        }

        private String read(String internalName) throws Exception {
            Path file = config.decompiledSourcesDir().resolve(internalName + ".java");
            return Files.exists(file) ? Files.readString(file) : null;
        }

        private void write(String internalName, String source) throws Exception {
            Files.writeString(config.decompiledSourcesDir().resolve(internalName + ".java"), source);
        }

        private static boolean isUnparseable(String source) {
            return source.contains("** GOTO");
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** The rounds report their decisions on stdout, and some of those reports
     *  are the only trace of a decision a test can see. */
    private String capturingStdout(ThrowingRunnable action) throws Exception {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            action.run();
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) count++;
        return count;
    }

    @SuppressWarnings("unchecked")
    private String recordedSource(PipelineConfig config, String internalName) throws Exception {        Map<String, Object> doc = new ObjectMapper().readValue(config.stage1ManifestPath().toFile(), Map.class);
        List<Map<String, Object>> results = (List<Map<String, Object>>) doc.get("results");
        assertNotNull(results);
        return (String) results.stream()
                .filter(r -> internalName.equals(r.get("internalName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no manifest entry for " + internalName))
                .get("source");
    }
}
