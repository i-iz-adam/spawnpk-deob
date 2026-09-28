package com.cleandecompile.stage1;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cleandecompile.PipelineConfig;
import com.cleandecompile.PipelineOrchestrator;
import com.cleandecompile.PipelineOrchestrator.SwapBookkeeping;
import com.cleandecompile.PipelineOrchestrator.SwapRoundState;
import com.cleandecompile.model.ClassInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * files, and must give back the backends it burned.
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

    @SuppressWarnings("unchecked")
    private String recordedSource(PipelineConfig config, String internalName) throws Exception {
        Map<String, Object> doc = new ObjectMapper().readValue(config.stage1ManifestPath().toFile(), Map.class);
        List<Map<String, Object>> results = (List<Map<String, Object>>) doc.get("results");
        assertNotNull(results);
        return (String) results.stream()
                .filter(r -> internalName.equals(r.get("internalName")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no manifest entry for " + internalName))
                .get("source");
    }
}
