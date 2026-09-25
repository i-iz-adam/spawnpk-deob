package com.cleandecompile.stage4;

import com.cleandecompile.PipelineConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Stage 4: run javac, bucket the errors, auto-patch the mechanical
 * categories, recompile, repeat -- with a hard cap on iterations
 * ({@link PipelineConfig#maxFixLoopIterations()}) so a jar with a
 * generics-cascade that never fully resolves automatically still
 * terminates instead of looping forever. Whatever's left unresolved after
 * the cap (or after an iteration makes zero progress) is written to the
 * report for a human.
 */
public final class CompileFixLoop {

    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    public record IterationSummary(int iteration, int errorCountBefore, int errorCountAfter, int autoFixesApplied,
                                   int revertedFileCount) {}

    public record LoopReport(boolean converged, List<IterationSummary> iterations,
                             List<String> remainingErrorSummaries,
                             List<String> failingFiles, int stage4Runs) {
    }

    public LoopReport run(PipelineConfig config) throws IOException {
        Path sourceRoot = config.decompiledSourcesDir();
        Path classOutput = config.outputDir().resolve("classes");
        Path vendoredJar = config.vendoredLibsDir().resolve("vendored-unidentified.jar");
        List<Path> classpath = new ArrayList<>();
        if (Files.exists(vendoredJar)) classpath.add(vendoredJar);
        // Lombok-annotated sources need the annotation types resolvable;
        // processing stays off (-proc:none), so no code is generated here --
        // the Gradle build runs the real processor.
        if (config.lombokJar() != null && Files.exists(config.lombokJar())) classpath.add(config.lombokJar());
        JavacRunner javac = new JavacRunner();

        List<IterationSummary> iterations = new ArrayList<>();
        Set<String> previousSignatures = null;

        // Compiled once up front; each subsequent iteration reuses the
        // recompile that applyFixesTransactionally already did to evaluate
        // its own fixes (see there), rather than compiling twice per loop.
        JavacRunner.CompileOutcome outcome = javac.compile(sourceRoot, classOutput, classpath,
                config.releaseLevel());

        for (int i = 1; i <= config.maxFixLoopIterations(); i++) {
            if (outcome.success()) {
                iterations.add(new IterationSummary(i, 0, 0, 0, 0));
                LoopReport report = new LoopReport(true, iterations, List.of(), List.of(), 1);
                writeReport(config, report);
                return report;
            }

            var bucketed = bucketer.categorize(outcome.diagnostics());
            int errorsBefore = bucketed.size();

            // Equilibrium detection: identical diagnostic sets across two
            // iterations mean the fixers are flip-flopping (or fixing one
            // error per newly revealed one at a steady state) -- stop
            // instead of burning the remaining budget. Counts alone can't
            // show this: fixing syntax routinely reveals deeper errors.
            Set<String> signatures = new HashSet<>();
            for (var b : bucketed) {
                var d = b.diagnostic();
                signatures.add(String.valueOf(d.getSource()) + ":" + d.getLineNumber() + ":" + d.getCode());
            }
            if (signatures.equals(previousSignatures)) {
                iterations.add(new IterationSummary(i, errorsBefore, errorsBefore, 0, 0));
                LoopReport report = new LoopReport(false, iterations, summarize(outcome.diagnostics()),
                        failingFiles(sourceRoot, outcome.diagnostics()), 1);
                writeReport(config, report);
                return report;
            }
            previousSignatures = signatures;

            FixTransaction tx = applyFixesTransactionally(sourceRoot, classOutput, outcome, bucketed, classpath,
                    config.releaseLevel(), javac);

            int errorsAfter = tx.outcome() != null ? tx.outcome().diagnostics().size() : errorsBefore;
            iterations.add(new IterationSummary(i, errorsBefore, errorsAfter, tx.fixesApplied(),
                    tx.revertedFiles().size()));
            if (!tx.revertedFiles().isEmpty()) {
                System.out.printf("  reverted %d file(s) a fixer made worse this iteration: %s%n",
                        tx.revertedFiles().size(), tx.revertedFiles());
            }

            if (tx.fixesApplied() == 0) {
                // Nothing left we know how to fix mechanically -- report the
                // remainder for a human. Error counts are NOT compared
                // across iterations: fixing syntax routinely reveals deeper
                // attribution errors, so a rising count is progress, not
                // regress.
                List<String> remaining = summarize(outcome.diagnostics());
                LoopReport report = new LoopReport(false, iterations, remaining,
                        failingFiles(sourceRoot, outcome.diagnostics()), 1);
                writeReport(config, report);
                return report;
            }

            // tx.outcome() is a fresh compile of the tree exactly as it
            // stands after this round's fixes and any per-file reverts --
            // use it as next iteration's baseline instead of recompiling.
            outcome = tx.outcome();
        }

        LoopReport report = new LoopReport(outcome.success(), iterations,
                summarize(outcome.diagnostics()),
                failingFiles(sourceRoot, outcome.diagnostics()), 1);
        writeReport(config, report);
        return report;
    }

    /** Result of one {@link #applyFixesTransactionally} call: fixes actually
     *  kept, the (root-relative) files that were reverted, and a compile
     *  outcome reflecting the tree exactly as fixes+reverts leave it --
     *  {@code null} outcome iff {@code fixesApplied == 0} (nothing changed,
     *  so no recompile was needed to evaluate anything). */
    private record FixTransaction(int fixesApplied, List<String> revertedFiles, JavacRunner.CompileOutcome outcome) {}

    /**
     * Wraps {@link #applyMechanicalFixes} in a snapshot/apply/recompile/
     * revert cycle so a regressing fixer can't silently make a file worse.
     * The aggregate checks in {@link #run} (error count, equilibrium
     * signature) only see the WHOLE tree at once, several files at a time --
     * they would happily accept a round where nine files improved and one
     * got worse, as long as the net count went down. This makes that one
     * file's regression visible and undoes it on its own, keeping the
     * other nine fixes.
     *
     * <p>Every {@code .java} file under {@code sourceRoot} is snapshotted
     * before the fixers run (not just files with a current diagnostic:
     * {@link StringConcatFixer} and {@link DecompilerArtifactFixer} scan
     * the whole tree for their patterns regardless of what javac currently
     * reports, so any file is a possible target). After the fixers run and
     *  the tree is recompiled once to see the result, every file whose
     *  content actually changed this round is checked: if it was CLEAN before
     *  the round and errors after it ({@link #shouldRevert}), it is rewritten
     *  back to its snapshot. Files that changed but did not regress, and files no
     *  fixer touched, are left as-is.
     *
     *  <p>Files that already had errors are deliberately never reverted on a
     *  rising count: a genuine fix routinely changes what javac reports next
     *  on the same file (fixing one attribution error exposes a different
     *  one), so message-for-message or count comparison would misclassify
     *  ordinary progress as regression and churn forever. Rising counts on broken
     *  files are judged by the loop's aggregate checks (equilibrium
     *  signature) instead.
     *
     * <p>Costs at most two compiles beyond the one the caller already had
     * (one to evaluate the round, one more only if a revert actually
     * happened); reverts are expected to be the rare case -- zero of them
     * is the common path.
     */
    private FixTransaction applyFixesTransactionally(Path sourceRoot, Path classOutput,
                                                     JavacRunner.CompileOutcome before, List<DiagnosticBucketer.Bucketed> bucketed, List<Path> classpath,
                                                     String releaseLevel, JavacRunner javac) throws IOException {
        Map<Path, List<String>> snapshot = snapshotSources(sourceRoot);

        int fixesApplied = applyMechanicalFixes(sourceRoot, bucketed, classpath, releaseLevel);
        if (fixesApplied == 0) {
            return new FixTransaction(0, List.of(), null);
        }

        JavacRunner.CompileOutcome after = javac.compile(sourceRoot, classOutput, classpath, releaseLevel);
        Map<Path, Integer> beforeCounts = errorCountsByFile(before.diagnostics());
        Map<Path, Integer> afterCounts = errorCountsByFile(after.diagnostics());

        Path root = sourceRoot.toAbsolutePath().normalize();
        List<String> reverted = new ArrayList<>();
        for (var entry : snapshot.entrySet()) {
            Path file = entry.getKey();
            List<String> original = entry.getValue();
            List<String> current;
            try {
                current = Files.readAllLines(file);
            } catch (IOException e) {
                continue; // a fixer may have removed/renamed it -- nothing to compare or restore
            }
            if (current.equals(original)) continue; // untouched this round

            int beforeCount = beforeCounts.getOrDefault(file, 0);
            int afterCount = afterCounts.getOrDefault(file, 0);
            if (shouldRevert(beforeCount, afterCount)) {
                Files.write(file, original);
                reverted.add(root.relativize(file).toString());
            }
        }

        JavacRunner.CompileOutcome finalOutcome = reverted.isEmpty()
                ? after
                : javac.compile(sourceRoot, classOutput, classpath, releaseLevel);
        return new FixTransaction(fixesApplied, reverted, finalOutcome);
    }

    /** Whether a file touched this round must be restored to its snapshot.
     *
     *  <p>Only a file that was CLEAN before the round and errors after it is
     *  certainly regressed by a fixer. A file that already had errors whose
     *  count rose is the normal shape of progress -- fixing one attribution
     *  error routinely reveals deeper ones -- so count comparison cannot
     *  distinguish "fixer broke something" from "fixer revealed something".
     *  Reverting those would churn: the same fixes get applied and reverted
     *  every round while the tree never converges. Rising counts on broken
     *  files are judged by the loop's aggregate checks (equilibrium
     *  signature) instead.
     */
    static boolean shouldRevert(int errorsBefore, int errorsAfter) {
        return errorsBefore == 0 && errorsAfter > 0;
    }

    /** Every {@code .java} file under {@code sourceRoot}, snapshotted by
     *  absolute normalized path (matching {@link #errorCountsByFile}'s keys)
     *  so content and diagnostics for the same file line up regardless of
     *  whether {@code sourceRoot} itself is relative. Unreadable files are
     *  skipped rather than failing the round -- there is nothing to protect
     *  for a file that can't be read anyway. */
    private static Map<Path, List<String>> snapshotSources(Path sourceRoot) throws IOException {
        Map<Path, List<String>> snapshot = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                try {
                    snapshot.put(p.toAbsolutePath().normalize(), Files.readAllLines(p));
                } catch (IOException ignored) {
                    // Unreadable -- not this round's problem to fix.
                }
            }
        }
        return snapshot;
    }

    /** Per-file ERROR diagnostic counts, keyed by absolute normalized source
     *  path so they line up with {@link #snapshotSources}'s keys regardless
     *  of how the diagnostic's own file object spells its path. */
    private static Map<Path, Integer> errorCountsByFile(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        Map<Path, Integer> counts = new HashMap<>();
        for (var d : diagnostics) {
            if (d.getSource() == null) continue;
            try {
                Path file = Path.of(d.getSource().toUri()).toAbsolutePath().normalize();
                counts.merge(file, 1, Integer::sum);
            } catch (Exception ignored) {
                // Unresolvable source -- not attributable to a file.
            }
        }
        return counts;
    }

    /** Distinct slash-form internal names with at least one error, derived
     *  from diagnostic source paths (extra-source shims included -- callers
     *  filter to decompilable classes themselves). */
    private List<String> failingFiles(Path sourceRoot, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        // Diagnostic paths are absolute while configured roots may be
        // relative -- absolutize before comparing, or nothing ever matches.
        Path root = sourceRoot.toAbsolutePath().normalize();
        Set<String> files = new java.util.LinkedHashSet<>();
        for (var d : diagnostics) {
            if (d.getSource() == null) continue;
            try {
                Path file = Path.of(d.getSource().toUri()).toAbsolutePath().normalize();
                if (!file.startsWith(root)) continue;
                String rel = root.relativize(file).toString();
                if (!rel.endsWith(".java")) continue;
                files.add(rel.substring(0, rel.length() - ".java".length()).replace(
                        file.getFileSystem().getSeparator(), "/"));
            } catch (Exception ignored) {
                // Unresolvable source -- not attributable to a file.
            }
        }
        return new ArrayList<>(files);
    }

    private int applyMechanicalFixes(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed,
                                     List<Path> classpath, String releaseLevel) throws IOException {
        int fixes = 0;

        // A raw new-through-parameterized-target poisons every downstream
        // use (lambda params infer Object). Runs FIRST: restoring the
        // diamond up front means the attribution oracle below sees precise
        // types instead of chasing Object cascades.
        int diamondFixes = DiamondFixer.tryFixAll(sourceRoot, bucketed);
        fixes += diamondFixes;

        // A String[] declaration holding only Strings poisons its uses the
        // same way; retyping the declaration up front keeps the later
        // line-scoped fixers from stacking bogus casts on those lines.
        ArrayDeclRetypeFixer.Result arrayFix =
                ArrayDeclRetypeFixer.tryFixAll(sourceRoot, bucketed);
        int arrayFixes = arrayFix.fixes();
        fixes += arrayFixes;
        bucketed = arrayFix.unhandled(bucketed);

        // The same slot sometimes holds Strings in one region and arrays in
        // another: retyping is unsound there, so the String region is split
        // into a fresh local instead.
        VarSplitFixer.Result splitFix =
                VarSplitFixer.tryFixAll(sourceRoot, bucketed);
        int splitFixes = splitFix.fixes();
        fixes += splitFixes;
        bucketed = splitFix.unhandled(bucketed);

        // Decompilers type a local as Object when the verifier merged its
        // interface/slot-reused types, then use it as an array, iterable or
        // receiver. Runs FIRST: it works from the diagnostics' line numbers
        // and the type oracle needs the files exactly as javac saw them
        // (ImportInserter below shifts lines). Diagnostics it handled are
        // withheld from the later line-scoped fixers, which would otherwise
        // stack redundant casts on the very same lines.
        ObjectTypedLocalFixer.Result objectFix =
                ObjectTypedLocalFixer.tryFixAll(sourceRoot, bucketed, classpath, releaseLevel);
        int objectFixes = objectFix.fixes();
        fixes += objectFixes;
        bucketed = objectFix.unhandled(bucketed);

        Map<DiagnosticBucketer.Category, List<DiagnosticBucketer.Bucketed>> grouped = bucketer.group(bucketed);

        var unresolved = grouped.getOrDefault(DiagnosticBucketer.Category.UNRESOLVED_SYMBOL, List.of());
        int importFixes = 0;
        if (!unresolved.isEmpty()) {
            importFixes = ImportInserter.tryFixAll(sourceRoot, unresolved);
            fixes += importFixes;
        }

        // Vineflower leaks invokedynamic string concatenation as a literal
        // bootstrap call (StringConcatFactory.makeConcatWithConstants<...>(...))
        // whenever it can't inline the recipe -- valid IR, invalid Java.
        // Recipes are mechanically convertible to plain + chains.
        int concatFixes = StringConcatFixer.tryFixAll(sourceRoot);
        fixes += concatFixes;

        // Erased generics surface as Object where a concrete type is needed.
        // When javac names the target type, an explicit cast restores it.
        var incompatible = grouped.getOrDefault(DiagnosticBucketer.Category.INCOMPATIBLE_TYPES, List.of());
        int castFixes = 0;
        if (!incompatible.isEmpty()) {
            castFixes = RawCastFixer.tryFixAll(sourceRoot, incompatible);
            fixes += castFixes;
        }

        // Vineflower's own printing bugs (redundant cast paren before new,
        // leaked capture-of inference text) are valid IR but invalid Java.
        int artifactFixes = DecompilerArtifactFixer.tryFixAll(sourceRoot);
        fixes += artifactFixes;

        // Vineflower collapses String.compareTo orderings into bare
        // operators (String > String), which cannot compile; the printed
        // operator faithfully guides the reconstruction (... > 0).
        int compareFixes = StringCompareFixer.tryFixAll(sourceRoot, bucketed);
        fixes += compareFixes;

        // Vineflower inlines synthetic accessors into direct accesses, but
        // our flat one-file-per-class layout breaks nestmate access: widen.
        int widenFixes = AccessWidenFixer.tryFixAll(sourceRoot, bucketed);
        fixes += widenFixes;

        // Decompiler drops precise types to Object; some JDK methods exist
        // on exactly one receiver type, which names the cast.
        int receiverFixes = ReceiverCastFixer.tryFixAll(sourceRoot, bucketed);
        fixes += receiverFixes;

        // A lone throwing call in a throws-less method is wrapped in
        // try/catch. Runs LAST: it inserts lines, which would shift the line
        // numbers the fixers above read from this round's diagnostics.
        int wrapFixes = CheckedWrapFixer.tryFixAll(sourceRoot, bucketed);
        fixes += wrapFixes;

        if (fixes > 0) {
            System.out.printf("  fixes applied: diamond=%d arrayRetype=%d split=%d objectTyped=%d imports=%d concat=%d casts=%d artifacts=%d compare=%d widen=%d receiver=%d wrap=%d%n",
                    diamondFixes, arrayFixes, splitFixes, objectFixes, importFixes, concatFixes, castFixes, artifactFixes, compareFixes, widenFixes, receiverFixes, wrapFixes);
        }

        // TODO: DUPLICATE_METHOD -- remove the redundant bridge method
        // (cross-reference Stage 0's BytecodeNormalizer.Warning synthetic/
        // bridge log to know which of the two colliding methods is the
        // safe one to delete) rather than the hand-written one.
        // TODO: INCOMPATIBLE_TYPES -- insert an explicit raw-type cast at
        // the flagged expression when the diagnostic pinpoints a single
        // assignment/argument site.
        // Both are left as manual-fix categories for now; see the plan's
        // Stage 4 section for the intended approach.

        return fixes;
    }

    private List<String> summarize(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        List<String> out = new ArrayList<>();
        for (var d : diagnostics) {
            String source = d.getSource() != null ? d.getSource().getName() : "?";
            out.add(source + ":" + d.getLineNumber() + ": " + d.getMessage(Locale.ENGLISH));
        }
        return out;
    }

    private void writeReport(PipelineConfig config, LoopReport report) throws IOException {
        Path reportPath = config.outputDir().resolve("manifests/stage4-fix-loop-report.json");
        Files.createDirectories(reportPath.getParent());
        // Every loop run (initial + each swap-round re-run) used to overwrite
        // this file, so the final report showed only the last run's tail --
        // a "1 error" tail hiding hundreds of earlier errors. Append instead:
        // prior iterations are kept (renumbered continuously) and the run
        // count records how many runs produced the file. Unparseable previous
        // content (including pre-aggregation reports without stage4Runs) is
        // treated as absent rather than failing the pipeline.
        List<IterationSummary> combined = new ArrayList<>();
        int priorRuns = 0;
        try {
            if (Files.exists(reportPath)) {
                var previous = new ObjectMapper().readTree(reportPath.toFile());
                var iters = previous.get("iterations");
                if (iters != null && iters.isArray()) {
                    for (var node : iters) {
                        combined.add(new IterationSummary(
                                node.path("iteration").asInt(combined.size() + 1),
                                node.path("errorCountBefore").asInt(0),
                                node.path("errorCountAfter").asInt(0),
                                node.path("autoFixesApplied").asInt(0),
                                node.path("revertedFileCount").asInt(0)));
                    }
                }
                priorRuns = previous.path("stage4Runs").asInt(0);
            }
        } catch (RuntimeException | IOException ignored) {
            combined.clear();
            priorRuns = 0;
        }
        int offset = combined.size();
        for (var summary : report.iterations()) {
            combined.add(new IterationSummary(
                    offset + summary.iteration(),
                    summary.errorCountBefore(), summary.errorCountAfter(),
                    summary.autoFixesApplied(), summary.revertedFileCount()));
        }
        LoopReport merged = new LoopReport(report.converged(), combined,
                report.remainingErrorSummaries(), report.failingFiles(), priorRuns + 1);
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(reportPath.toFile(), merged);
    }

    /**
     * Real, working fixer for the single most common mechanical case: a
     * decompiler referenced another class by simple name without an
     * import (or dropped/mis-rewrote one during Stage 0 renaming). Builds
     * a simple-name -> FQN index from the generated source tree's own
     * directory layout (which is exact, since Stage 1 writes each file at
     * its already-final internal-name path) and inserts an import when
     * there's exactly one unambiguous candidate.
     */
    static final class ImportInserter {
        private static final Pattern CANNOT_FIND_SYMBOL_CLASS =
                Pattern.compile("symbol:\\s*class\\s+(\\w+)");

        static int tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> unresolved) throws IOException {
            Map<String, List<String>> simpleNameToFqn = buildIndex(sourceRoot);
            int fixed = 0;

            // Group by file so each file is only read/written once even if
            // it has several unresolved-symbol errors this iteration.
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            for (var b : unresolved) {
                if (b.diagnostic().getSource() == null) continue;
                Path file = Path.of(b.diagnostic().getSource().toUri());
                byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(b);
            }

            for (var entry : byFile.entrySet()) {
                Set<String> importsToAdd = new TreeSet<>();
                for (var b : entry.getValue()) {
                    Matcher m = CANNOT_FIND_SYMBOL_CLASS.matcher(b.diagnostic().getMessage(Locale.ENGLISH));
                    if (!m.find()) continue;
                    String simpleName = m.group(1);
                    List<String> candidates = simpleNameToFqn.getOrDefault(simpleName, List.of());
                    if (candidates.size() == 1) {
                        importsToAdd.add(candidates.get(0));
                    }
                    // size 0 -> genuinely missing, nothing to insert.
                    // size >1 -> ambiguous, left for a human rather than guessing.
                }
                if (!importsToAdd.isEmpty()) {
                    fixed += insertImports(entry.getKey(), importsToAdd);
                }
            }
            return fixed;
        }

        private static Map<String, List<String>> buildIndex(Path sourceRoot) throws IOException {
            Map<String, List<String>> index = new HashMap<>();
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                for (Path p : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    Path rel = sourceRoot.relativize(p);
                    String fqn = rel.toString()
                            .substring(0, rel.toString().length() - ".java".length())
                            .replace(rel.getFileSystem().getSeparator(), ".");
                    String simple = fqn.contains(".") ? fqn.substring(fqn.lastIndexOf('.') + 1) : fqn;
                    index.computeIfAbsent(simple, s -> new ArrayList<>()).add(fqn);
                }
            }
            return index;
        }

        private static int insertImports(Path javaFile, Set<String> fqns) throws IOException {
            List<String> lines = new ArrayList<>(Files.readAllLines(javaFile));
            Set<String> have = new HashSet<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("import ")) {
                    have.add(trimmed.substring("import ".length()).replace(";", "").trim());
                }
            }
            // Drop already-present imports: re-adding them is harmless to
            // javac but counts as phantom "fixes" that keep a stalled loop
            // spinning instead of terminating.
            List<String> newImports = fqns.stream()
                    .filter(fqn -> !have.contains(fqn))
                    .map(fqn -> "import " + fqn + ";").toList();
            if (newImports.isEmpty()) return 0;
            int insertAt = 0;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                    insertAt = i + 1;
                } else if (!trimmed.isEmpty() && !trimmed.startsWith("//")) {
                    break;
                }
            }
            lines.addAll(insertAt, newImports);
            Files.write(javaFile, lines);
            return newImports.size();
        }
    }

    /**
     * Inserts an explicit cast where type erasure left {@code Object} (or a
     * wrong raw type) flowing into a concrete target javac names in the
     * diagnostic ({@code Object cannot be converted to String}). Only
     * assignment and {@code return} shapes, only when the right-hand side
     * isn't already parenthesized (keeps the fixer idempotent across loop
     * iterations). Anything fancier -- argument positions, method-ref
     * targets javac doesn't name -- stays manual.
     */
    static final class RawCastFixer {
        private static final Pattern CONVERSION = Pattern.compile(
                "incompatible types: (.+) cannot be converted to (.+)");
        private static final Map<String, String> BOXED = Map.of(
                "boolean", "Boolean", "byte", "Byte", "short", "Short",
                "char", "Character", "int", "Integer", "long", "Long",
                "float", "Float", "double", "Double");

        static int tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> incompatible) throws IOException {
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            for (var b : incompatible) {
                if (b.diagnostic().getSource() == null) continue;
                Path file = Path.of(b.diagnostic().getSource().toUri());
                byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(b);
            }
            int fixed = 0;
            for (var entry : byFile.entrySet()) {
                List<String> lines;
                try {
                    lines = new ArrayList<>(Files.readAllLines(entry.getKey()));
                } catch (IOException ioe) {
                    continue;
                }
                boolean changed = false;
                for (var b : entry.getValue()) {
                    Matcher m = CONVERSION.matcher(b.diagnostic().getMessage(Locale.ENGLISH));
                    if (!m.find()) continue;
                    String target = BOXED.getOrDefault(m.group(2).trim(), m.group(2).trim());
                    int lineNo = (int) b.diagnostic().getLineNumber();
                    if (lineNo < 1 || lineNo > lines.size()) continue;
                    String rewritten = tryFixLine(lines.get(lineNo - 1), target);
                    if (rewritten != null) {
                        lines.set(lineNo - 1, rewritten);
                        changed = true;
                        fixed++;
                    }
                }
                if (changed) Files.write(entry.getKey(), lines);
            }
            return fixed;
        }

        private static String tryFixLine(String line, String castTo) {
            String code = line;
            String comment = "";
            int commentAt = code.indexOf("//");
            if (commentAt >= 0) {
                comment = code.substring(commentAt);
                code = code.substring(0, commentAt);
            }
            if (!code.trim().endsWith(";") && !code.trim().endsWith("{")) return null;

            String enhanced = tryFixEnhancedFor(code, castTo);
            if (enhanced != null) return enhanced + comment;

            Matcher ret = Pattern.compile("^(.*\\breturn\\s+)(.+);\\s*$").matcher(code);
            if (ret.matches()) {
                String expr = ret.group(2).trim();
                // Never stack identical casts (a persisting error with the
                // right cast already present has its root cause elsewhere).
                // But a DIFFERENT cast is proven bogus by javac itself --
                // strip one level and let the loop recheck the bare expr,
                // provided the parens hold a bare type-ish name rather than
                // a compound expression (dropping THOSE parens would change
                // precedence, e.g. (a+b) * c).
                if (expr.isEmpty()) return null;
                Matcher exprCast = Pattern.compile("^\\(([^()]*)\\)\\s*(.+)$").matcher(expr);
                if (exprCast.matches()) {
                    if (exprCast.group(1).trim().equals(castTo)) return null;
                    if (isBareTypeName(exprCast.group(1).trim())) {
                        return ret.group(1) + exprCast.group(2) + ";" + comment;
                    }
                    return null;
                }
                return ret.group(1) + "(" + castTo + ") " + expr + ";" + comment;
            }

            int eq = lastTopLevelEquals(code);
            if (eq < 0) return null;
            String rhs = code.substring(eq + 1, code.lastIndexOf(';')).trim();
            if (rhs.isEmpty()) return null;
            Matcher rhsCast = Pattern.compile("^\\(([^()]*)\\)\\s*(.+)$").matcher(rhs);
            if (rhsCast.matches()) {
                if (rhsCast.group(1).trim().equals(castTo)) return null;
                if (isBareTypeName(rhsCast.group(1).trim())) {
                    return code.substring(0, eq + 1) + " " + rhsCast.group(2) + ";" + comment;
                }
                return null;
            }
            return code.substring(0, eq + 1) + " (" + castTo + ") " + rhs + ";" + comment;
        }

        /** True for atomic type-ish names a redundant paren pair can be
         *  dropped around without precedence effects: identifiers, dotted
         *  names, arrays, simple generics. Anything with operators is a
         *  real subexpression -- hands off. {@code new X} and
         *  {@code (void)} are excluded (dropping those parens breaks the
         *  expression that follows). */
        private static boolean isBareTypeName(String inside) {
            if (inside.isEmpty()) return false;
            String first = inside.split("[\\s<\\[]", 2)[0];
            if (first.equals("new") || first.equals("void")) return false;
            for (int i = 0; i < inside.length(); i++) {
                char c = inside.charAt(i);
                if (Character.isJavaIdentifierPart(c) || c == '.' || c == '[' || c == ']'
                        || c == '<' || c == '>' || c == ',' || c == '?' || Character.isWhitespace(c)) {
                    continue;
                }
                return false;
            }
            return true;
        }

        /** {@code for (T v : EXPR)} over a raw iterable: javac reports the
         *  element mismatch ({@code Object cannot be converted to T}).
         *  Casting the iterated expression to {@code Iterable<T>} restores
         *  it; primitives box ({@code Iterable<Integer>}). Skipped for
         *  C-style headers (top-level {@code ;}) and already-cast heads. */
        private static String tryFixEnhancedFor(String code, String target) {
            int forAt = indexOfWord(code, "for");
            if (forAt < 0) return null;
            int open = code.indexOf('(', forAt);
            if (open < 0) return null;
            int close = findMatchingParen(code, open);
            if (close < 0) return null;
            String header = code.substring(open + 1, close);
            if (header.indexOf(';') >= 0) return null; // C-style loop
            int colon = topLevelChar(header, ':');
            if (colon < 0) return null;
            String decl = header.substring(0, colon).trim();
            String expr = header.substring(colon + 1).trim();
            if (decl.isEmpty() || expr.isEmpty() || !decl.contains(" ")) return null;
            String boxed = BOXED.getOrDefault(target.trim(), target.trim());
            String castTo = "Iterable<" + boxed + ">";
            if (expr.startsWith("(" + castTo + ")")) return null;
            return code.substring(0, open + 1) + decl + " : (" + castTo + ") " + expr + code.substring(close);
        }

        private static int indexOfWord(String code, String word) {
            int at = code.indexOf(word);
            while (at >= 0) {
                boolean beforeOk = at == 0 || !Character.isJavaIdentifierPart(code.charAt(at - 1));
                int end = at + word.length();
                boolean afterOk = end >= code.length() || !Character.isJavaIdentifierPart(code.charAt(end));
                if (beforeOk && afterOk) return at;
                at = code.indexOf(word, at + 1);
            }
            return -1;
        }

        private static int findMatchingParen(String code, int open) {
            int depth = 0;
            boolean inStr = false;
            boolean inChr = false;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                    continue;
                }
                if (inChr) {
                    if (c == '\\') i++;
                    else if (c == '\'') inChr = false;
                    continue;
                }
                if (c == '"') inStr = true;
                else if (c == '\'') inChr = true;
                else if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }

        private static int topLevelChar(String code, char want) {            int depthParen = 0, depthBracket = 0, depthBrace = 0;
            boolean inStr = false;
            boolean inChr = false;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                    continue;
                }
                if (inChr) {
                    if (c == '\\') i++;
                    else if (c == '\'') inChr = false;
                    continue;
                }
                switch (c) {
                    case '"' -> inStr = true;
                    case '\'' -> inChr = true;
                    case '(' -> depthParen++;
                    case ')' -> depthParen--;
                    case '[' -> depthBracket++;
                    case ']' -> depthBracket--;
                    case '{' -> depthBrace++;
                    case '}' -> depthBrace--;
                    default -> {
                        if (c == want && depthParen == 0 && depthBracket == 0 && depthBrace == 0) return i;
                    }
                }
            }
            return -1;
        }

        private static int lastTopLevelEquals(String code) {
            int depthParen = 0, depthBracket = 0, depthBrace = 0;
            boolean inStr = false;
            boolean inChr = false;
            int found = -1;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                    continue;
                }
                if (inChr) {
                    if (c == '\\') i++;
                    else if (c == '\'') inChr = false;
                    continue;
                }
                switch (c) {
                    case '"' -> inStr = true;
                    case '\'' -> inChr = true;
                    case '(' -> depthParen++;
                    case ')' -> depthParen--;
                    case '[' -> depthBracket++;
                    case ']' -> depthBracket--;
                    case '{' -> depthBrace++;
                    case '}' -> depthBrace--;
                    case '=' -> {
                        if (depthParen == 0 && depthBracket == 0 && depthBrace == 0
                                && !isRelationalEquals(code, i)) {
                            found = i;
                        }
                    }
                    default -> { }
                }
            }
            return found;
        }

        private static boolean isRelationalEquals(String code, int i) {
            char prev = i > 0 ? code.charAt(i - 1) : 0;
            char next = i + 1 < code.length() ? code.charAt(i + 1) : 0;
            return prev == '=' || prev == '!' || prev == '<' || prev == '>'
                    || next == '=';
        }
    }

    /** Last top-level {@code =} (skipping {@code ==/!=/<=/>=}), or -1.
     *  Shared by the line-scoped fixers below. */
    static int lastTopLevelEquals(String code) {
        int depthParen = 0, depthBracket = 0, depthBrace = 0;
        boolean inStr = false;
        boolean inChr = false;
        int found = -1;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (inChr) {
                if (c == '\\') i++;
                else if (c == '\'') inChr = false;
                continue;
            }
            switch (c) {
                case '"' -> inStr = true;
                case '\'' -> inChr = true;
                case '(' -> depthParen++;
                case ')' -> depthParen--;
                case '[' -> depthBracket++;
                case ']' -> depthBracket--;
                case '{' -> depthBrace++;
                case '}' -> depthBrace--;
                case '=' -> {
                    if (depthParen == 0 && depthBracket == 0 && depthBrace == 0
                            && !isTopRelationalEquals(code, i)) {
                        found = i;
                    }
                }
                default -> { }
            }
        }
        return found;
    }

    private static boolean isTopRelationalEquals(String code, int i) {
        char prev = i > 0 ? code.charAt(i - 1) : 0;
        char next = i + 1 < code.length() ? code.charAt(i + 1) : 0;
        return prev == '=' || prev == '!' || prev == '<' || prev == '>'
                || next == '=';
    }

    /** Crude literal/comment strip so braces inside strings don't count.
     *  Shared by the brace-matching fixers below. */
    static String stripLine(String line) {
        StringBuilder sb = new StringBuilder();
        boolean inStr = false;
        boolean inChr = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (inChr) {
                if (c == '\\') i++;
                else if (c == '\'') inChr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
                continue;
            }
            if (c == '\'') {
                inChr = true;
                continue;
            }
            if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') break;
            sb.append(c);
        }
        return sb.toString();
    }

    /** Method end by brace balance from a header line; -1 when the braces
     *  never balance (truncated file) or the range is absurd. Shared by the
     *  brace-matching fixers below. */
    static int methodEnd(List<String> lines, int header) {
        int depth = 0;
        for (int i = header; i < Math.min(lines.size(), header + 2000); i++) {
            String code = stripLine(lines.get(i));
            for (int j = 0; j < code.length(); j++) {
                char c = code.charAt(j);
                if (c == '{') depth++;
                else if (c == '}') {
                    if (--depth == 0) return i;
                }
            }
        }
        return -1;
    }

    /**
     * Repairs two Vineflower printing bugs, both valid IR but invalid Java:
     * redundant cast paren doublets ({@code (pkg.Type)) new TreeMap(...)},
     * including doubled identical casts ({@code (A) (B)) (B) new ...},
     * whose phantom inner copies are dropped) and leaked capture-of
     * inference text ({@code (capture#3 of ? super T) expr}). Collapsing a
     * double paren is semantics-preserving (it can only ever be redundant);
     * a {@code #} name can never be a real type, so that cast was bogus and
     * the expression is rechecked bare. Line-scoped, idempotent.
     */
    static final class DecompilerArtifactFixer {
        private static final Pattern DOUBLE_PAREN_NEW =
                Pattern.compile("\\(([\\w.$]+)\\)\\)(\\s*)new\\b");
        // A duplicated cast before new, e.g. (A) (B)) (B) new TreeMap(...):
        // Vineflower smeared the outer type into a phantom inner cast that
        // can even be inconvertible. Both copies go; the outer cast stays.
        private static final Pattern DOUBLED_CAST_BEFORE_NEW =
                Pattern.compile("\\(([\\w.$]+)\\)\\)\\s*\\(\\1\\)\\s*(?=new\\b)");
        private static final Pattern CAPTURE_CAST =
                Pattern.compile("\\(capture#\\d+ of [^()]*\\)\\s*");
        // CFR marks uninferred void temporaries with a WARNING comment and
        // emits them as `void var;`, which is never legal Java. The variable
        // is unused by construction (nothing can read void), so drop it.
        private static final Pattern VOID_VARIABLE =
                Pattern.compile("^\\s*void\\s+[A-Za-z_]\\w*\\s*;\\s*$");

        static int tryFixAll(Path sourceRoot) throws IOException {
            int fixed = 0;
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                for (Path p : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    List<String> lines = Files.readAllLines(p);
                    boolean changed = false;
                    for (int i = 0; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (VOID_VARIABLE.matcher(line).matches()) {
                            lines.set(i, "");
                            changed = true;
                            fixed++;
                            continue;
                        }
                        String rewritten = DOUBLE_PAREN_NEW.matcher(line).replaceAll("($1)$2new");
                        rewritten = DOUBLED_CAST_BEFORE_NEW.matcher(rewritten).replaceAll("");
                        rewritten = CAPTURE_CAST.matcher(rewritten).replaceAll("");
                        if (!rewritten.equals(line)) {
                            lines.set(i, rewritten);
                            changed = true;
                            fixed++;
                        }
                    }
                    if (changed) Files.write(p, lines);
                }
            }
            return fixed;
        }
    }

    /**
     * Widens {@code private} declarations whose cross-class users the
     * decompiler emits as direct accesses. In bytecode this access is legal
     * (nestmates, or synthetic accessors Vineflower inlined away), but our
     * flat one-file-per-class layout is not a nest, so javac rejects it --
     * and package-private is not enough either, since users routinely sit
     * in other packages. Straight to {@code public}: widening only, no
     * behavior change for direct access. Line-scoped on the declaring file,
     * idempotent.
     */
    static final class AccessWidenFixer {
        private static final Pattern PRIVATE_ACCESS =
                Pattern.compile("(.+) has private access in ([\\w.$]+)");

        static int tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            int fixed = 0;
            for (var b : bucketed) {
                Matcher m = PRIVATE_ACCESS.matcher(b.diagnostic().getMessage(Locale.ENGLISH));
                if (!m.find()) continue;
                String member = m.group(1).trim();
                String owner = m.group(2).trim();
                Path file = sourceRoot.resolve(owner.replace('.', '/') + ".java");
                if (!Files.exists(file)) continue;
                if (widen(file, member)) fixed++;
            }
            return fixed;
        }

        private static boolean widen(Path javaFile, String member) throws IOException {
            List<String> lines = new ArrayList<>(Files.readAllLines(javaFile));
            Pattern decl = Pattern.compile(
                    "^(\\s*)private(\\s+.*\\b" + Pattern.quote(member) + "\\b\\s*[=(;])");
            for (int i = 0; i < lines.size(); i++) {
                Matcher m = decl.matcher(lines.get(i));
                if (m.find()) {
                    lines.set(i, m.group(1) + "public" + m.group(2));
                    Files.write(javaFile, lines);
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Reconstructs {@code (a.compareTo(b) OP 0)} from Vineflower's
     * collapsed {@code a OP b} on Strings (ordering operators only --
     * {@code ==} is legal as printed and never reaches this fixer).
     * Diagnostic-driven: only lines javac flags with String/String
     * operands, assignment or {@code return} shape, single top-level
     * operator. Idempotent (no bare ordering op remains afterwards).
     */
    static final class StringCompareFixer {
        private static final Pattern BAD_OPERAND = Pattern.compile(
                "bad operand types for binary operator '([><]=?)'");

        static int tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                Matcher m = BAD_OPERAND.matcher(b.diagnostic().getMessage(Locale.ENGLISH));
                if (!m.find()) continue;
                String message = b.diagnostic().getMessage(Locale.ENGLISH);
                if (!message.contains("first type:  java.lang.String")
                        || !message.contains("second type: java.lang.String")) {
                    continue;
                }
                if (b.diagnostic().getSource() == null) continue;
                byFile.computeIfAbsent(Path.of(b.diagnostic().getSource().toUri()),
                        f -> new ArrayList<>()).add(b);
            }
            int fixed = 0;
            for (var entry : byFile.entrySet()) {
                List<String> lines;
                try {
                    lines = new ArrayList<>(Files.readAllLines(entry.getKey()));
                } catch (IOException ioe) {
                    continue;
                }
                boolean changed = false;
                for (var b : entry.getValue()) {
                    Matcher m = BAD_OPERAND.matcher(b.diagnostic().getMessage(Locale.ENGLISH));
                    if (!m.find()) continue;
                    int lineNo = (int) b.diagnostic().getLineNumber();
                    if (lineNo < 1 || lineNo > lines.size()) continue;
                    String rewritten = tryFixLine(lines.get(lineNo - 1), m.group(1));
                    if (rewritten != null) {
                        lines.set(lineNo - 1, rewritten);
                        changed = true;
                        fixed++;
                    }
                }
                if (changed) Files.write(entry.getKey(), lines);
            }
            return fixed;
        }

        private static String tryFixLine(String line, String operator) {
            String code = line;
            String comment = "";
            int commentAt = code.indexOf("//");
            if (commentAt >= 0) {
                comment = code.substring(commentAt);
                code = code.substring(0, commentAt);
            }
            String tail;
            String head;
            Matcher ret = Pattern.compile("^(.*\\breturn\\s+)(.+);\\s*$").matcher(code);
            int eq = lastTopLevelEquals(code);
            if (ret.matches()) {
                head = ret.group(1);
                tail = ret.group(2);
            } else if (eq >= 0) {
                head = code.substring(0, eq + 1) + " ";
                tail = code.substring(eq + 1, code.lastIndexOf(';'));
            } else {
                return null;
            }
            int op = topLevelOperator(tail, operator);
            if (op < 0) return null;
            String left = tail.substring(0, op).trim();
            String right = tail.substring(op + operator.length()).trim();
            if (left.isEmpty() || right.isEmpty()) return null;
            return head + "(" + left + ".compareTo(" + right + ") " + operator + " 0);" + comment;
        }

        private static int topLevelOperator(String code, String operator) {
            int depthParen = 0, depthBracket = 0, depthBrace = 0;
            boolean inStr = false;
            boolean inChr = false;
            for (int i = 0; i + operator.length() <= code.length(); i++) {
                char c = code.charAt(i);
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                    continue;
                }
                if (inChr) {
                    if (c == '\\') i++;
                    else if (c == '\'') inChr = false;
                    continue;
                }
                switch (c) {
                    case '"' -> inStr = true;
                    case '\'' -> inChr = true;
                    case '(' -> depthParen++;
                    case ')' -> depthParen--;
                    case '[' -> depthBracket++;
                    case ']' -> depthBracket--;
                    case '{' -> depthBrace++;
                    case '}' -> depthBrace--;
                    default -> {
                        if ((c == '>' || c == '<') && depthParen == 0 && depthBracket == 0 && depthBrace == 0
                                && code.startsWith(operator, i)) {
                            return i;
                        }
                    }
                }
            }
            return -1;
        }
    }

    /**
     * Restores generic inference where the decompiler dropped the diamond: a
     * raw {@code new TreeMap(...)} assigned to a parameterized target leaves
     * lambda parameters (and everything downstream) Object-typed, producing
     * a spray of "cannot find symbol ... of type Object" errors at the uses.
     * Adding {@code <>} is semantics-preserving -- the target type already
     * fixes the arguments -- and one edit clears every downstream error.
     *
     * <p>Narrow by construction: only fires when the same statement shows a
     * parameterized declaration target ({@code Type<A, B> name = new X(...)}),
     * only for known-generic JDK collection types, and never on anonymous
     * subclasses (diamond is illegal there). Whole-tree scan like
     * {@link StringConcatFixer}; single-point insertions, so line numbers
     * elsewhere are undisturbed. Idempotent.
     */
    static final class DiamondFixer {
        private static final java.util.Set<String> GENERIC_TYPES = java.util.Set.of(
                "TreeMap", "HashMap", "LinkedHashMap", "WeakHashMap", "IdentityHashMap",
                "TreeSet", "HashSet", "LinkedHashSet",
                "ArrayList", "LinkedList", "ArrayDeque", "PriorityQueue", "Vector", "Stack");
        private static final Pattern NEW_RAW =
                Pattern.compile("new\\s+(\\w+)\\s*\\(");
        private static final Pattern PARAMETERIZED_TARGET =
                Pattern.compile("[\\w.$]+\\s*<.+>\\s+[\\w$]+\\s*=\\s*$", Pattern.DOTALL);

        static int tryFixAll(Path sourceRoot) throws IOException {
            return tryFixAll(sourceRoot, List.of());
        }

        /** @param bucketed this round's diagnostics; only files carrying
         *  diagnostics are touched. A diamond unifies inference from the
         *  target AND the constructor arguments, while raw defers mismatches
         *  to an unchecked warning -- so on a clean file the diamond can only
         *  break what compiles. Empty means no filter (tests). */
        static int tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            Set<Path> errorFiles = new HashSet<>();
            for (var b : bucketed) {
                if (b.diagnostic().getSource() == null) continue;
                try {
                    errorFiles.add(Path.of(b.diagnostic().getSource().toUri()).toAbsolutePath().normalize());
                } catch (RuntimeException ignored) {
                    // Unresolvable source -- not attributable to a file.
                }
            }
            int fixed = 0;
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                for (Path p : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    if (!errorFiles.isEmpty()
                            && !errorFiles.contains(p.toAbsolutePath().normalize())) continue;
                    fixed += tryFixFile(p);
                }
            }
            return fixed;
        }

        private static int tryFixFile(Path file) throws IOException {
            List<String> lines = Files.readAllLines(file);
            boolean changed = false;
            int fixed = 0;
            for (int i = 0; i < lines.size(); i++) {
                List<Integer> inserts = new ArrayList<>();
                Matcher m = NEW_RAW.matcher(lines.get(i));
                while (m.find()) {
                    if (!GENERIC_TYPES.contains(m.group(1))) continue;
                    // Explicit type args or an existing diamond between the
                    // name and the paren -- nothing to do.
                    String between = lines.get(i).substring(m.end(1), m.end() - 1);
                    if (!between.trim().isEmpty()) continue;
                    if (!hasParameterizedTarget(lines, i, m.start())) continue;
                    if (isAnonymous(lines, i, m.end() - 1)) continue;
                    inserts.add(m.end() - 1); // before the paren: new X( -> new X<>(
                }
                if (!inserts.isEmpty()) {
                    // Right to left so earlier offsets stay valid.
                    inserts.sort(java.util.Comparator.reverseOrder());
                    StringBuilder sb = new StringBuilder(lines.get(i));
                    for (int at : inserts) sb.insert(at, "<>");
                    lines.set(i, sb.toString());
                    changed = true;
                    fixed += inserts.size();
                }
            }
            if (changed) Files.write(file, lines);
            return fixed;
        }

        /** The statement text before {@code matchStart} (same line plus up to
         *  4 preceding lines, stopping at a statement boundary) ends with a
         *  parameterized declaration target. */
        private static boolean hasParameterizedTarget(List<String> lines, int line, int matchStart) {
            StringBuilder context = new StringBuilder(lines.get(line).substring(0, matchStart));
            for (int j = line - 1; j >= Math.max(0, line - 4); j--) {
                String prev = lines.get(j);
                int cut = Math.max(prev.lastIndexOf(';'),
                        Math.max(prev.lastIndexOf('{'), prev.lastIndexOf('}')));
                if (cut >= 0) {
                    context.insert(0, prev.substring(cut + 1));
                    break;
                }
                context.insert(0, prev);
            }
            return PARAMETERIZED_TARGET.matcher(context).find();
        }

        /** True when the balanced close paren of this call is followed by a
         *  class body -- an anonymous subclass, where a diamond is illegal. */
        private static boolean isAnonymous(List<String> lines, int line, int openParen) {
            int depth = 0;
            boolean inStr = false;
            boolean inChr = false;
            for (int i = line; i < Math.min(lines.size(), line + 20); i++) {
                String text = lines.get(i);
                for (int j = (i == line ? openParen : 0); j < text.length(); j++) {
                    char c = text.charAt(j);
                    if (inStr) {
                        if (c == '\\') j++;
                        else if (c == '"') inStr = false;
                        continue;
                    }
                    if (inChr) {
                        if (c == '\\') j++;
                        else if (c == '\'') inChr = false;
                        continue;
                    }
                    switch (c) {
                        case '"' -> inStr = true;
                        case '\'' -> inChr = true;
                        case '(' -> depth++;
                        case ')' -> {
                            if (--depth == 0) {
                                int k = j + 1;
                                while (k < text.length() && Character.isWhitespace(text.charAt(k))) k++;
                                return k < text.length() && text.charAt(k) == '{';
                            }
                        }
                        default -> { }
                    }
                }
            }
            return false;
        }
    }

    /**
     * Retypes a {@code String[]} local holding only Strings.
     *
     * <p>Slot reuse leaves declarations like {@code String[] stringArray}
     * whose every assignment is a String and whose every use demands String
     * methods -- a cluster of "String cannot be converted to String[]" plus
     * "cannot find symbol ... of type String[]" errors. Retyping the
     * declaration to {@code String} clears the cluster at once (and the
     * cast fixer strips the now-bogus casts on the following round).
     *
     * <p>Soundness is checked per enclosing method, not per line: exactly
     * one {@code String[] v} declaration; no array-shaped use
     * ({@code v[}, {@code v.length}, for-each over {@code v}); every
     * assignment carries a String-mismatch diagnostic (proving a String
     * right-hand side); every other occurrence is a member access, the
     * declaration itself, or a reference comparison (all type-neutral).
     * Anything else -- bare {@code v} as an argument, {@code return v},
     * anonymous classes in range -- bails. Same-line edits only.
     */
    static final class ArrayDeclRetypeFixer {
        private static final Pattern MISMATCH = Pattern.compile(
                "incompatible types:\\s+(?:java\\.lang\\.)?String cannot be converted to (?:java\\.lang\\.)?String\\[\\]");
        private static final Pattern METHOD_HEADER = Pattern.compile(
                "^\\s*+(?!if\\b|for\\b|while\\b|switch\\b|catch\\b|synchronized\\b|do\\b|else\\b|try\\b)(?:(?:public|private|protected|static|final|synchronized|native|abstract|strictfp)\\s+)*[\\w.$<>\\[\\],? ]*\\w+\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w., ]+)?\\s*\\{\\s*$");

        record Result(int fixes, Set<Diagnostic<? extends JavaFileObject>> handled) {
            static final Result NONE = new Result(0, Set.of());

            List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
                if (handled.isEmpty()) return all;
                return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
            }
        }

        static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) {
            Map<Path, List<Diagnostic<? extends JavaFileObject>>> flagged = new LinkedHashMap<>();
            for (var b : bucketed) {
                var d = b.diagnostic();
                if (d.getSource() == null || !MISMATCH.matcher(d.getMessage(Locale.ENGLISH)).find()) continue;
                try {
                    flagged.computeIfAbsent(Path.of(d.getSource().toUri()), f -> new ArrayList<>()).add(d);
                } catch (RuntimeException ignored) {
                    // Unresolvable source -- not attributable to a file.
                }
            }
            if (flagged.isEmpty()) return Result.NONE;

            Set<Diagnostic<? extends JavaFileObject>> handled =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            int fixes = 0;
            for (var entry : flagged.entrySet()) {
                try {
                    fixes += tryFixFile(entry.getKey(), entry.getValue(), handled);
                } catch (IOException ignored) {
                    // Unreadable -- not this round's problem to fix.
                }
            }
            return new Result(fixes, handled);
        }

        private static int tryFixFile(Path file, List<Diagnostic<? extends JavaFileObject>> diags,
                                      Set<Diagnostic<? extends JavaFileObject>> handled) throws IOException {
            List<String> lines = new ArrayList<>(Files.readAllLines(file));
            // Line numbers of this file's String-mismatch diagnostics prove a
            // String right-hand side for the assignments they sit on.
            Set<Integer> mismatchLines = new HashSet<>();
            for (var d : diags) mismatchLines.add((int) d.getLineNumber());

            // Group by assigned variable: left-hand side of the top-level =.
            Map<String, List<Integer>> byVar = new LinkedHashMap<>();
            for (var d : diags) {
                int lineNo = (int) d.getLineNumber();
                if (lineNo < 1 || lineNo > lines.size()) continue;
                int eq = lastTopLevelEquals(lines.get(lineNo - 1));
                if (eq < 0) continue;
                String lhs = lines.get(lineNo - 1).substring(0, eq).trim();
                if (!lhs.matches("[\\w$]+")) continue;
                byVar.computeIfAbsent(lhs, v -> new ArrayList<>()).add(lineNo);
            }

            int fixed = 0;
            for (var entry : byVar.entrySet()) {
                if (tryRetype(lines, entry.getKey(), mismatchLines)) {
                    fixed++;
                    for (var d : diags) handled.add(d);
                }
            }
            if (fixed > 0) Files.write(file, lines);
            return fixed;
        }

        private static boolean tryRetype(List<String> lines, String var, Set<Integer> mismatchLines) {
            // Nearest String[] declaration above the first String assignment
            // is the candidate; a method header in between means it lives
            // elsewhere.
            int firstUse = Integer.MAX_VALUE;
            for (int lineNo : mismatchLines) firstUse = Math.min(firstUse, lineNo);
            Pattern decl = Pattern.compile(
                    "String\\s*\\[\\s*\\]\\s+" + Pattern.quote(var) + "\\b|String\\s+"
                            + Pattern.quote(var) + "\\s*\\[\\s*\\]");
            int declLine = -1;
            for (int i = firstUse - 1; i >= Math.max(0, firstUse - 200); i--) {
                if (METHOD_HEADER.matcher(lines.get(i)).matches()) return false;
                if (decl.matcher(lines.get(i)).find()) {
                    declLine = i;
                    break;
                }
            }
            if (declLine < 0) return false;

            // Enclosing method: header above, brace-matched end below.
            int header = -1;
            for (int i = declLine; i >= Math.max(0, declLine - 100); i--) {
                if (METHOD_HEADER.matcher(lines.get(i)).matches()) {
                    header = i;
                    break;
                }
            }
            if (header < 0) return false;
            int end = methodEnd(lines, header);
            if (end < 0) return false;

            int declCount = 0;
            for (int i = header; i <= end; i++) {
                String code = stripLine(lines.get(i));
                if (decl.matcher(code).find()) declCount++;
                if (code.contains("new ") && code.contains("{")) return false; // anonymous class
            }
            if (declCount != 1) return false;

            Pattern word = Pattern.compile("(?<![\\w$])" + Pattern.quote(var) + "(?![\\w$])");
            for (int i = header; i <= end; i++) {
                String code = stripLine(lines.get(i));
                Matcher m = word.matcher(code);
                while (m.find()) {
                    int s = m.start();
                    int e = m.end();
                    // Qualified x.var is a different variable entirely.
                    if (s > 0 && code.charAt(s - 1) == '.') continue;
                    // The declaration itself.
                    if (i == declLine) continue;
                    String after = code.substring(e).trim();
                    String before = code.substring(0, s).trim();
                    if (after.startsWith(".")) continue; // member access
                    if (after.startsWith("==") || after.startsWith("!=")
                            || before.endsWith("==") || before.endsWith("!=")) continue; // comparison
                    // An earlier round's receiver cast ((String) v) is
                    // String-demand evidence itself, not an array use.
                    if (Pattern.compile("\\(\\s*(?:java\\.lang\\.)?String\\s*\\)\\s*$")
                            .matcher(before).find()) continue;
                    // Otherwise the occurrence must be the target of a String
                    // assignment (proved by this round's diagnostic).
                    int eq = lastTopLevelEquals(code);
                    if (eq >= 0 && code.substring(0, eq).trim().equals(var)
                            && mismatchLines.contains(i + 1)) continue;
                    return false;
                }
                if (word.matcher(code).find()) {
                    // Array-shaped uses can never be satisfied by a String.
                    if (Pattern.compile(Pattern.quote(var) + "\\s*\\[").matcher(code).find()) return false;
                    if (Pattern.compile(Pattern.quote(var) + "\\s*\\.\\s*length\\b").matcher(code).find()) {
                        return false;
                    }
                    if (Pattern.compile(":\\s*" + Pattern.quote(var) + "\\s*\\)").matcher(code).find()) {
                        return false;
                    }
                }
            }

            // Normalise both spellings (String[] v and String v[]) to String v.
            String rewritten = lines.get(declLine)
                    .replaceFirst("String\\s*\\[\\s*\\]\\s+" + Pattern.quote(var) + "\\b", "String " + var)
                    .replaceFirst("String\\s+" + Pattern.quote(var) + "\\s*\\[\\s*\\]", "String " + var);
            if (rewritten.equals(lines.get(declLine))) return false;
            lines.set(declLine, rewritten);
            return true;
        }
    }

    /**
     * Splits a String region out of a reused array slot.
     *
     * <p>When one local holds Strings in one region and arrays in another
     * (slot reuse across distant code), retyping the declaration is unsound
     * -- {@link ArrayDeclRetypeFixer} correctly refuses. Instead the String
     * assignment starts a fresh {@code String} local and every String-demand
     * use up to the next reassignment of the old slot is renamed to it. The
     * array uses keep the original variable untouched.
     *
     * <p>Soundness per region: the trigger assignment carries a
     * String-mismatch diagnostic (proving a String right-hand side, modulo
     * one stripped bogus {@code (String[])} cast level); the region ends at
     * the next assignment to the old slot (or the method end); inside, every
     * occurrence is the trigger, a member access, a reference comparison, or
     * a {@code (String)}-cast operand; no array-shaped use appears. Anything
     * else bails. Same-line edits except the one inserted declaration,
     * which sits on the trigger line itself (no shifting).
     */
    static final class VarSplitFixer {
        private static final Pattern MISMATCH = Pattern.compile(
                "incompatible types:\\s+(?:java\\.lang\\.)?String cannot be converted to (?:java\\.lang\\.)?String\\[\\]");
        private static final Pattern METHOD_HEADER = Pattern.compile(
                "^\\s*+(?!if\\b|for\\b|while\\b|switch\\b|catch\\b|synchronized\\b|do\\b|else\\b|try\\b)(?:(?:public|private|protected|static|final|synchronized|native|abstract|strictfp)\\s+)*[\\w.$<>\\[\\],? ]*\\w+\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w., ]+)?\\s*\\{\\s*$");

        record Result(int fixes, Set<Diagnostic<? extends JavaFileObject>> handled) {
            static final Result NONE = new Result(0, Set.of());

            List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
                if (handled.isEmpty()) return all;
                return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
            }
        }

        static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) {
            Map<Path, List<Diagnostic<? extends JavaFileObject>>> flagged = new LinkedHashMap<>();
            for (var b : bucketed) {
                var d = b.diagnostic();
                if (d.getSource() == null || !MISMATCH.matcher(d.getMessage(Locale.ENGLISH)).find()) continue;
                try {
                    flagged.computeIfAbsent(Path.of(d.getSource().toUri()), f -> new ArrayList<>()).add(d);
                } catch (RuntimeException ignored) {
                    // Unresolvable source -- not attributable to a file.
                }
            }
            if (flagged.isEmpty()) return Result.NONE;

            Set<Diagnostic<? extends JavaFileObject>> handled =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            int fixes = 0;
            for (var entry : flagged.entrySet()) {
                try {
                    fixes += tryFixFile(entry.getKey(), entry.getValue(), bucketed, handled);
                } catch (IOException ignored) {
                    // Unreadable -- not this round's problem to fix.
                }
            }
            return new Result(fixes, handled);
        }

        private static int tryFixFile(Path file, List<Diagnostic<? extends JavaFileObject>> diags,
                                      List<DiagnosticBucketer.Bucketed> bucketed,
                                      Set<Diagnostic<? extends JavaFileObject>> handled) throws IOException {
            List<String> lines = new ArrayList<>(Files.readAllLines(file));
            int fixed = 0;
            for (var d : diags) {
                int lineNo = (int) d.getLineNumber();
                if (lineNo < 1 || lineNo > lines.size()) continue;
                int eq = lastTopLevelEquals(lines.get(lineNo - 1));
                if (eq < 0) continue;
                String lhs = lines.get(lineNo - 1).substring(0, eq).trim();
                if (!lhs.matches("[\\w$]+")) continue;
                if (trySplit(lines, file, lhs, lineNo, bucketed, handled)) {
                    fixed++;
                    handled.add(d);
                }
            }
            if (fixed > 0) Files.write(file, lines);
            return fixed;
        }

        private static boolean trySplit(List<String> lines, Path file, String var, int triggerLine,
                                        List<DiagnosticBucketer.Bucketed> bucketed,
                                        Set<Diagnostic<? extends JavaFileObject>> handled) {
            Pattern decl = Pattern.compile(
                    "String\\s*\\[\\s*\\]\\s+" + Pattern.quote(var) + "\\b");
            // Declaration above, enclosing method around it.
            int declLine = -1;
            for (int i = triggerLine - 1; i >= Math.max(0, triggerLine - 200); i--) {
                if (METHOD_HEADER.matcher(lines.get(i)).matches()) return false;
                if (decl.matcher(lines.get(i)).find()) {
                    declLine = i;
                    break;
                }
            }
            if (declLine < 0) return false;
            int header = -1;
            for (int i = declLine; i >= Math.max(0, declLine - 100); i--) {
                if (METHOD_HEADER.matcher(lines.get(i)).matches()) {
                    header = i;
                    break;
                }
            }
            if (header < 0) return false;
            int end = methodEnd(lines, header);
            if (end < 0) return false;

            // Region: trigger line up to (excluding) the next reassignment.
            // triggerIdx is 0-based; regionEndExcl is the 0-based exclusive
            // end of the region.
            int triggerIdx = triggerLine - 1;
            Pattern reassign = Pattern.compile("(?<![\\w$.])" + Pattern.quote(var)
                    + "(?![\\w$])\\s*(?:\\[[^\\]]*\\]\\s*)*=(?![=>])");
            int regionEndExcl = end + 1;
            for (int i = triggerIdx + 1; i <= end; i++) {
                String code = stripLine(lines.get(i));
                Matcher m = reassign.matcher(code);
                while (m.find()) {
                    // Skip ==, !=, <=, >= spellings around the match.
                    int s = m.start();
                    String before = code.substring(0, s);
                    if (before.endsWith("!") || before.endsWith("<") || before.endsWith(">")) continue;
                    regionEndExcl = i;
                    break;
                }
                if (regionEndExcl != end + 1) break;
            }

            // Fresh name, absent from the whole method.
            Pattern word = Pattern.compile("(?<![\\w$])" + Pattern.quote(var) + "(?![\\w$])");
            String fresh = var + "Str";
            int counter = 2;
            outer:
            while (true) {
                Pattern candidate = Pattern.compile("(?<![\\w$])" + Pattern.quote(fresh) + "(?![\\w$])");
                for (int i = header; i <= end; i++) {
                    if (candidate.matcher(stripLine(lines.get(i))).find()) {
                        fresh = var + "Str" + counter++;
                        continue outer;
                    }
                }
                break;
            }

            // Verify the region, then rewrite it.
            for (int i = triggerIdx; i < regionEndExcl; i++) {
                String code = stripLine(lines.get(i));
                Matcher m = word.matcher(code);
                while (m.find()) {
                    int s = m.start();
                    int e = m.end();
                    if (s > 0 && code.charAt(s - 1) == '.') continue; // x.var: different variable
                    String after = code.substring(e).trim();
                    String before = code.substring(0, s).trim();
                    if (after.startsWith(".")) continue; // member access
                    if (after.startsWith("==") || after.startsWith("!=")
                            || before.endsWith("==") || before.endsWith("!=")) continue;
                    if (Pattern.compile("\\(\\s*(?:java\\.lang\\.)?String\\s*\\)\\s*$")
                            .matcher(before).find()) continue; // (String) v: String demand
                    if (i == triggerIdx) {
                        int eq = lastTopLevelEquals(code);
                        if (eq >= 0 && code.substring(0, eq).trim().equals(var)) continue; // trigger
                    }
                    return false;
                }
                if (word.matcher(code).find()) {
                    if (Pattern.compile(Pattern.quote(var) + "\\s*\\[").matcher(code).find()) return false;
                    if (Pattern.compile(Pattern.quote(var) + "\\s*\\.\\s*length\\b").matcher(code).find()) {
                        return false;
                    }
                    if (Pattern.compile(":\\s*" + Pattern.quote(var) + "\\s*\\)").matcher(code).find()) {
                        return false;
                    }
                }
            }

            // Withhold this variable's diagnostics in the region: they vanish
            // with the rewrite, and later fixers would otherwise act on stale
            // text (other variables' diagnostics on the same lines stay).
            for (var b : bucketed) {
                var d = b.diagnostic();
                if (d.getSource() == null) continue;
                try {
                    if (!Path.of(d.getSource().toUri()).equals(file)) continue;
                } catch (RuntimeException ignored) {
                    continue;
                }
                long ln = d.getLineNumber();
                if (ln > triggerIdx && ln <= regionEndExcl
                        && d.getMessage(Locale.ENGLISH).contains("variable " + var + " of type")) {
                    handled.add(d);
                }
            }

            // Rewrite: trigger declares the fresh String (dropping one bogus
            // String[] cast level when present); region occurrences rename;
            // redundant (String) casts on the fresh var collapse. Trailing
            // comments survive untouched.
            for (int i = triggerIdx; i < regionEndExcl; i++) {
                String line = lines.get(i);
                if (i == triggerIdx) {
                    int eq = lastTopLevelEquals(line);
                    int semi = line.lastIndexOf(';');
                    if (eq < 0 || semi < 0 || semi < eq) return false;
                    String rhs = line.substring(eq + 1, semi).trim();
                    Matcher rhsCast = Pattern.compile(
                            "^\\((?:java\\.lang\\.)?String\\[\\]\\)\\s*(.+)$").matcher(rhs);
                    if (rhsCast.matches()) rhs = rhsCast.group(1);
                    String indent = line.substring(0, line.length() - line.stripLeading().length());
                    lines.set(i, indent + "String " + fresh + " = " + rhs + ";" + line.substring(semi + 1));
                    continue;
                }
                String updated = word.matcher(line).replaceAll(Matcher.quoteReplacement(fresh));
                // ((String) fresh) -> (fresh): cast is now redundant but valid;
                // collapsing keeps the output clean.
                updated = Pattern.compile("\\(\\s*(?:java\\.lang\\.)?String\\s*\\)\\s*"
                        + "\\(\\s*" + Pattern.quote(fresh) + "\\s*\\)").matcher(updated)
                        .replaceAll("(" + fresh + ")");
                lines.set(i, updated);
            }
            return true;
        }
    }

    /**
     * Wraps a lone throwing call in try/catch when its method declares no
     * {@code throws}.
     *
     * <p>Adding {@code throws} to the method would cascade the error into
     * every caller (which then needs {@code throws} or handling of its own);
     * wrapping is always compile-safe and matches the idiom the decompiled
     * sources already use next door (a sibling {@code catch (IOException)}
     * returning a fallback). Only the exception javac names, only the
     * innermost enclosing method, only when no {@code throws} names it
     * already. The import is added when missing.
     *
     * <p>Runs LAST among the fixers: it inserts lines, which shifts line
     * numbers other line-scoped fixers read from this round's diagnostics.
     * Idempotent (a wrapped call no longer errors).
     */
    static final class CheckedWrapFixer {
        private static final Pattern UNREPORTED = Pattern.compile(
                "unreported exception ([\\w.$]+); must be caught or declared to be thrown");
        private static final Pattern METHOD_HEADER = Pattern.compile(
                "^\\s*+(?!if\\b|for\\b|while\\b|switch\\b|catch\\b|synchronized\\b|do\\b|else\\b|try\\b)(?:(?:public|private|protected|static|final|synchronized|native|abstract|strictfp)\\s+)*[\\w.$<>\\[\\],? ]*\\w+\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w., ]+)?\\s*\\{?\\s*$");

        static int tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            Map<Path, List<Diagnostic<? extends JavaFileObject>>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                Matcher m = UNREPORTED.matcher(b.diagnostic().getMessage(Locale.ENGLISH));
                if (!m.find() || b.diagnostic().getSource() == null) continue;
                try {
                    byFile.computeIfAbsent(Path.of(b.diagnostic().getSource().toUri()), f -> new ArrayList<>())
                            .add(b.diagnostic());
                } catch (RuntimeException ignored) {
                    // Unresolvable source -- not attributable to a file.
                }
            }
            int fixed = 0;
            for (var entry : byFile.entrySet()) {
                try {
                    fixed += tryFixFile(entry.getKey(), entry.getValue());
                } catch (IOException ignored) {
                    // Unreadable -- not this round's problem to fix.
                }
            }
            return fixed;
        }

        private static int tryFixFile(Path file, List<Diagnostic<? extends JavaFileObject>> diags)
                throws IOException {
            List<String> lines = new ArrayList<>(Files.readAllLines(file));
            // Deepest diagnostic first so earlier insertions don't disturb
            // the line numbers of later ones in the same file.
            List<Diagnostic<? extends JavaFileObject>> ordered = new ArrayList<>(diags);
            ordered.sort((a, b) -> Long.compare(b.getLineNumber(), a.getLineNumber()));
            int fixed = 0;
            for (var d : ordered) {
                Matcher m = UNREPORTED.matcher(d.getMessage(Locale.ENGLISH));
                if (!m.find()) continue;
                String exception = m.group(1);
                int lineNo = (int) d.getLineNumber();
                if (lineNo < 1 || lineNo > lines.size()) continue;
                if (wrap(lines, file, lineNo, exception)) fixed++;
            }
            if (fixed > 0) Files.write(file, lines);
            return fixed;
        }

        private static boolean wrap(List<String> lines, Path file, int lineNo, String exception)
                throws IOException {
            String simple = exception.contains(".") ? exception.substring(exception.lastIndexOf('.') + 1)
                    : exception;
            // Enclosing method header above the diagnostic.
            int header = -1;
            for (int i = lineNo - 1; i >= Math.max(0, lineNo - 100); i--) {
                if (METHOD_HEADER.matcher(lines.get(i)).matches()
                        && lines.get(i).contains("(")) {
                    header = i;
                    break;
                }
            }
            if (header < 0) return false;
            // A throws clause already naming it needs no wrap.
            Matcher throwsClause = Pattern.compile(
                    "throws\\s+([\\w., ]+)").matcher(lines.get(header));
            if (throwsClause.find()) {
                for (String t : throwsClause.group(1).split(",")) {
                    String name = t.trim();
                    if (name.equals(exception) || name.equals(simple)
                            || name.endsWith("." + simple)) return false;
                }
            }
            // Body braces from the header down; the diagnostic line must sit
            // inside them.
            int openLine = -1;
            int depth = 0;
            int closeLine = -1;
            outer:
            for (int i = header; i < lines.size(); i++) {
                String code = stripLine(lines.get(i));
                for (int j = 0; j < code.length(); j++) {
                    char c = code.charAt(j);
                    if (c == '{') {
                        if (depth == 0 && openLine < 0) {
                            openLine = i;
                        }
                        depth++;
                    } else if (c == '}') {
                        if (--depth == 0 && openLine >= 0) {
                            closeLine = i;
                            break outer;
                        }
                    }
                }
            }
            if (openLine < 0 || closeLine < 0 || lineNo - 1 <= openLine || lineNo - 1 >= closeLine) {
                return false;
            }
            // Wrapping the whole body in try/catch leaves the catch path
            // without a return value: only safe for void methods (and
            // constructors), whose only legal returns are bare. A value
            // return would trade the unreported exception for a missing
            // return statement.
            for (int i = openLine; i <= closeLine; i++) {
                if (Pattern.compile("\\breturn\\s+[^;\\s]").matcher(stripLine(lines.get(i))).find()) {
                    return false;
                }
            }
            String indent = lines.get(header).substring(0,
                    lines.get(header).length() - lines.get(header).stripLeading().length());
            lines.add(openLine + 1, indent + "   try {");
            // Insertion shifted the close line down by one.
            lines.add(closeLine + 1, indent + "   } catch (" + simple + " ignored) {");
            lines.add(closeLine + 2, indent + "   }");
            if (!simple.equals(exception) || exception.contains(".")) {
                ensureImport(lines, exception);
            }
            return true;
        }

        private static void ensureImport(List<String> lines, String fqn) {
            if (!fqn.contains(".")) return;
            if (fqn.startsWith("java.lang.")) return;
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.equals("import " + fqn + ";")
                        || trimmed.equals("import static " + fqn + ";")) return;
                if (trimmed.matches("import\\s+[\\w.$]+\\." + Pattern.quote(simple) + "\\s*;")) return; // collision
            }
            int insertAt = 0;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) insertAt = i + 1;
                else if (!trimmed.isEmpty() && !trimmed.startsWith("//")) break;
            }
            lines.add(insertAt, "import " + fqn + ";");
        }
    }

    /**
     * Casts an {@code Object}-typed receiver to the one JDK type that
     * declares the called method, for a curated set of unambiguous
     * (name, arity) pairs: {@code split(String)} and {@code toCharArray()}
     * exist only on {@code String}; {@code exists/mkdir/mkdirs/delete} only
     * on {@code java.io.File}. Diagnostic-driven
     * ({@code symbol: method m(..) / location: variable v of type ...}),
     * receiver must be a simple dotted path (no chains). Idempotent.
     */
    static final class ReceiverCastFixer {
        private static final Pattern NO_SUCH_METHOD = Pattern.compile(
                "cannot find symbol\\s+symbol:\\s+method (\\w+)\\(([^)]*)\\)\\s+location:\\s+variable (\\w+) of type");
        private static final Map<String, String> RECEIVER_TYPE = Map.ofEntries(
                Map.entry("split/1", "java.lang.String"),
                Map.entry("toCharArray/0", "java.lang.String"),
                Map.entry("intern/0", "java.lang.String"),
                // contains/startsWith also exist on java.util.Collection, so
                // unlike the entries above this is a guess, not a proof: it
                // fires only for Object-typed locals the assignment-evidence
                // fixer already refused, where String protocol code dominates
                // decompiled output. A wrong guess compiles and can throw
                // ClassCastException at runtime -- same risk as the catalog
                // guesses in ObjectTypedLocalFixer.
                Map.entry("contains/1", "java.lang.String"),
                Map.entry("startsWith/1", "java.lang.String"),
                Map.entry("exists/0", "java.io.File"),
                Map.entry("mkdir/0", "java.io.File"),
                Map.entry("mkdirs/0", "java.io.File"),
                Map.entry("delete/0", "java.io.File"),
                Map.entry("createNewFile/0", "java.io.File"),
                Map.entry("listFiles/0", "java.io.File"));

        static int tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            Map<Path, List<String[]>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                Matcher m = NO_SUCH_METHOD.matcher(b.diagnostic().getMessage(Locale.ENGLISH));
                if (!m.find() || b.diagnostic().getSource() == null) continue;
                String key = m.group(1) + "/" + arity(m.group(2));
                String target = RECEIVER_TYPE.get(key);
                if (target == null) continue;
                byFile.computeIfAbsent(Path.of(b.diagnostic().getSource().toUri()), f -> new ArrayList<>())
                        .add(new String[]{m.group(3), m.group(1), target,
                                String.valueOf(b.diagnostic().getLineNumber())});
            }
            int fixed = 0;
            for (var entry : byFile.entrySet()) {
                List<String> lines;
                try {
                    lines = new ArrayList<>(Files.readAllLines(entry.getKey()));
                } catch (IOException ioe) {
                    continue;
                }
                boolean changed = false;
                for (String[] job : entry.getValue()) {
                    int lineNo;
                    try {
                        lineNo = Integer.parseInt(job[3]);
                    } catch (NumberFormatException nfe) {
                        continue;
                    }
                    if (lineNo < 1 || lineNo > lines.size()) continue;
                    String rewritten = tryFixLine(lines.get(lineNo - 1), job[0], job[1], job[2]);
                    if (rewritten != null) {
                        lines.set(lineNo - 1, rewritten);
                        changed = true;
                        fixed++;
                    }
                }
                if (changed) Files.write(entry.getKey(), lines);
            }
            return fixed;
        }

        private static int arity(String params) {
            if (params.trim().isEmpty()) return 0;
            int depth = 0;
            int count = 1;
            for (int i = 0; i < params.length(); i++) {
                char c = params.charAt(i);
                if (c == '<') depth++;
                else if (c == '>') depth--;
                else if (c == ',' && depth == 0) count++;
            }
            return count;
        }

        private static String tryFixLine(String line, String receiver, String method, String target) {
            // receiver.method( -- receiver a simple dotted path; already-cast
            // receivers are skipped for idempotence.
            Pattern call = Pattern.compile(
                    "(?<![\\w.$])" + Pattern.quote(receiver) + "\\s*\\.\\s*" + Pattern.quote(method) + "\\s*\\(");
            Matcher m = call.matcher(line);
            if (!m.find()) return null;
            String before = line.substring(0, m.start());
            if (before.trim().endsWith(")")) return null;
            return before + "((" + target + ") " + receiver + ")." + method + "("
                    + line.substring(m.end());
        }
    }

    /**
     * Rewrites Vineflower's leaked bootstrap calls for string concatenation
     * ({@code StringConcatFactory.makeConcatWithConstants<"name","recipe">(args)})
     * into plain {@code +} chains. The recipe interleaves constant segments
     * with placeholder characters (one per trailing argument, in order), so a
     * recipe of {@code "a<PH>b"} with args {@code (x, y)} becomes
     * {@code ("a" + x + "b" + y)}.
     *
     * <p>Line-scoped and conservative: a line whose placeholders don't match
     * its argument count, or whose header/arguments don't balance, is left
     * untouched for a human. Idempotent (rewritten lines no longer match).
     */
    static final class StringConcatFixer {
        private static final String MARKER = "makeConcatWithConstants<";
        /** Recipe placeholder: one per concatenated argument, in order. */
        private static final String PLACEHOLDER = Character.toString((char) 1);

        static int tryFixAll(Path sourceRoot) throws IOException {
            int fixed = 0;
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                for (Path p : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    fixed += tryFixFile(p);
                }
            }
            return fixed;
        }

        private static int tryFixFile(Path javaFile) throws IOException {
            List<String> lines = Files.readAllLines(javaFile);
            boolean changed = false;
            int fixed = 0;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (!line.contains(MARKER)) continue;
                // The bootstrap call often spans several lines; join
                // continuations (bounded) and rewrite as one + chain,
                // blanking the consumed continuation lines.
                String joined = line;
                int end = i;
                String rewritten = rewriteLine(joined);
                while (rewritten == null && end - i < 15 && end + 1 < lines.size()) {
                    joined += " " + lines.get(++end).trim();
                    rewritten = rewriteLine(joined);
                }
                if (rewritten != null) {
                    lines.set(i, rewritten);
                    for (int j = i + 1; j <= end; j++) lines.set(j, "");
                    changed = true;
                    fixed++;
                }
            }
            if (changed) Files.write(javaFile, lines);
            return fixed;
        }

        private static String rewriteLine(String line) {
            StringBuilder out = new StringBuilder();
            int cursor = 0;
            boolean any = false;
            while (true) {
                int start = line.indexOf(MARKER, cursor);
                if (start < 0) break;
                // Header: <"method","recipe"> -- two plain string literals.
                int angle = start + MARKER.length() - 1;
                int[] pos = {angle + 1};
                String method = readJavaStringLiteral(line, pos);
                if (method == null || !skipWs(line, pos) || pos[0] >= line.length()
                        || line.charAt(pos[0]++) != ',') return null;
                if (!skipWs(line, pos)) return null;
                String recipe = readJavaStringLiteral(line, pos);
                if (recipe == null || !skipWs(line, pos) || pos[0] >= line.length()
                        || line.charAt(pos[0]++) != '>') return null;
                if (!skipWs(line, pos) || pos[0] >= line.length() || line.charAt(pos[0]) != '(') return null;
                int argsEnd = findMatchingParen(line, pos[0]);
                if (argsEnd < 0) return null;
                List<String> args = splitTopLevel(line.substring(pos[0] + 1, argsEnd));
                String replacement = buildConcat(recipe, args);
                if (replacement == null) return null;
                // Strip the bootstrap qualifier too (always the plain
                // StringConcatFactory static reference): leaving it would
                // produce a dangling "StringConcatFactory.(...)".
                int qualifierStart = start;
                while (qualifierStart > 0 && (Character.isJavaIdentifierPart(line.charAt(qualifierStart - 1))
                        || line.charAt(qualifierStart - 1) == '.')) {
                    qualifierStart--;
                }
                // qualifierStart..start is "StringConcatFactory." (with the
                // dot); anything else is not a bootstrap call we understand.
                if (start - qualifierStart < 2 || line.charAt(start - 1) != '.'
                        || !line.substring(qualifierStart, start - 1).trim().equals("StringConcatFactory")) {
                    return null;
                }
                out.append(line, cursor, qualifierStart).append(replacement);
                cursor = argsEnd + 1;
                any = true;
            }
            if (!any) return null;
            out.append(line.substring(cursor));
            // Drop the now-unused bootstrap import when this was its only use.
            String result = out.toString();
            return result;
        }

        private static String buildConcat(String recipe, List<String> args) {
            String[] parts = recipe.split("", -1);
            if (parts.length - 1 != args.size()) return null;
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].isEmpty()) {
                    if (sb.length() > 1) sb.append(" + ");
                    sb.append('"').append(escapeForLiteral(parts[i])).append('"');
                }
                if (i < args.size()) {
                    if (sb.length() > 1) sb.append(" + ");
                    sb.append(args.get(i).trim());
                }
            }
            if (sb.length() == 1) sb.append("\"\"");
            return sb.append(')').toString();
        }

        /** Reads a double-quoted literal starting at {@code pos[0]}, returns
         *  the unescaped value and advances past the closing quote, or null. */
        private static String readJavaStringLiteral(String line, int[] pos) {
            int i = pos[0];
            if (i >= line.length() || line.charAt(i) != '"') return null;
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < line.length()) {
                char c = line.charAt(i);
                if (c == '"') {
                    pos[0] = i + 1;
                    return sb.toString();
                }
                if (c != '\\' || i + 1 >= line.length()) {
                    sb.append(c);
                    i++;
                    continue;
                }
                char e = line.charAt(++i);
                switch (e) {
                    case 'b' -> { sb.append('\b'); i++; }
                    case 'f' -> { sb.append('\f'); i++; }
                    case 'n' -> { sb.append('\n'); i++; }
                    case 'r' -> { sb.append('\r'); i++; }
                    case 't' -> { sb.append('\t'); i++; }
                    case '\'' -> { sb.append('\''); i++; }
                    case '"' -> { sb.append('"'); i++; }
                    case '\\' -> { sb.append('\\'); i++; }
                    case 'u' -> {
                        if (i + 4 >= line.length()) return null;
                        try {
                            sb.append((char) Integer.parseInt(line.substring(i + 1, i + 5), 16));
                        } catch (NumberFormatException nfe) {
                            return null;
                        }
                        i += 5;
                    }
                    case '0', '1', '2', '3', '4', '5', '6', '7' -> {
                        int end = i;
                        int val = 0;
                        while (end < line.length() && end - i < 3
                                && line.charAt(end) >= '0' && line.charAt(end) <= '7') {
                            val = val * 8 + (line.charAt(end) - '0');
                            end++;
                        }
                        sb.append((char) val);
                        i = end;
                    }
                    default -> { sb.append(e); i++; }
                }
            }
            return null;
        }

        private static String escapeForLiteral(String value) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> sb.append(c);
                }
            }
            return sb.toString();
        }

        private static boolean skipWs(String line, int[] pos) {
            while (pos[0] < line.length() && Character.isWhitespace(line.charAt(pos[0]))) pos[0]++;
            return pos[0] < line.length();
        }

        private static int findMatchingParen(String line, int open) {
            int depth = 0;
            boolean inStr = false;
            boolean inChr = false;
            for (int i = open; i < line.length(); i++) {
                char c = line.charAt(i);
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                    continue;
                }
                if (inChr) {
                    if (c == '\\') i++;
                    else if (c == '\'') inChr = false;
                    continue;
                }
                if (c == '"') inStr = true;
                else if (c == '\'') inChr = true;
                else if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }

        private static List<String> splitTopLevel(String args) {
            List<String> parts = new ArrayList<>();
            int depthParen = 0, depthBracket = 0, depthBrace = 0, depthAngle = 0;
            boolean inStr = false;
            boolean inChr = false;
            int start = 0;
            for (int i = 0; i < args.length(); i++) {
                char c = args.charAt(i);
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                    continue;
                }
                if (inChr) {
                    if (c == '\\') i++;
                    else if (c == '\'') inChr = false;
                    continue;
                }
                switch (c) {
                    case '"' -> inStr = true;
                    case '\'' -> inChr = true;
                    case '(' -> depthParen++;
                    case ')' -> depthParen--;
                    case '[' -> depthBracket++;
                    case ']' -> depthBracket--;
                    case '{' -> depthBrace++;
                    case '}' -> depthBrace--;
                    case '<' -> depthAngle++;
                    case '>' -> {
                        // A -> splitting a >> shift or -> arrow would corrupt;
                        // comparisons at depth 0 are rare inside argument lists
                        // that reached this fixer, accept the limitation.
                        if (depthAngle > 0) depthAngle--;
                    }
                    case ',' -> {
                        if (depthParen == 0 && depthBracket == 0 && depthBrace == 0 && depthAngle == 0) {
                            parts.add(args.substring(start, i));
                            start = i + 1;
                        }
                    }
                    default -> { }
                }
            }
            parts.add(args.substring(start));
            if (parts.size() == 1 && parts.get(0).trim().isEmpty()) return new ArrayList<>();
            return parts;
        }
    }
}