package com.cleandecompile.stage4;

import com.cleandecompile.PipelineConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    private static final Pattern METHOD_HEADER = Pattern.compile(
            "^\\s*+(?!if\\b|for\\b|while\\b|switch\\b|catch\\b|synchronized\\b|do\\b|else\\b|try\\b)(?:(?:public|private|protected|static|final|synchronized|native|abstract|strictfp)\\s+)*[\\w.$<>\\[\\],? ]*\\w+\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w., ]+)?\\s*\\{\\s*$");

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

        LambdaRestoreFixer.Result lambdaFix = LambdaRestoreFixer.tryFixAll(sourceRoot, bucketed);
        int lambdaFixes = lambdaFix.fixes();
        fixes += lambdaFixes;
        bucketed = lambdaFix.unhandled(bucketed);

        MemberResolutionFixer.Result memberFix =
                MemberResolutionFixer.tryFixAll(sourceRoot, bucketed);
        int memberFixes = memberFix.fixes();
        fixes += memberFixes;
        bucketed = memberFix.unhandled(bucketed);

        CollectionSourceFixer.Result collectionFix =
                CollectionSourceFixer.tryFixAll(sourceRoot, bucketed);
        int collectionFixes = collectionFix.fixes();
        fixes += collectionFixes;
        bucketed = collectionFix.unhandled(bucketed);

        InferredTypeFixer.Result inferredFix =
                InferredTypeFixer.tryFixAll(sourceRoot, bucketed);
        int inferredTypeFixes = inferredFix.fixes();
        fixes += inferredTypeFixes;
        bucketed = inferredFix.unhandled(bucketed);

        ResidualSyntaxFixer.Result residualSyntaxFix =
                ResidualSyntaxFixer.tryFixAll(sourceRoot, bucketed);
        int residualSyntaxFixes = residualSyntaxFix.fixes();
        fixes += residualSyntaxFixes;
        bucketed = residualSyntaxFix.unhandled(bucketed);

        DuplicateLocalFixer.Result duplicateFix =
                DuplicateLocalFixer.tryFixAll(sourceRoot, bucketed);
        int duplicateFixes = duplicateFix.fixes();
        fixes += duplicateFixes;
        bucketed = duplicateFix.unhandled(bucketed);

        ResidualAccessFixer.Result residualFix =
                ResidualAccessFixer.tryFixAll(sourceRoot, bucketed);
        int residualFixes = residualFix.fixes();
        fixes += residualFixes;
        bucketed = residualFix.unhandled(bucketed);

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

        int wrapFixes = CheckedWrapFixer.tryFixAll(sourceRoot, bucketed);
        fixes += wrapFixes;

        VoidAbstractStubFixer.Result voidAbstractFix =
                VoidAbstractStubFixer.tryFixAll(sourceRoot, bucketed);
        int voidAbstractFixes = voidAbstractFix.fixes();
        fixes += voidAbstractFixes;

        if (fixes > 0) {
            System.out.printf("  fixes applied: diamond=%d arrayRetype=%d split=%d objectTyped=%d lambdaRestore=%d memberResolution=%d collectionSource=%d inferredType=%d residualSyntax=%d duplicateLocal=%d residualAccess=%d imports=%d concat=%d casts=%d artifacts=%d compare=%d widen=%d receiver=%d wrap=%d voidAbstractStub=%d%n",
                    diamondFixes, arrayFixes, splitFixes, objectFixes, lambdaFixes, memberFixes, collectionFixes, inferredTypeFixes, residualSyntaxFixes, duplicateFixes, residualFixes, importFixes, concatFixes, castFixes, artifactFixes, compareFixes, widenFixes, receiverFixes, wrapFixes, voidAbstractFixes);
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

    static final class LambdaRestoreFixer {
        private static final Pattern INVALID_METHOD_REF = Pattern.compile(
                "incompatible types: invalid method reference.*?method\\s+([A-Za-z_$][\\w$]*)\\s+in class\\s+[\\w.$]+ cannot be applied.*?required:\\s*(.*?)\\s+found:\\s*no arguments",
                Pattern.DOTALL);
        private static final Pattern THIS_REFERENCE = Pattern.compile(
                "(?<![A-Za-z0-9_$\\.])this::([A-Za-z_$][\\w$]*)(?![A-Za-z0-9_$])");
        private static final Pattern METHOD_HEADER = Pattern.compile(
                "^\\s*(?:(?:public|private|protected|static|final|synchronized|native|abstract|strictfp|default)\\s+)*[A-Za-z_$][\\w.$<>\\[\\],? ]*\\s+([A-Za-z_$][\\w$]*)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w., ]+)?\\s*\\{\\s*\\}?\\s*$");
        private static final Pattern LOCAL = Pattern.compile(
                "^(?:(?:final)\\s+)*([A-Za-z_$][\\w.$]*(?:\\s*<[^;=]+>)?(?:\\s*\\[\\s*\\])*)\\s+([A-Za-z_$][\\w$]*)(?:\\s*=.*)?$");

        record Result(int fixes, List<Diagnostic<? extends JavaFileObject>> handled) {
            List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
                if (handled.isEmpty()) return all;
                return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
            }

            static final Result NONE = new Result(0, List.of());
        }

        private record MethodInfo(int line, int bodyStart, int bodyEnd, String name,
                                 List<String> parameters) {}

        private record Capture(String name, String type, int line, int scopeDepth, int scopeEnd) {}

        private static final class MethodContext {
            final MethodInfo method;
            final int[] depths;
            final Map<String, Set<String>> imports;
            final String packageName;

            MethodContext(MethodInfo method, int[] depths, Map<String, Set<String>> imports, String packageName) {
                this.method = method;
                this.depths = depths;
                this.imports = imports;
                this.packageName = packageName;
            }
        }

        private LambdaRestoreFixer() {
        }

        static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            List<Diagnostic<? extends JavaFileObject>> handled = new ArrayList<>();
            for (var b : bucketed) {
                if (b.category() != DiagnosticBucketer.Category.INCOMPATIBLE_TYPES
                        || b.diagnostic().getSource() == null
                        || !INVALID_METHOD_REF.matcher(b.diagnostic().getMessage(Locale.ENGLISH)).find()) continue;
                Path file = Path.of(b.diagnostic().getSource().toUri()).toAbsolutePath().normalize();
                if (file.startsWith(sourceRoot.toAbsolutePath().normalize())) {
                    byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(b);
                }
            }
            int fixed = 0;
            for (var entry : byFile.entrySet()) {
                List<String> lines;
                try {
                    lines = new ArrayList<>(Files.readAllLines(entry.getKey()));
                } catch (IOException e) {
                    continue;
                }
                List<String> codeLines = lexicalLines(lines);
                int[] depths = depths(codeLines);
                boolean changed = false;
                for (var b : entry.getValue()) {
                    Matcher matcher = INVALID_METHOD_REF.matcher(b.diagnostic().getMessage(Locale.ENGLISH));
                    if (!matcher.find()) continue;
                    String methodName = matcher.group(1);
                    List<String> required = diagnosticParameterTypes(matcher.group(2));
                    int lineNo = (int) b.diagnostic().getLineNumber();
                    if (lineNo < 1 || lineNo > lines.size()) continue;
                    MethodContext context = contextFor(codeLines, depths, entry.getKey(), lineNo - 1);
                    if (context == null) continue;
                    MethodInfo target = targetMethod(codeLines, context, methodName, required);
                    if (target == null) continue;
                    Matcher reference = THIS_REFERENCE.matcher(codeLines.get(lineNo - 1));
                    if (!reference.find() || !methodName.equals(reference.group(1))) continue;
                    Matcher secondReference = THIS_REFERENCE.matcher(codeLines.get(lineNo - 1));
                    if (secondReference.find(reference.end())) continue;
                    List<Capture> captures = captures(codeLines, depths, context, lineNo - 1, required);
                    if (captures == null) continue;
                    int referenceStart = reference.start();
                    int referenceEnd = reference.end();
                    String replacement = "() -> this." + methodName
                            + "(" + captures.stream().map(Capture::name).collect(Collectors.joining(", ")) + ")";
                    String rewritten = lines.get(lineNo - 1).substring(0, referenceStart)
                            + replacement + lines.get(lineNo - 1).substring(referenceEnd);
                    lines.set(lineNo - 1, rewritten);
                    handled.add(b.diagnostic());
                    changed = true;
                    fixed++;
                }
                if (changed) Files.write(entry.getKey(), lines);
            }
            return new Result(fixed, handled);
        }

        private static MethodContext contextFor(List<String> lines, int[] depths, Path file, int referenceLine) throws IOException {
            Map<String, Set<String>> imports = new HashMap<>();
            String packageName = null;
            for (String line : lines) {
                String code = line.trim();
                if (code.startsWith("package ")) {
                    packageName = code.substring(8).replace(";", "").trim();
                } else if (code.startsWith("import ") && !code.contains(".*")) {
                    String imported = code.substring(7).replace(";", "").trim();
                    int dot = imported.lastIndexOf('.');
                    if (dot > 0) imports.computeIfAbsent(imported.substring(dot + 1),
                            key -> new LinkedHashSet<>()).add(imported);
                }
            }
            MethodInfo method = null;
            for (int i = 0; i < lines.size(); i++) {
                Matcher matcher = METHOD_HEADER.matcher(lines.get(i));
                if (!matcher.matches()) continue;
                int end = methodEnd(lines, depths, i);
                if (i <= referenceLine && referenceLine <= end
                        && (method == null || i > method.line())) {
                    method = new MethodInfo(i, i, end, matcher.group(1),
                            parameterTypes(matcher.group(2)));
                }
            }
            return method == null ? null : new MethodContext(method, depths, imports, packageName);
        }

        private static MethodInfo targetMethod(List<String> lines, MethodContext context, String name,
                                               List<String> required) {
            MethodInfo found = null;
            for (String line : lines) {
                Matcher matcher = METHOD_HEADER.matcher(line);
                if (!matcher.matches() || !name.equals(matcher.group(1))) continue;
                List<String> parameters = parameterTypes(matcher.group(2));
                if (!sameParameterTypes(parameters, required, context)) continue;
                if (found != null) return null;
                found = new MethodInfo(0, 0, 0, name, parameters);
            }
            return found;
        }

        private static List<Capture> captures(List<String> lines, int[] depths, MethodContext context,
                                              int referenceLine, List<String> required) {
            if (referenceLine < context.method.bodyStart() || referenceLine > context.method.bodyEnd()) return null;
            List<Capture> all = new ArrayList<>();
            addParameterCaptures(lines, depths, context, all);
            for (int i = context.method.bodyStart() + 1; i <= referenceLine; i++) {
                String code = lines.get(i).trim();
                for (String declaration : splitParameters(code.endsWith(";") ? code.substring(0, code.length() - 1) : code)) {
                    Matcher matcher = LOCAL.matcher(declaration.trim());
                    if (matcher.matches()) {
                        all.add(new Capture(matcher.group(2), matcher.group(1), i,
                                depths[i], enclosingScopeEnd(lines, depths, i, depths[i], context.method.bodyEnd())));
                    }
                }
            }
            List<Capture> selected = new ArrayList<>();
            for (String type : required) {
                List<Capture> matches = new ArrayList<>();
                for (Capture capture : all) {
                    if (depths[referenceLine] >= capture.scopeDepth()
                            && referenceLine <= capture.scopeEnd()
                            && isEffectivelyFinal(lines, capture)
                            && sameType(capture.type(), type, context)) matches.add(capture);
                }
                if (matches.size() != 1) return null;
                selected.add(matches.get(0));
            }
            return selected;
        }

        private static void addParameterCaptures(List<String> lines, int[] depths, MethodContext context,
                                                 List<Capture> captures) {
            Matcher matcher = METHOD_HEADER.matcher(lines.get(context.method.line()));
            if (matcher.matches()) {
                for (String parameter : splitParameters(matcher.group(2))) {
                    Matcher local = LOCAL.matcher(parameter.trim());
                    if (local.matches())                     captures.add(new Capture(local.group(2), local.group(1), context.method.line(),
                            depths[context.method.bodyStart()] + braceDelta(lines.get(context.method.line())),
                            context.method.bodyEnd()));
                }
            }
        }

        private static int enclosingScopeEnd(List<String> lines, int[] depths, int declarationLine,
                                             int scopeDepth, int methodEnd) {
            for (int i = declarationLine + 1; i <= methodEnd; i++) {
                if (depths[i] <= scopeDepth) return i - 1;
            }
            return methodEnd;
        }

        private static boolean isEffectivelyFinal(List<String> lines, Capture capture) {
            for (int i = capture.line() + 1; i <= capture.scopeEnd(); i++) {
                String code = lines.get(i);
                String name = Pattern.quote(capture.name());
                Pattern mutation = Pattern.compile("(?<![A-Za-z0-9_$])(?:\\+\\+|--|>>>=|>>=|<<=|(?:[+\\-*/%&|^]=?)|=(?!=))\\s*"
                        + name
                        + "(?![A-Za-z0-9_$])|(?<![A-Za-z0-9_$])"
                        + name
                        + "\\s*(?:\\+\\+|--|>>>=|>>=|<<=|(?:[+\\-*/%&|^]=?)|=(?!=))(?![A-Za-z0-9_$])");
                if (mutation.matcher(code).find()) return false;
            }
            return true;
        }

        private static boolean sameParameterTypes(List<String> first, List<String> second, MethodContext context) {
            if (first.size() != second.size()) return false;
            for (int i = 0; i < first.size(); i++) {
                if (!sameType(first.get(i), second.get(i), context)) return false;
            }
            return true;
        }

        private static boolean sameType(String first, String second, MethodContext context) {
            String a = normalizeType(first);
            String b = normalizeType(second);
            if (a.contains("...") || b.contains("...")) return false;
            if (a.equals(b)) return true;
            String resolvedA = resolveSimple(a, context);
            String resolvedB = resolveSimple(b, context);
            return resolvedA != null && resolvedA.equals(resolvedB);
        }

        private static String resolveSimple(String type, MethodContext context) {
            if (type.contains(".") || type.contains("[]")) return type;
            Set<String> imported = context.imports.get(type);
            if (imported != null) return imported.size() == 1 ? imported.iterator().next() : null;
            return context.packageName == null || context.packageName.isEmpty() ? null : context.packageName + "." + type;
        }

        private static String normalizeType(String type) {
            return type.replaceAll("\\s+", "");
        }

        static List<String> lexicalLines(List<String> lines) {
            List<String> result = new ArrayList<>(lines.size());
            boolean blockComment = false;
            boolean string = false;
            boolean character = false;
            boolean textBlock = false;
            boolean escaped = false;
            for (String line : lines) {
                StringBuilder code = new StringBuilder(line.length());
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                    if (textBlock) {
                        if (escaped) {
                            code.append(' ');
                            escaped = false;
                        } else if (c == '\\') {
                            code.append(' ');
                            escaped = true;
                        } else if (c == '"') {
                            int run = quoteRun(line, i);
                            code.append(" ".repeat(run));
                            i += run - 1;
                            if (run % 3 == 0) textBlock = false;
                        } else {
                            code.append(' ');
                        }
                    } else if (blockComment) {
                        if (c == '*' && next == '/') {
                            code.append("  ");
                            i++;
                            blockComment = false;
                        } else {
                            code.append(' ');
                        }
                    } else if (string || character) {
                        code.append(' ');
                        if (escaped) {
                            escaped = false;
                        } else if (c == '\\') {
                            escaped = true;
                        } else if ((string && c == '"') || (character && c == '\'')) {
                            string = false;
                            character = false;
                        }
                    } else if (c == '/' && next == '*') {
                        code.append("  ");
                        i++;
                        blockComment = true;
                    } else if (c == '/' && next == '/') {
                        while (i < line.length()) {
                            code.append(' ');
                            i++;
                        }
                        break;
                    } else if (c == '"' && next == '"'
                            && i + 2 < line.length() && line.charAt(i + 2) == '"') {
                        code.append("   ");
                        i += 2;
                        textBlock = true;
                    } else if (c == '"') {
                        code.append(' ');
                        string = true;
                    } else if (c == '\'') {
                        code.append(' ');
                        character = true;
                    } else {
                        code.append(c);
                    }
                }
                result.add(code.toString());
            }
            return result;
        }

        private static int quoteRun(String line, int from) {
            int run = 0;
            while (from + run < line.length() && line.charAt(from + run) == '"') run++;
            return run;
        }

        private static int[] depths(List<String> lines) {
            int[] result = new int[lines.size()];
            int depth = 0;
            for (int i = 0; i < lines.size(); i++) {
                result[i] = depth;
                String code = stripLine(lines.get(i));
                for (int j = 0; j < code.length(); j++) {
                    if (code.charAt(j) == '{') depth++;
                    else if (code.charAt(j) == '}') depth--;
                }
            }
            return result;
        }

        private static int methodEnd(List<String> lines, int[] depths, int methodLine) {
            int start = depths[methodLine];
            int methodDelta = braceDelta(lines.get(methodLine));
            if (methodDelta == 0) return methodLine;
            for (int i = methodLine + 1; i < lines.size(); i++) {
                if (depths[i] + braceDelta(lines.get(i)) == start) return i - 1;
            }
            return -1;
        }

        private static int braceDelta(String line) {
            int delta = 0;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == '{') delta++;
                else if (line.charAt(i) == '}') delta--;
            }
            return delta;
        }

        private static List<String> diagnosticParameterTypes(String text) {
            List<String> result = new ArrayList<>();
            for (String type : splitParameters(text.replace('\n', ' '))) {
                if (type.isEmpty() || type.contains("...")) return List.of();
                result.add(type);
            }
            return result;
        }

        private static List<String> parameterTypes(String text) {
            List<String> result = new ArrayList<>();
            for (String parameter : splitParameters(text)) {
                Matcher matcher = LOCAL.matcher(parameter.trim());
                if (!matcher.matches() || parameter.contains("...")) return List.of();
                result.add(matcher.group(1));
            }
            return result;
        }

        private static List<String> splitParameters(String text) {
            List<String> result = new ArrayList<>();
            int start = 0;
            int genericDepth = 0;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '<') genericDepth++;
                else if (c == '>') genericDepth--;
                else if (c == ',' && genericDepth == 0) {
                    result.add(text.substring(start, i).trim());
                    start = i + 1;
                }
            }
            if (start < text.length()) result.add(text.substring(start).trim());
            return result;
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

        static int findMatchingParen(String code, int open) {
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

        static int topLevelChar(String code, char want) {            int depthParen = 0, depthBracket = 0, depthBrace = 0;
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
        return methodEnd(lines, header, LambdaRestoreFixer.lexicalLines(lines));
    }

    static int methodEnd(List<String> lines, int header, List<String> masked) {
        int depth = 0;
        for (int i = header; i < Math.min(lines.size(), header + 2000); i++) {
            String code = masked.get(i);
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
        enum Shape { METHOD, FIELD }

        private static final Pattern PRIVATE_ACCESS = Pattern.compile(
                "([A-Za-z_$][\\w$]*)\\s*(\\([^()]*\\))?\\s+has private access in\\s+([\\w.$]+)");

        private static final String MODIFIER_RUN =
                "(?:(?:public|private|protected|static|final|synchronized|native|abstract|strictfp|default)\\s+)*";
        private static final String TYPE_RUN =
                "[A-Za-z_$][\\w.$]*(?:\\s*<[^;=]*>)?(?:\\s*\\[\\s*\\])*\\s+";
        private static final Pattern VISIBILITY = Pattern.compile("(?:public|private|protected)");
        private static final int UNKNOWN_PARAMETERS = -1;

        private AccessWidenFixer() {
        }

        static Shape shapeOf(String parameters) {
            return parameters == null ? Shape.FIELD : Shape.METHOD;
        }

        static int tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            OwnerResolver resolver = ownerResolver(sourceRoot);
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                Path file = sourceOf(b, sourceRoot);
                if (file != null) byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(b);
            }
            int fixed = 0;
            for (var entry : byFile.entrySet()) {
                for (var b : entry.getValue()) {
                    Matcher m = PRIVATE_ACCESS.matcher(b.diagnostic().getMessage(Locale.ENGLISH));
                    if (!m.find()) continue;
                    String owner = m.group(3);
                    Path ownerFile = resolver.resolve(entry.getKey(), owner);
                    if (ownerFile == null) continue;
                    String simple = owner.substring(owner.lastIndexOf('.') + 1);
                    String parameters = m.group(2);
                    if (widenMember(ownerFile, simple, m.group(1), parameterCount(parameters),
                            shapeOf(parameters))) fixed++;
                }
            }
            return fixed;
        }

        static OwnerResolver ownerResolver(Path sourceRoot) {
            return new OwnerResolver(sourceRoot);
        }

        static Path sourceOf(DiagnosticBucketer.Bucketed b, Path sourceRoot) {
            if (b.diagnostic().getSource() == null) return null;
            Path file;
            try {
                file = Path.of(b.diagnostic().getSource().toUri()).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                return null;
            }
            return file.startsWith(sourceRoot.toAbsolutePath().normalize()) ? file : null;
        }

        static int parameterCount(String parameters) {
            if (parameters == null) return UNKNOWN_PARAMETERS;
            String inner = parameters.substring(1, parameters.length() - 1).trim();
            if (inner.isEmpty()) return 0;
            int count = 1;
            int angle = 0;
            for (int i = 0; i < inner.length(); i++) {
                char c = inner.charAt(i);
                if (c == '<') angle++;
                else if (c == '>') angle--;
                else if (c == ',' && angle == 0) count++;
            }
            return count;
        }

        static final class OwnerResolver {
            private final Path sourceRoot;
            private final Map<String, Path> memo = new HashMap<>();

            private OwnerResolver(Path sourceRoot) {
                this.sourceRoot = sourceRoot;
            }

            Path resolve(Path callSite, String owner) throws IOException {
                String key = callSite + "|" + owner;
                if (memo.containsKey(key)) return memo.get(key);
                Path resolved = resolveUncached(callSite, owner);
                memo.put(key, resolved);
                return resolved;
            }

            private Path resolveUncached(Path callSite, String owner) throws IOException {
                if (owner.indexOf('.') >= 0) {
                    Path direct = sourceRoot.resolve(owner.replace('.', '/') + ".java");
                    if (Files.isDirectory(direct)) return null;
                    return Files.isRegularFile(direct) ? direct : null;
                }
                List<Path> candidates = new ArrayList<>();
                String packageName = packageOf(callSite);
                addIfSource(candidates, packageName.isEmpty() ? owner : packageName + "." + owner);
                for (String imported : singleTypeImports(callSite)) {
                    int dot = imported.lastIndexOf('.');
                    if (dot >= 0 && imported.substring(dot + 1).equals(owner)) {
                        addIfSource(candidates, imported);
                    }
                }
                if (candidates.size() == 1) return candidates.get(0);
                if (!candidates.isEmpty()) return null;
                return uniqueFileNamed(owner);
            }

            private void addIfSource(List<Path> candidates, String fqn) {
                Path file = sourceRoot.resolve(fqn.replace('.', '/') + ".java");
                if (Files.isDirectory(file)) return;
                if (Files.isRegularFile(file) && !candidates.contains(file)) candidates.add(file);
            }

            private Path uniqueFileNamed(String owner) throws IOException {
                try (Stream<Path> walk = Files.walk(sourceRoot)) {
                    List<Path> matches = walk
                            .filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().equals(owner + ".java"))
                            .toList();
                    return matches.size() == 1 ? matches.get(0) : null;
                }
            }

            private static String packageOf(Path javaFile) throws IOException {
                for (String line : Files.readAllLines(javaFile)) {
                    String code = line.strip();
                    if (code.startsWith("package ")) return code.substring(8).replace(";", "").strip();
                }
                return "";
            }

            private static List<String> singleTypeImports(Path javaFile) throws IOException {
                List<String> imports = new ArrayList<>();
                for (String line : Files.readAllLines(javaFile)) {
                    String code = line.strip();
                    if (code.startsWith("import ") && !code.contains("*")) {
                        imports.add(code.substring(7).replace(";", "").strip());
                    }
                }
                return imports;
            }
        }

        private record Match(int line, int modifierStart, int declarationStart, int parameterCount) {}

        static boolean widenMember(Path javaFile, String ownerSimpleName, String member, int parameterCount,
                                    Shape shape) throws IOException {
            List<String> lines = new ArrayList<>(Files.readAllLines(javaFile));
            List<String> masked = LambdaRestoreFixer.lexicalLines(lines);
            int[] depths = LambdaRestoreFixer.depths(masked);
            List<Match> candidates = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                if (depths[i] != 1) continue;
                Match found = declaration(masked.get(i), ownerSimpleName, member, i, shape);
                if (found != null) candidates.add(found);
            }
            Match chosen = select(candidates, parameterCount);
            if (chosen == null) return false;
            String rewritten = publicize(lines.get(chosen.line()), masked.get(chosen.line()),
                    chosen.modifierStart(), chosen.declarationStart());
            if (rewritten == null) return false;
            lines.set(chosen.line(), rewritten);
            Files.write(javaFile, lines);
            return true;
        }

        private static Match select(List<Match> candidates, int parameterCount) {
            if (candidates.size() == 1) return candidates.get(0);
            if (candidates.isEmpty() || parameterCount == UNKNOWN_PARAMETERS) return null;
            List<Match> matching = candidates.stream()
                    .filter(c -> c.parameterCount() == parameterCount)
                    .toList();
            return matching.size() == 1 ? matching.get(0) : null;
        }

        private static Match declaration(String maskedLine, String ownerSimpleName, String member, int line,
                                          Shape shape) {
            String name = "(?<![A-Za-z0-9_$])" + Pattern.quote(member) + "(?![A-Za-z0-9_$])";
            if (shape == Shape.METHOD) {
                if (ownerSimpleName != null) {
                    String owner = "(?<![A-Za-z0-9_$])" + Pattern.quote(ownerSimpleName)
                            + "(?![A-Za-z0-9_$])";
                    Matcher constructor = Pattern.compile(
                            "^(?<indent>\\s*)(?:" + MODIFIER_RUN + ")(?<name>" + owner
                                    + ")\\s*\\((?<args>[^()]*)\\)")
                            .matcher(maskedLine);
                    if (constructor.find()) {
                        return new Match(line, constructor.end("indent"), constructor.start("name"),
                                parameterCount("(" + constructor.group("args") + ")"));
                    }
                }
                Matcher method = Pattern.compile(
                        "^(?<indent>\\s*)(?:" + MODIFIER_RUN + ")(?:(?<type>" + TYPE_RUN + "))?(?<name>" + name
                                + ")\\s*\\((?<args>[^()]*)\\)")
                        .matcher(maskedLine);
                if (method.find()) {
                    int start = method.group("type") == null
                            ? method.start("name")
                            : method.start("type");
                    return new Match(line, method.end("indent"), start,
                            parameterCount("(" + method.group("args") + ")"));
                }
                return null;
            }
            Matcher field = Pattern.compile(
                    "^(?<indent>\\s*)(?:" + MODIFIER_RUN + ")(?<type>" + TYPE_RUN + ")(?<name>" + name
                            + ")\\s*(?==|;|\\[|,)")
                    .matcher(maskedLine);
            return field.find()
                    ? new Match(line, field.end("indent"), field.start("type"), UNKNOWN_PARAMETERS)
                    : null;
        }

        private static String publicize(String original, String maskedLine, int modifierStart,
                                        int declarationStart) {
            if (original.length() != maskedLine.length()) return null;
            Matcher visibility = VISIBILITY.matcher(maskedLine);
            visibility.region(modifierStart, declarationStart);
            if (visibility.find()) {
                if (visibility.group().equals("public")) return null;
                return original.substring(0, visibility.start()) + "public"
                        + original.substring(visibility.end());
            }
            return original.substring(0, modifierStart) + "public " + original.substring(modifierStart);
        }
    }

    static final class ResidualAccessFixer {
        private static final Pattern NOT_PUBLIC = Pattern.compile(
                "([A-Za-z_$][\\w$]*)\\s*(\\([^()]*\\))?\\s+is not public in\\s+"
                        + "([\\w.$]+);\\s+cannot be accessed");
        private static final Pattern STALE_OVERRIDE =
                Pattern.compile("does not override or implement a method from a supertype");
        private static final Pattern METHOD_DECLARATION = Pattern.compile(
                "^(?:(?:public|private|protected|static|final|synchronized|native|abstract|strictfp|default)\\s+)*"
                        + "(?:[A-Za-z_$][\\w.$]*(?:\\s*<[^;=]*>)?(?:\\s*\\[\\s*\\])*\\s+)?"
                        + "(?<name>[A-Za-z_$][\\w$]*)\\s*\\([^()]*\\)\\s*(?:throws\\s+[\\w$.,\\s]+)?\\s*\\{\\s*$");
        private static final Pattern BODY_END = Pattern.compile("^\\s*\\}");
        private static final int DECLARATION_WINDOW = 4;
        private static final Set<String> NOT_METHOD_KEYWORDS = Set.of(
                "new", "return", "if", "for", "while", "switch", "catch", "synchronized",
                "assert", "throw", "do", "else", "try", "this", "super", "case", "instanceof");

        record Result(int fixes, List<Diagnostic<? extends JavaFileObject>> handled) {
            List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
                if (handled.isEmpty()) return all;
                return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
            }

            static final Result NONE = new Result(0, List.of());
        }

        private ResidualAccessFixer() {
        }

        static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            List<Diagnostic<? extends JavaFileObject>> handled = new ArrayList<>();
            int fixed = widen(sourceRoot, bucketed, handled) + blankOverrides(sourceRoot, bucketed, handled);
            return fixed == 0 ? Result.NONE : new Result(fixed, handled);
        }

        private static int widen(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed,
                                 List<Diagnostic<? extends JavaFileObject>> handled) throws IOException {
            AccessWidenFixer.OwnerResolver resolver = AccessWidenFixer.ownerResolver(sourceRoot);
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                Path file = AccessWidenFixer.sourceOf(b, sourceRoot);
                if (file != null) byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(b);
            }
            int fixed = 0;
            for (var entry : byFile.entrySet()) {
                for (var b : entry.getValue()) {
                    Matcher m = NOT_PUBLIC.matcher(b.diagnostic().getMessage(Locale.ENGLISH));
                    if (!m.find()) continue;
                    String owner = m.group(3);
                    Path ownerFile = resolver.resolve(entry.getKey(), owner);
                    if (ownerFile == null) continue;
                    String simple = owner.substring(owner.lastIndexOf('.') + 1);
                    String parameters = m.group(2);
                    if (!AccessWidenFixer.widenMember(ownerFile, simple, m.group(1),
                            AccessWidenFixer.parameterCount(parameters),
                            AccessWidenFixer.shapeOf(parameters))) continue;
                    handled.add(b.diagnostic());
                    fixed++;
                }
            }
            return fixed;
        }

        private static int blankOverrides(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed,
                                          List<Diagnostic<? extends JavaFileObject>> handled) throws IOException {
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                if (!STALE_OVERRIDE.matcher(b.diagnostic().getMessage(Locale.ENGLISH)).find()) continue;
                Path file = AccessWidenFixer.sourceOf(b, sourceRoot);
                if (file != null) byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(b);
            }
            int fixed = 0;
            for (var entry : byFile.entrySet()) {
                for (var b : entry.getValue()) {
                    long lineNo = b.diagnostic().getLineNumber();
                    if (lineNo < 1 || lineNo > Integer.MAX_VALUE) continue;
                    if (!blankOverride(entry.getKey(), (int) lineNo)) continue;
                    handled.add(b.diagnostic());
                    fixed++;
                }
            }
            return fixed;
        }

        private static boolean blankOverride(Path javaFile, int diagnosticLine) throws IOException {
            List<String> lines = new ArrayList<>(Files.readAllLines(javaFile));
            List<String> masked = LambdaRestoreFixer.lexicalLines(lines);
            int[] depths = LambdaRestoreFixer.depths(masked);
            int at = diagnosticLine - 1;
            if (at < 0 || at >= masked.size()) return false;
            int declaration = declaration(masked, depths, at);
            if (declaration < 0) return false;
            int annotation = declaration - 1;
            if (annotation < 0 || !masked.get(annotation).strip().equals("@Override")) return false;
            int token = masked.get(annotation).indexOf("@Override");
            if (token < 0 || lines.get(annotation).length() != masked.get(annotation).length()) return false;
            lines.set(annotation, lines.get(annotation).substring(0, token)
                    + " ".repeat("@Override".length())
                    + lines.get(annotation).substring(token + "@Override".length()));
            Files.write(javaFile, lines);
            return true;
        }

        private static int declaration(List<String> masked, int[] depths, int at) {
            for (int i = at; i < masked.size() && i <= at + DECLARATION_WINDOW; i++) {
                if (depths[i] != 1) return -1;
                if (masked.get(i).isBlank()) return -1;
                if (BODY_END.matcher(masked.get(i)).find()) return -1;
                Matcher m = METHOD_DECLARATION.matcher(masked.get(i).strip());
                if (m.find() && !NOT_METHOD_KEYWORDS.contains(m.group("name"))) return i;
            }
            return -1;
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
            List<String> masked = LambdaRestoreFixer.lexicalLines(lines);
            for (var entry : byVar.entrySet()) {
                if (tryRetype(lines, entry.getKey(), mismatchLines, masked)) {
                    fixed++;
                    masked = LambdaRestoreFixer.lexicalLines(lines);
                    for (var d : diags) handled.add(d);
                }
            }
            if (fixed > 0) Files.write(file, lines);
            return fixed;
        }

        private static boolean tryRetype(List<String> lines, String var, Set<Integer> mismatchLines,
                                        List<String> masked) {
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
                if (METHOD_HEADER.matcher(masked.get(i)).matches()) return false;
                if (decl.matcher(masked.get(i)).find()) {
                    declLine = i;
                    break;
                }
            }
            if (declLine < 0) return false;

            // Enclosing method: header above, brace-matched end below.
            int header = -1;
            for (int i = declLine; i >= Math.max(0, declLine - 100); i--) {
                if (METHOD_HEADER.matcher(masked.get(i)).matches()) {
                    header = i;
                    break;
                }
            }
            if (header < 0) return false;
            int end = methodEnd(lines, header, masked);
            if (end < 0) return false;

            int declCount = 0;
            for (int i = header; i <= end; i++) {
                String code = masked.get(i);
                if (decl.matcher(code).find()) declCount++;
                if (code.contains("new ") && code.contains("{")) return false; // anonymous class
            }
            if (declCount != 1) return false;

            Pattern word = Pattern.compile("(?<![\\w$])" + Pattern.quote(var) + "(?![\\w$])");
            for (int i = header; i <= end; i++) {
                String code = masked.get(i);
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
            List<String> masked = LambdaRestoreFixer.lexicalLines(lines);
            for (var d : diags) {
                int lineNo = (int) d.getLineNumber();
                if (lineNo < 1 || lineNo > lines.size()) continue;
                int eq = lastTopLevelEquals(lines.get(lineNo - 1));
                if (eq < 0) continue;
                String lhs = lines.get(lineNo - 1).substring(0, eq).trim();
                if (!lhs.matches("[\\w$]+")) continue;
                if (trySplit(lines, file, lhs, lineNo, bucketed, handled, masked)) {
                    fixed++;
                    masked = LambdaRestoreFixer.lexicalLines(lines);
                    handled.add(d);
                }
            }
            if (fixed > 0) Files.write(file, lines);
            return fixed;
        }

        private static boolean trySplit(List<String> lines, Path file, String var, int triggerLine,
                                        List<DiagnosticBucketer.Bucketed> bucketed,
                                        Set<Diagnostic<? extends JavaFileObject>> handled,
                                        List<String> masked) {
            Pattern decl = Pattern.compile(
                    "String\\s*\\[\\s*\\]\\s+" + Pattern.quote(var) + "\\b");
            // Declaration above, enclosing method around it.
            int declLine = -1;
            for (int i = triggerLine - 1; i >= Math.max(0, triggerLine - 200); i--) {
                if (METHOD_HEADER.matcher(masked.get(i)).matches()) return false;
                if (decl.matcher(masked.get(i)).find()) {
                    declLine = i;
                    break;
                }
            }
            if (declLine < 0) return false;
            int header = -1;
            for (int i = declLine; i >= Math.max(0, declLine - 100); i--) {
                if (METHOD_HEADER.matcher(masked.get(i)).matches()) {
                    header = i;
                    break;
                }
            }
            if (header < 0) return false;
            int end = methodEnd(lines, header, masked);
            if (end < 0) return false;

            // Region: trigger line up to (excluding) the next reassignment.
            // triggerIdx is 0-based; regionEndExcl is the 0-based exclusive
            // end of the region.
            int triggerIdx = triggerLine - 1;
            Pattern reassign = Pattern.compile("(?<![\\w$.])" + Pattern.quote(var)
                    + "(?![\\w$])\\s*(?:\\[[^\\]]*\\]\\s*)*=(?![=>])");
            int regionEndExcl = end + 1;
            for (int i = triggerIdx + 1; i <= end; i++) {
                String code = masked.get(i);
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
                    if (candidate.matcher(masked.get(i)).find()) {
                        fresh = var + "Str" + counter++;
                        continue outer;
                    }
                }
                break;
            }

            // Verify the region, then rewrite it.
            for (int i = triggerIdx; i < regionEndExcl; i++) {
                String code = masked.get(i);
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
     * Idempotent (a wrapped call no longer errors).
     */
    static final class CheckedWrapFixer {
        private static final Pattern UNREPORTED = Pattern.compile(
                "unreported exception ([\\w.$]+); must be caught or declared to be thrown");
        // 'synchronized (lock) {' is a block, but 'synchronized int m(..) {' is a method header.
        private static final Pattern METHOD_HEADER = Pattern.compile(
                "^\\s*+(?!if\\b|for\\b|while\\b|switch\\b|catch\\b|synchronized\\s*\\(|do\\b|else\\b|try\\b)(?:(?:public|private|protected|static|final|synchronized|native|abstract|strictfp)\\s+)*[\\w.$<>\\[\\],? ]*\\w+\\s*\\([^;{}]*\\)\\s*(?:throws\\s+[\\w., ]+)?\\s*\\{?\\s*$");

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
            // (method header line, exception) pairs already wrapped this pass: a
            // second diagnostic for the same exception in the same method is
            // covered by the first wrap (the try encloses the whole body), and
            // wrapping again would nest a catch for an exception the inner try
            // already handled -- "never thrown in body of corresponding try".
            Set<String> wrapped = new HashSet<>();
            for (var d : ordered) {
                Matcher m = UNREPORTED.matcher(d.getMessage(Locale.ENGLISH));
                if (!m.find()) continue;
                String exception = m.group(1);
                int lineNo = (int) d.getLineNumber();
                if (lineNo < 1 || lineNo > lines.size()) continue;
                if (wrap(lines, file, lineNo, exception, wrapped)) fixed++;
            }
            if (fixed > 0) Files.write(file, lines);
            return fixed;
        }

        private static boolean wrap(List<String> lines, Path file, int lineNo, String exception,
                                    Set<String> wrapped) throws IOException {
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
            // Already wrapped for this exception by an earlier (deeper) diagnostic.
            if (!wrapped.add(header + ":" + exception)) return true;
            // A void method (or constructor) can simply swallow the exception:
            // the catch path falls off the end like any other. A value
            // return cannot -- the catch path would have no value, trading
            // the unreported exception for a missing-return error -- so
            // there the catch rethrows instead (unchecked), which needs no
            // value and keeps the failure loud rather than inventing a
            // fallback result.
            boolean returnsValue = false;
            for (int i = openLine; i <= closeLine; i++) {
                if (Pattern.compile("\\breturn\\s+[^;\\s]").matcher(stripLine(lines.get(i))).find()) {
                    returnsValue = true;
                    break;
                }
            }
            String indent = lines.get(header).substring(0,
                    lines.get(header).length() - lines.get(header).stripLeading().length());
            lines.add(openLine + 1, indent + "   try {");
            // Insertion shifted the close line down by one.
            if (returnsValue) {
                lines.add(closeLine + 1, indent + "   } catch (" + simple + " stage4Checked) {");
                lines.add(closeLine + 2, indent + "      throw " + rethrow(exception, simple));
            } else {
                lines.add(closeLine + 1, indent + "   } catch (" + simple + " ignored) {");
            }
            lines.add(returnsValue ? closeLine + 3 : closeLine + 2, indent + "   }");
            if (!simple.equals(exception) || exception.contains(".")) {
                ensureImport(lines, exception);
            }
            return true;
        }

        /** {@code IOException} keeps its type's unchecked twin; anything else is wrapped. */
        private static String rethrow(String exception, String simple) {
            if (exception.equals("java.io.IOException") || exception.equals("IOException")) {
                return "new java.io.UncheckedIOException(stage4Checked);";
            }
            return "new RuntimeException(stage4Checked);";
        }

        private static void ensureImport(List<String> lines, String fqn) {
            if (!fqn.contains(".")) return;
            // Only direct members of java.lang are implicit; java.lang.reflect.*,
            // java.lang.invoke.* etc. are ordinary packages that need the import.
            if (fqn.startsWith("java.lang.") && fqn.indexOf('.', "java.lang.".length()) < 0) return;
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

    static final class MemberResolutionFixer {
        private static final Pattern OBFUSCATED_METHOD = Pattern.compile("method\\d+");
        private static final Pattern OBFUSCATED_FIELD = Pattern.compile("field\\d+");
        private static final Pattern ON_RECEIVER = Pattern.compile(
                "symbol:\\s*(method|variable)\\s+([A-Za-z_$][\\w$]*)(?:\\(([^)]*)\\))?\\s*"
                        + "location:\\s*variable\\s+([A-Za-z_$][\\w$]*)\\s+of type\\s+([\\w.$]+)");
        private static final Pattern ON_CLASS = Pattern.compile(
                "symbol:\\s*variable\\s+([A-Za-z_$][\\w$]*)\\s*location:\\s*class\\s+([\\w.$]+)");
        private static final String OBJECT = "java.lang.Object";
        private static final int MAX_LOGGED_DECLINES = 40;

        record Result(int fixes, List<Diagnostic<? extends JavaFileObject>> handled) {
            List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
                if (handled.isEmpty()) return all;
                return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
            }

            static final Result NONE = new Result(0, List.of());
        }

        private enum Kind { RECEIVER, CLASS_SCOPE }

        private record Task(Kind kind, String member, boolean isMethod, int arity, String target,
                            String declaredType, int line,
                            List<Diagnostic<? extends JavaFileObject>> diagnostics) {
            private String key() {
                return line + "|" + kind + "|" + member + "|" + arity + "|" + target;
            }
        }

        private record Decl(String owner, String top, boolean isStatic, boolean varargs, int arity) {
        }

        private record FileScope(String pkg, Map<String, String> importsBySimpleName) {
            private String resolve(String spelling) {
                if (spelling == null || spelling.isEmpty()) return null;
                if (spelling.indexOf('.') >= 0) return spelling;
                String imported = importsBySimpleName().get(spelling);
                if (imported != null) return imported;
                return pkg.isEmpty() ? spelling : pkg + "." + spelling;
            }
        }

        private MemberResolutionFixer() {
        }

        static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            Map<Path, LinkedHashMap<String, Task>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                if (b.category() != DiagnosticBucketer.Category.UNRESOLVED_SYMBOL) continue;
                var d = b.diagnostic();
                if (d.getSource() == null) continue;
                String message = d.getMessage(Locale.ENGLISH);
                Task task = taskFor(message, (int) d.getLineNumber());
                if (task == null) continue;
                Path file;
                try {
                    file = Path.of(d.getSource().toUri()).toAbsolutePath().normalize();
                } catch (RuntimeException e) {
                    continue;
                }
                byFile.computeIfAbsent(file, f -> new LinkedHashMap<>())
                        .computeIfAbsent(task.key(), k -> task)
                        .diagnostics().add(d);
            }
            if (byFile.isEmpty()) return Result.NONE;

            Index index = buildIndex(sourceRoot, neededNames(new ArrayList<>(byFile.values())));
            Path root = sourceRoot.toAbsolutePath().normalize();
            int fixes = 0;
            List<String> declined = new ArrayList<>();
            List<Diagnostic<? extends JavaFileObject>> handled = new ArrayList<>();
            for (var entry : byFile.entrySet()) {
                if (!entry.getKey().startsWith(root)) continue;
                fixes += fixFile(entry.getKey(), entry.getValue(), index, handled, declined);
            }
            for (int i = 0; i < Math.min(declined.size(), MAX_LOGGED_DECLINES); i++) {
                System.out.println("  member-resolution declined (audit): " + declined.get(i));
            }
            if (declined.size() > MAX_LOGGED_DECLINES) {
                System.out.println("  member-resolution ... and " + (declined.size() - MAX_LOGGED_DECLINES)
                        + " more declines");
            }
            return fixes == 0 ? Result.NONE : new Result(fixes, handled);
        }

        private static Task taskFor(String message, int line) {
            Matcher receiver = ON_RECEIVER.matcher(message);
            if (receiver.find()) {
                String member = receiver.group(2);
                boolean isMethod = receiver.group(1).equals("method");
                if (isMethod ? !OBFUSCATED_METHOD.matcher(member).matches()
                        : !OBFUSCATED_FIELD.matcher(member).matches()) {
                    return null;
                }
                int arity = isMethod ? arity(receiver.group(3)) : 0;
                if (arity < 0) return null;
                return new Task(Kind.RECEIVER, member, isMethod, arity, receiver.group(4),
                        receiver.group(5), line, new ArrayList<>());
            }
            Matcher inClass = ON_CLASS.matcher(message);
            if (inClass.find() && OBFUSCATED_FIELD.matcher(inClass.group(1)).matches()) {
                return new Task(Kind.CLASS_SCOPE, inClass.group(1), false, 0, inClass.group(2),
                        null, line, new ArrayList<>());
            }
            return null;
        }

        private static int arity(String parameters) {
            if (parameters == null || parameters.isBlank()) return 0;
            if (parameters.contains("...")) return -1;
            int count = 1;
            int depth = 0;
            for (int i = 0; i < parameters.length(); i++) {
                char c = parameters.charAt(i);
                if (c == '<' || c == '(') depth++;
                else if (c == '>' || c == ')') depth--;
                else if (c == ',' && depth == 0) count++;
            }
            return count;
        }

        private static int fixFile(Path file, Map<String, Task> tasks, Index index,
                                   List<Diagnostic<? extends JavaFileObject>> handled,
                                   List<String> declined) throws IOException {
            List<String> lines;
            try {
                lines = new ArrayList<>(Files.readAllLines(file));
            } catch (IOException e) {
                return 0;
            }
            FileScope scope = scopeOf(lines);
            List<String> masked = LambdaRestoreFixer.lexicalLines(lines);
            String name = file.getFileName().toString();
            boolean changed = false;
            int fixes = 0;
            for (Task task : tasks.values()) {
                String at = name + ":" + task.line() + " " + task.member();
                if (task.line() < 1 || task.line() > lines.size()) {
                    declined.add(at + ": flagged line out of range");
                    continue;
                }
                Decl decl = index.lookup(task);
                if (decl == null) {
                    declined.add(at + ": no unique declaring class");
                    continue;
                }
                if (task.kind() == Kind.CLASS_SCOPE && !decl.isStatic()) {
                    declined.add(at + ": " + decl.owner() + "." + task.member() + " is not static");
                    continue;
                }
                if (task.kind() == Kind.RECEIVER && !castIsSound(task, decl, scope, index)) {
                    declined.add(at + ": " + decl.owner() + " is not a subtype of the declared receiver type "
                            + scope.resolve(task.declaredType()));
                    continue;
                }
                String owner = ownerText(decl, task, scope);
                String line = lines.get(task.line() - 1);
                String rewritten = task.kind() == Kind.RECEIVER
                        ? castReceiver(line, mask(masked, task.line() - 1), task, owner)
                        : qualifyUnqualified(line, mask(masked, task.line() - 1), task.member(), owner);
                if (rewritten == null || rewritten.equals(line)) {
                    declined.add(at + ": no unambiguous occurrence on the flagged line");
                    continue;
                }
                lines.set(task.line() - 1, rewritten);
                masked = LambdaRestoreFixer.lexicalLines(lines);
                handled.addAll(task.diagnostics());
                changed = true;
                fixes++;
            }
            if (changed) Files.write(file, lines);
            return fixes;
        }

        private static boolean castIsSound(Task task, Decl decl, FileScope scope, Index index) {
            String declared = scope.resolve(task.declaredType());
            if (declared == null || declared.isEmpty()) return false;
            if (declared.equals(OBJECT)) return true;
            if (declared.equals(decl.owner())) return true;
            return index.descendsFrom(decl.owner(), declared);
        }

        private static String castReceiver(String line, String masked, Task task, String owner) {
            if (masked == null) return null;
            Matcher m = Pattern.compile("(?<![.\\w$])" + Pattern.quote(task.target()) + "\\s*\\.\\s*"
                    + Pattern.quote(task.member()) + "(?![\\w$])").matcher(masked);
            StringBuilder sb = new StringBuilder();
            int cursor = 0;
            int found = 0;
            while (m.find()) {
                if (task.isMethod() && callArity(masked, m.end()) != task.arity()) continue;
                sb.append(line, cursor, m.start())
                        .append("((").append(owner).append(") ").append(task.target())
                        .append(").").append(task.member());
                cursor = m.end();
                found++;
            }
            if (found == 0) return null;
            return sb.append(line.substring(cursor)).toString();
        }

        private static int callArity(String masked, int afterMember) {
            int open = masked.indexOf('(', afterMember);
            if (open < 0) return -1;
            int depth = 0;
            int close = -1;
            for (int i = open; i < masked.length(); i++) {
                char c = masked.charAt(i);
                if (c == '(') depth++;
                else if (c == ')' && --depth == 0) {
                    close = i;
                    break;
                }
            }
            if (close < 0) return -1;
            return arity(masked.substring(open + 1, close));
        }

        private static String qualifyUnqualified(String line, String masked, String member, String owner) {
            if (masked == null) return null;
            Matcher m = Pattern.compile("(?<![.\\w$])" + Pattern.quote(member) + "(?![\\w$])").matcher(masked);
            if (!m.find()) return null;
            int start = m.start();
            int end = m.end();
            if (m.find()) return null;
            return line.substring(0, start) + owner + "." + member + line.substring(end);
        }

        private static String mask(List<String> masked, int index) {
            return index < 0 || index >= masked.size() ? null : masked.get(index);
        }

        private static String ownerText(Decl decl, Task task, FileScope scope) {
            if (task.kind() == Kind.CLASS_SCOPE && decl.owner().equals(scope.resolve(task.target()))) {
                return task.target();
            }
            String top = decl.top();
            String topPkg = top.contains(".") ? top.substring(0, top.lastIndexOf('.')) : "";
            String nested = decl.owner().substring(top.length());
            if (topPkg.equals(scope.pkg()) || top.equals(scope.importsBySimpleName().get(simpleName(top)))) {
                return simpleName(top) + nested;
            }
            return top + nested;
        }

        private static String simpleName(String fqn) {
            return fqn.substring(fqn.lastIndexOf('.') + 1);
        }

        private static FileScope scopeOf(List<String> lines) {
            String pkg = "";
            Map<String, String> imports = new HashMap<>();
            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("package ")) {
                    pkg = line.substring("package ".length()).replace(";", "").trim();
                } else if (line.startsWith("import ") && !line.contains("*")) {
                    String fqn = line.substring("import ".length()).replace(";", "").trim();
                    imports.put(simpleName(fqn), fqn);
                }
            }
            return new FileScope(pkg, imports);
        }

        private static FileScope scopeOf(CompilationUnitTree unit) {
            String pkg = unit.getPackageName() == null ? "" : unit.getPackageName().toString();
            Map<String, String> imports = new HashMap<>();
            for (var imp : unit.getImports()) {
                String fqn = imp.getQualifiedIdentifier().toString();
                if (imp.isStatic() || fqn.endsWith("*")) continue;
                imports.put(simpleName(fqn), fqn);
            }
            return new FileScope(pkg, imports);
        }

        private static final class Index {
            private final Map<String, List<Decl>> methods = new HashMap<>();
            private final Map<String, List<Decl>> fields = new HashMap<>();
            private final Map<String, String> supertypes = new HashMap<>();

            Decl lookup(Task task) {
                if (task.kind() == Kind.CLASS_SCOPE || !task.isMethod()) {
                    return unique(fields.get(task.member()));
                }
                return unique(overloads(task));
            }

            boolean descendsFrom(String type, String ancestor) {
                Set<String> seen = new HashSet<>();
                String current = type;
                while (current != null && seen.add(current)) {
                    if (current.equals(ancestor)) return true;
                    current = supertypes.get(current);
                }
                return false;
            }

            private List<Decl> overloads(Task task) {
                List<Decl> candidates = methods.get(task.member());
                if (candidates == null) return List.of();
                List<Decl> matching = new ArrayList<>();
                for (Decl d : candidates) {
                    if (d.arity() == task.arity() || (d.varargs() && task.arity() >= d.arity() - 1)) {
                        matching.add(d);
                    }
                }
                return matching;
            }

            private static Decl unique(List<Decl> found) {
                if (found == null || found.isEmpty()) return null;
                for (Decl d : found) {
                    if (!d.owner().equals(found.get(0).owner())) return null;
                }
                return found.get(0);
            }
        }

        private static Set<String> neededNames(Collection<Map<String, Task>> tasks) {
            Set<String> names = new TreeSet<>();
            for (Map<String, Task> perFile : tasks) {
                for (Task task : perFile.values()) names.add(task.member());
            }
            return names;
        }

        private static Index buildIndex(Path sourceRoot, Set<String> names) {
            Index index = new Index();
            if (names.isEmpty()) return index;
            Set<Path> pending = candidatesMentioning(sourceRoot, names);
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null || pending.isEmpty()) return index;
            Set<Path> indexed = new HashSet<>();
            DiagnosticCollector<JavaFileObject> ignored = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(ignored, null, StandardCharsets.UTF_8)) {
                while (!pending.isEmpty()) {
                    List<Path> batch = new ArrayList<>();
                    for (Path p : pending) {
                        if (indexed.add(p)) batch.add(p);
                    }
                    pending.clear();
                    if (batch.isEmpty()) continue;
                    JavacTask task = (JavacTask) compiler.getTask(null, fm, ignored, List.of("-proc:none"),
                            null, fm.getJavaFileObjectsFromPaths(batch));
                    for (CompilationUnitTree unit : task.parse()) {
                        FileScope scope = scopeOf(unit);
                        for (Tree decl : unit.getTypeDecls()) {
                            if (!(decl instanceof ClassTree cls) || cls.getSimpleName().length() == 0) continue;
                            String simple = cls.getSimpleName().toString();
                            String fqn = scope.pkg().isEmpty() ? simple : scope.pkg() + "." + simple;
                            indexClass(index, cls, fqn, fqn, scope, sourceRoot, pending);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                return index;
            }
            return index;
        }

        private static Set<Path> candidatesMentioning(Path sourceRoot, Set<String> names) {
            Set<Path> candidates = new LinkedHashSet<>();
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    String text;
                    try {
                        text = new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1);
                    } catch (IOException e) {
                        continue;
                    }
                    for (String name : names) {
                        if (text.contains(name)) {
                            candidates.add(p);
                            break;
                        }
                    }
                }
            } catch (IOException e) {
                return Set.of();
            }
            return candidates;
        }

        private static void indexClass(Index index, ClassTree cls, String fqn, String top, FileScope scope,
                                       Path sourceRoot, Set<Path> pending) {
            String parent = supertypeOf(cls, scope, sourceRoot);
            if (parent != null && index.supertypes.putIfAbsent(fqn, parent) == null) {
                Path parentFile = sourceRoot.resolve(parent.replace('.', '/') + ".java");
                if (Files.isRegularFile(parentFile)) {
                    pending.add(parentFile.toAbsolutePath().normalize());
                }
            }
            for (Tree member : cls.getMembers()) {
                if (member instanceof VariableTree variable) {
                    String name = variable.getName().toString();
                    if (!OBFUSCATED_FIELD.matcher(name).matches()) continue;
                    boolean isStatic = variable.getModifiers().getFlags().contains(Modifier.STATIC);
                    index.fields.computeIfAbsent(name, k -> new ArrayList<>())
                            .add(new Decl(fqn, top, isStatic, false, 0));
                } else if (member instanceof MethodTree method) {
                    String name = method.getName().toString();
                    if (method.getReturnType() == null || !OBFUSCATED_METHOD.matcher(name).matches()) continue;
                    int arity = method.getParameters().size();
                    boolean isStatic = method.getModifiers().getFlags().contains(Modifier.STATIC);
                    boolean varargs = arity > 0 && method.getParameters().get(arity - 1)
                            .getType().toString().endsWith("...");
                    index.methods.computeIfAbsent(name, k -> new ArrayList<>())
                            .add(new Decl(fqn, top, isStatic, varargs, arity));
                } else if (member instanceof ClassTree inner && inner.getSimpleName().length() > 0) {
                    indexClass(index, inner, fqn + "." + inner.getSimpleName(), top, scope, sourceRoot, pending);
                }
            }
        }

        private static String supertypeOf(ClassTree cls, FileScope scope, Path sourceRoot) {
            Tree parent = cls.getExtendsClause();
            if (parent == null) return null;
            String spelling = parent.toString();
            int generic = spelling.indexOf('<');
            if (generic >= 0) spelling = spelling.substring(0, generic);
            spelling = spelling.trim();
            if (spelling.isEmpty() || spelling.indexOf('.') >= 0) {
                return spelling.isEmpty() ? null : spelling;
            }
            String imported = scope.importsBySimpleName().get(spelling);
            if (imported != null) return imported;
            String samePackage = scope.pkg().isEmpty() ? spelling : scope.pkg() + "." + spelling;
            if (Files.isRegularFile(sourceRoot.resolve(samePackage.replace('.', '/') + ".java"))) {
                return samePackage;
            }
            return null;
        }
    }

    static final class CollectionSourceFixer {
        private static final String OBJECT_CAST = "(Object)";
        private static final Pattern FOR_EACH_OVER_OBJECT = Pattern.compile(
                "for-each not applicable.*?required:\\s*array or java\\.lang\\.Iterable.*?"
                        + "found:\\s*(?:java\\.lang\\.)?Object",
                Pattern.DOTALL);
        private static final Pattern UNBOXING_REF = Pattern.compile(
                "invalid method reference.*?method\\s+([A-Za-z_$][\\w$]*)\\s+in class\\s+"
                        + "((?:[A-Za-z_$][\\w$]*\\.)*[A-Za-z_$][\\w$]*)\\s+cannot be applied",
                Pattern.DOTALL);
        private static final Pattern STREAM_CAST = Pattern.compile(
                "incompatible types:\\s*(?:[A-Za-z_$][\\w.$]*\\.)*Stream(?:<[^<>]*>)?\\s+cannot be converted to\\s+([A-Za-z_$][\\w.$]*)");
        private static final Pattern CAST = Pattern.compile(
                "\\(\\s*([A-Za-z_$][\\w.$]*(?:<[^<>]*>)?)\\s*\\)");
        private static final Pattern ELEMENT_DEMAND = Pattern.compile(
                "incompatible types:\\s+(?:java\\.lang\\.)?Object cannot be converted to "
                        + "([A-Za-z_$][\\w$.]*)(?![\\w$.<])");
        private static final Pattern RAW_COLLECTION = Pattern.compile(
                "(?:java\\.util\\.)?(?:List|Collection|Set|Iterable)");
        private static final String MAPPING_CALL = ".mapToInt(";
        private static final String STREAM_CALL = ".stream()";
        private static final List<String> GUARDED_PACKAGES = List.of("java.lang.", "java.util.");
        private static final Set<String> WRAPPERS = Set.of(
                "Boolean", "Byte", "Character", "Double", "Float", "Integer", "Long", "Short");
        private static final Set<String> UNBOXING_METHODS = Set.of(
                "booleanValue", "byteValue", "charValue", "doubleValue",
                "floatValue", "intValue", "longValue", "shortValue");

        record Result(int fixes, List<Diagnostic<? extends JavaFileObject>> handled) {
            List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
                if (handled.isEmpty()) return all;
                return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
            }

            static final Result NONE = new Result(0, List.of());
        }

        private record Splice(int start, int end, String text) {
            String applyTo(String line) {
                return line.substring(0, start) + text + line.substring(end);
            }
        }

        private record Edit(int line, int start, int end, String text) {
            void applyTo(List<String> lines) {
                lines.set(line, lines.get(line).substring(0, start) + text + lines.get(line).substring(end));
            }
        }

        private record Scope(String pkg, Map<String, String> imports, List<String> onDemandPackages) {
            boolean binds(String simple, String fqn) {
                if (fqn.startsWith("java.lang.")) return true;
                String imported = imports.get(simple);
                if (imported != null) return imported.equals(fqn);
                int dot = fqn.lastIndexOf('.');
                if (dot < 0) return true;
                if (fqn.substring(0, dot).equals(pkg)) return true;
                int covering = 0;
                for (String candidate : onDemandPackages) {
                    if (fqn.startsWith(candidate + ".")) covering++;
                }
                return covering == 1;
            }
        }

        private CollectionSourceFixer() {
        }

        static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                if (b.diagnostic().getSource() == null) continue;
                Path file;
                try {
                    file = Path.of(b.diagnostic().getSource().toUri()).toAbsolutePath().normalize();
                } catch (RuntimeException e) {
                    continue;
                }
                if (file.startsWith(sourceRoot.toAbsolutePath().normalize())) {
                    byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(b);
                }
            }
            int fixed = 0;
            List<Diagnostic<? extends JavaFileObject>> handled = new ArrayList<>();
            for (var entry : byFile.entrySet()) {
                List<String> lines;
                try {
                    lines = new ArrayList<>(Files.readAllLines(entry.getKey()));
                } catch (IOException ioe) {
                    continue;
                }
                List<String> code = LambdaRestoreFixer.lexicalLines(lines);
                Scope scope = scopeOf(lines);
                Set<String> seen = new HashSet<>();
                boolean changed = false;
                for (var b : entry.getValue()) {
                    String message = b.diagnostic().getMessage(Locale.ENGLISH);
                    long lineNo = b.diagnostic().getLineNumber();
                    if (lineNo < 1 || lineNo > lines.size()) continue;
                    if (!seen.add(lineNo + "|" + message)) continue;
                    int index = (int) lineNo - 1;
                    List<Edit> edits = rewrite(code, index, message, scope);
                    if (edits.isEmpty()) continue;
                    for (Edit edit : edits) edit.applyTo(lines);
                    code = LambdaRestoreFixer.lexicalLines(lines);
                    changed = true;
                    fixed++;
                    handled.add(b.diagnostic());
                }
                if (changed) Files.write(entry.getKey(), lines);
            }
            return fixed == 0 ? Result.NONE : new Result(fixed, handled);
        }

        private static Scope scopeOf(List<String> lines) {
            String pkg = "";
            Map<String, String> imports = new HashMap<>();
            List<String> onDemand = new ArrayList<>();
            for (String raw : lines) {
                String line = raw.trim();
                if (line.startsWith("package ")) {
                    pkg = line.substring("package ".length()).replace(";", "").trim();
                } else if (line.startsWith("import ") && !line.startsWith("import static ")) {
                    String spelled = line.substring("import ".length()).replace(";", "").trim();
                    if (spelled.endsWith(".*")) {
                        onDemand.add(spelled.substring(0, spelled.length() - ".*".length()));
                    } else {
                        imports.put(simpleName(spelled), spelled);
                    }
                }
            }
            return new Scope(pkg, imports, onDemand);
        }

        private static List<Edit> rewrite(List<String> code, int index, String message, Scope scope) {
            List<Edit> parameterized = parameterizeRawCollectionSource(code, index, message, scope);
            if (!parameterized.isEmpty()) return parameterized;
            String flagged = code.get(index);
            Splice forEach = dropForEachObjectCast(flagged, message);
            if (forEach != null) return List.of(new Edit(index, forEach.start(), forEach.end(), forEach.text()));
            Splice receiver = parameterizeStreamReceiver(flagged, message, scope);
            if (receiver != null) {
                return List.of(new Edit(index, receiver.start(), receiver.end(), receiver.text()));
            }
            Splice cast = retypeStreamCast(flagged, message, scope);
            if (cast != null) return List.of(new Edit(index, cast.start(), cast.end(), cast.text()));
            return List.of();
        }

        private static List<Edit> parameterizeRawCollectionSource(List<String> code, int index,
                                                                   String message, Scope scope) {
            Matcher demand = ELEMENT_DEMAND.matcher(message);
            if (!demand.find()) return List.of();
            String target = demand.group(1).trim();
            if (target.isEmpty()) return List.of();
            String flagged = code.get(index);
            int[] receiver = soleStreamReceiver(flagged);
            if (receiver == null) return List.of();
            String source = flagged.substring(receiver[0], receiver[1]);
            int declaration = rawCollectionLocal(code, index, source);
            if (declaration < 0) return List.of();
            Matcher declared = localDeclaration(source).matcher(code.get(declaration));
            if (!declared.find()) return List.of();
            String spelled = declared.group(1);
            String parameterizedType = spelled + "<" + bindElement(target, scope) + ">";
            if (parameterizedType.equals(spelled)) return List.of();
            if (!soleUseAfterDeclaration(code, declaration, index, source)) return List.of();
            List<Edit> edits = new ArrayList<>();
            edits.add(new Edit(declaration, declared.start(1), declared.end(1), parameterizedType));
            int[] cast = deadElementCast(flagged, receiver[0], target, scope);
            if (cast != null) edits.add(new Edit(index, cast[0], cast[1], ""));
            return edits;
        }

        private static int[] soleStreamReceiver(String code) {
            int at = code.indexOf(STREAM_CALL);
            if (at < 0) return null;
            if (code.indexOf(STREAM_CALL, at + 1) >= 0) return null;
            int end = skipWhitespaceBackwards(code, at - 1);
            if (end < 0 || !Character.isJavaIdentifierPart(code.charAt(end))) return null;
            int start = end;
            while (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) start--;
            if (!Character.isJavaIdentifierStart(code.charAt(start))) return null;
            if (start > 0 && code.charAt(start - 1) == '.') return null;
            return new int[]{start, end + 1};
        }

        private static Pattern localDeclaration(String name) {
            return Pattern.compile("(?<![\\w$.])(?:final\\s+)?"
                    + "([A-Za-z_$][\\w.$]*(?:\\s*<[^<>]*>)?(?:\\s*\\[\\s*\\])*)\\s+"
                    + Pattern.quote(name) + "\\s*(?==)");
        }

        private static int rawCollectionLocal(List<String> code, int flagged, String name) {
            Pattern declaration = localDeclaration(name);
            int depth = depthAtStart(code, flagged);
            int found = -1;
            for (int i = flagged - 1; i >= 0; i--) {
                String line = code.get(i);
                if (atMethodBoundary(line, depth)) break;
                Matcher m = declaration.matcher(line);
                if (m.find()) {
                    if (found >= 0) return -1;
                    if (!RAW_COLLECTION.matcher(m.group(1).replaceAll("\\s+", "")).matches()) return -1;
                    found = i;
                }
                depth -= braceDelta(line);
            }
            return found;
        }

        private static boolean atMethodBoundary(String line, int depth) {
            return depth <= 1;
        }

        private static boolean soleUseAfterDeclaration(List<String> code, int declaration, int flagged,
                                                       String name) {
            Pattern word = Pattern.compile("(?<![\\w$.])" + Pattern.quote(name) + "(?![\\w$])");
            int depth = 0;
            int found = 0;
            for (int i = declaration + 1; i < code.size(); i++) {
                String line = code.get(i);
                Matcher m = word.matcher(line);
                while (m.find()) {
                    found++;
                    if (i != flagged) return false;
                }
                depth += braceDelta(line);
                if (depth < 0) break;
            }
            return found == 1;
        }

        private static int depthAtStart(List<String> code, int line) {
            int depth = 0;
            for (int i = 0; i < line; i++) depth += braceDelta(code.get(i));
            return depth;
        }

        private static int braceDelta(String code) {
            int delta = 0;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '{') delta++;
                else if (c == '}') delta--;
            }
            return delta;
        }

        private static String bindElement(String target, Scope scope) {
            if (!target.contains(".")) return target;
            return scope.binds(simpleName(target), target) ? simpleName(target) : target;
        }

        private static int[] deadElementCast(String code, int receiverStart, String target, Scope scope) {
            int close = skipWhitespaceBackwards(code, receiverStart - 1);
            if (close < 0 || code.charAt(close) != ')') return null;
            int open = matchingParenBackwards(code, close);
            if (open <= 0) return null;
            if (!Character.isWhitespace(code.charAt(open - 1))) return null;
            String spelled = code.substring(open + 1, close).replaceAll("\\s+", "");
            if (!spelled.equals(target) && !spelled.equals(bindElement(target, scope))) return null;
            int end = skipWhitespace(code, close + 1, code.length());
            if (end > receiverStart) return null;
            return new int[]{open, end};
        }

        private static Splice dropForEachObjectCast(String code, String message) {
            if (!FOR_EACH_OVER_OBJECT.matcher(message).find()) return null;
            List<int[]> candidates = new ArrayList<>();
            for (int at = 0; at < code.length(); ) {
                int word = indexOfWordFrom(code, "for", at);
                if (word < 0) break;
                at = word + 3;
                int open = code.indexOf('(', word);
                if (open < 0) continue;
                int close = RawCastFixer.findMatchingParen(code, open);
                if (close < 0) continue;
                String header = code.substring(open + 1, close);
                if (RawCastFixer.topLevelChar(header, ';') >= 0) continue;
                int colon = RawCastFixer.topLevelChar(header, ':');
                if (colon < 0) continue;
                int expr = skipWhitespace(code, open + 1 + colon + 1, close);
                if (!code.startsWith(OBJECT_CAST, expr)) continue;
                int end = expr + OBJECT_CAST.length();
                if (skipWhitespace(code, end, close) >= close) continue;
                candidates.add(new int[]{expr, end});
            }
            if (candidates.size() != 1) return null;
            int[] cast = candidates.get(0);
            return new Splice(cast[0], cast[1], "");
        }

        private static Splice parameterizeStreamReceiver(String code, String message, Scope scope) {
            Matcher reference = UNBOXING_REF.matcher(message);
            if (!reference.find()) return null;
            String name = reference.group(1);
            String owner = reference.group(2);
            if (!UNBOXING_METHODS.contains(name) || !wrapperOwner(owner, scope)) return null;
            String qualifier = code.contains(owner + "::" + name) ? owner : simpleName(owner);
            String reference1 = qualifier + "::" + name;
            int first = code.indexOf(reference1);
            if (first < 0 || code.indexOf(reference1, first + 1) >= 0) return null;
            int mapAt = code.lastIndexOf(MAPPING_CALL, first - 1);
            if (mapAt < 0) return null;
            int streamAt = code.lastIndexOf(".stream()", mapAt);
            if (streamAt < 0) return null;
            if (!code.substring(streamAt + ".stream()".length(), mapAt).isBlank()) return null;
            int receiverEnd = skipWhitespaceBackwards(code, streamAt - 1);
            if (receiverEnd < 0 || code.charAt(receiverEnd) != ')') return null;
            int receiverOpen = matchingParenBackwards(code, receiverEnd);
            if (receiverOpen < 0) return null;
            int castOpen = skipWhitespace(code, receiverOpen + 1, receiverEnd);
            if (castOpen >= receiverEnd || code.charAt(castOpen) != '(') return null;
            int castEnd = RawCastFixer.findMatchingParen(code, castOpen);
            if (castEnd < 0 || castEnd > receiverEnd) return null;
            String type = code.substring(castOpen + 1, castEnd).trim();
            if (!RAW_COLLECTION.matcher(type).matches()) return null;
            if (!bindsToJdkCollection(type, scope)) return null;
            if (skipWhitespace(code, castEnd + 1, receiverEnd) >= receiverEnd) return null;
            String element = elementType(qualifier, owner, scope);
            return new Splice(castOpen, castEnd + 1, "(" + type + "<" + element + ">)");
        }

        private static boolean wrapperOwner(String owner, Scope scope) {
            String simple = simpleName(owner);
            if (!WRAPPERS.contains(simple)) return false;
            String expected = "java.lang." + simple;
            return owner.equals(expected) || (!owner.contains(".") && scope.binds(simple, expected));
        }

        private static boolean bindsToJdkCollection(String type, Scope scope) {
            if (type.contains(".")) return true;
            String simple = simpleName(type);
            String expected = simple.equals("Iterable") ? "java.lang.Iterable" : "java.util." + simple;
            return scope.binds(simple, expected);
        }

        private static String elementType(String qualifier, String owner, Scope scope) {
            if (qualifier.contains(".")) return qualifier;
            if (!owner.contains(".") || scope.binds(qualifier, owner)) return qualifier;
            return owner;
        }

        private static Splice retypeStreamCast(String code, String message, Scope scope) {
            Matcher cast = STREAM_CAST.matcher(message);
            if (!cast.find()) return null;
            String target = cast.group(1).trim();
            if (target.isEmpty() || guardedType(target)) return null;
            List<Splice> candidates = new ArrayList<>();
            Matcher onLine = CAST.matcher(code);
            while (onLine.find()) {
                String type = onLine.group(1);
                if (type.contains("<")) continue;
                if (guardedType(type)) continue;
                if (!bindsToTarget(type, target, scope)) continue;
                if (!feedsAStreamChain(code, onLine.end())) continue;
                candidates.add(new Splice(onLine.start(), onLine.end(),
                        "(java.util.stream.Stream<" + type + ">)"));
            }
            if (candidates.size() != 1) return null;
            return candidates.get(0);
        }

        private static boolean bindsToTarget(String castType, String target, Scope scope) {
            if (castType.contains(".")) return castType.equals(target);
            return scope.binds(castType, target);
        }

        private static boolean guardedType(String type) {
            for (String pkg : GUARDED_PACKAGES) {
                if (type.startsWith(pkg)) return true;
            }
            String simple = simpleName(type);
            return simple.equals("Stream") || simple.equals("Object");
        }

        private static boolean feedsAStreamChain(String code, int after) {
            for (int i = after; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == ';' || c == '+') return code.substring(after, i).contains(".stream");
            }
            return code.substring(after).contains(".stream");
        }

        private static int indexOfWordFrom(String code, String word, int from) {
            int at = from;
            while (at >= 0) {
                at = code.indexOf(word, at);
                if (at < 0) return -1;
                boolean beforeOk = at == 0 || !Character.isJavaIdentifierPart(code.charAt(at - 1));
                int end = at + word.length();
                boolean afterOk = end >= code.length() || !Character.isJavaIdentifierPart(code.charAt(end));
                if (beforeOk && afterOk) return at;
                at = at + 1;
            }
            return -1;
        }

        private static String simpleName(String type) {
            int dot = type.lastIndexOf('.');
            return dot < 0 ? type : type.substring(dot + 1);
        }

        private static int skipWhitespace(String code, int from, int limit) {
            int i = from;
            while (i < limit && Character.isWhitespace(code.charAt(i))) i++;
            return i;
        }

        private static int skipWhitespaceBackwards(String code, int from) {
            int i = from;
            while (i >= 0 && Character.isWhitespace(code.charAt(i))) i--;
            return i;
        }

        private static int matchingParenBackwards(String code, int close) {
            int depth = 0;
            for (int i = close; i >= 0; i--) {
                char c = code.charAt(i);
                if (c == ')') depth++;
                else if (c == '(') {
                    depth--;
                    if (depth == 0) return i;
                }
            }
            return -1;
        }
    }

    static final class DuplicateLocalFixer {
        private static final Pattern ALREADY_DEFINED = Pattern.compile(
                "variable\\s+([A-Za-z_$][\\w$]*)\\s+is\\s+already\\s+defined\\s+in\\s+method\\b");
        private static final Set<String> SCOPE_OWNING = Set.of(
                "for", "if", "while", "do", "else", "switch", "try", "catch", "finally",
                "synchronized", "case", "default");
        private static final List<String> NOT_TYPES = List.of(
                "return", "throw", "yield", "assert", "else", "do", "case", "new",
                "this", "super", "null", "true", "false", "instanceof");

        record Result(int fixes, List<Diagnostic<? extends JavaFileObject>> handled) {
            List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
                if (handled.isEmpty()) return all;
                return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
            }

            static final Result NONE = new Result(0, List.of());
        }

        private DuplicateLocalFixer() {
        }

        static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                if (b.category() != DiagnosticBucketer.Category.DUPLICATE_METHOD
                        || b.diagnostic().getSource() == null) continue;
                if (!ALREADY_DEFINED.matcher(b.diagnostic().getMessage(Locale.ENGLISH)).find()) continue;
                Path file;
                try {
                    file = Path.of(b.diagnostic().getSource().toUri()).toAbsolutePath().normalize();
                } catch (RuntimeException e) {
                    continue;
                }
                if (file.startsWith(sourceRoot.toAbsolutePath().normalize())) {
                    byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(b);
                }
            }
            int fixed = 0;
            List<Diagnostic<? extends JavaFileObject>> handled = new ArrayList<>();
            for (var entry : byFile.entrySet()) {
                List<String> lines;
                try {
                    lines = new ArrayList<>(Files.readAllLines(entry.getKey()));
                } catch (IOException ioe) {
                    continue;
                }
                List<String> masked = LambdaRestoreFixer.lexicalLines(lines);
                int[] depths = LambdaRestoreFixer.depths(masked);
                Set<String> seen = new HashSet<>();
                boolean changed = false;
                for (var b : entry.getValue()) {
                    String message = b.diagnostic().getMessage(Locale.ENGLISH);
                    long lineNo = b.diagnostic().getLineNumber();
                    if (lineNo < 1 || lineNo > lines.size()) continue;
                    if (!seen.add(lineNo + "|" + message)) continue;
                    Matcher matcher = ALREADY_DEFINED.matcher(message);
                    if (!matcher.find()) continue;
                    int declaration = (int) lineNo - 1;
                    if (!rename(lines, masked, depths, declaration, matcher.group(1))) continue;
                    masked = LambdaRestoreFixer.lexicalLines(lines);
                    depths = LambdaRestoreFixer.depths(masked);
                    changed = true;
                    fixed++;
                    handled.add(b.diagnostic());
                }
                if (changed) Files.write(entry.getKey(), lines);
            }
            return fixed == 0 ? Result.NONE : new Result(fixed, handled);
        }

        private static boolean rename(List<String> lines, List<String> masked, int[] depths,
                                      int declaration, String name) {
            Declaration found = declarationAt(masked.get(declaration), name);
            if (found == null) return false;
            int at = found.index();
            int method = methodOf(masked, depths, declaration);
            if (method < 0) return false;
            int methodEnd = endOfBlock(masked, depths, method);
            if (methodEnd < 0) return false;
            int[] region = region(masked, depths, declaration);
            if (region == null) return false;
            int start = region[0];
            int end = region[1];
            if (end < declaration || end > methodEnd) return false;
            if (ambiguous(masked, depths, start, end, declaration, at, name)) return false;
            String fresh = freshName(masked, method, methodEnd, name);
            if (fresh == null) return false;
            for (int i = declaration; i <= end; i++) {
                lines.set(i, replaceStandalone(lines.get(i), masked.get(i), name, fresh));
            }
            return true;
        }

        private static boolean ambiguous(List<String> masked, int[] depths, int start, int end,
                                         int declaration, int at, String name) {
            int declared = depthAt(masked, depths, declaration, at);
            int sameDepth = 0;
            for (int i = start; i <= end; i++) {
                if (i == start || i == declaration) continue;
                Declaration other = declarationAt(masked.get(i), name);
                if (other == null) continue;
                if (other.shape() == DeclarationShape.LAMBDA_PARAMETER) return true;
                if (depthAt(masked, depths, i, other.index()) > declared) return true;
                if (++sameDepth > 1) return true;
            }
            return false;
        }

        private static int depthAt(List<String> masked, int[] depths, int line, int index) {
            String code = masked.get(line);
            int depth = depths[line];
            for (int i = 0; i < index && i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '{') depth++;
                else if (c == '}') depth--;
            }
            return depth;
        }

        private static int[] region(List<String> masked, int[] depths, int declaration) {
            String code = masked.get(declaration).strip();
            boolean opensBlock = code.endsWith("{")
                    && lastTopLevelEquals(code.substring(0, code.length() - 1)) < 0;
            if (opensBlock) {
                int end = endOfBlock(masked, depths, declaration);
                return end < 0 ? null : new int[]{declaration, end};
            }
            if (code.contains("->")) return null;
            if (opensNestedScope(code)) {
                int end = statementEnd(masked, depths, declaration);
                return end < 0 ? null : new int[]{declaration, end};
            }
            int start = enclosingOpener(masked, depths, declaration);
            if (start < 0) return null;
            int end = endOfBlock(masked, depths, start);
            return end < 0 ? null : new int[]{start, end};
        }

        private static boolean opensNestedScope(String code) {
            int end = 0;
            while (end < code.length() && Character.isJavaIdentifierPart(code.charAt(end))) end++;
            if (end == 0) return code.startsWith("->");
            return SCOPE_OWNING.contains(code.substring(0, end));
        }

        private static int statementEnd(List<String> masked, int[] depths, int declaration) {
            int target = depths[declaration];
            int paren = 0;
            int bracket = 0;
            for (int i = declaration; i < masked.size(); i++) {
                String code = masked.get(i);
                int brace = depths[i];
                for (int j = 0; j < code.length(); j++) {
                    char c = code.charAt(j);
                    if (c == '(') paren++;
                    else if (c == ')') paren--;
                    else if (c == '[') bracket++;
                    else if (c == ']') bracket--;
                    else if (c == '{') brace++;
                    else if (c == '}') brace--;
                    else if (c == ';' && paren == 0 && bracket == 0 && brace == target) {
                        return i;
                    }
                    if (i == declaration && brace > target) return -1;
                }
                if (i + 1 < masked.size() && depths[i + 1] < target) return -1;
            }
            return -1;
        }

        private static int methodOf(List<String> masked, int[] depths, int line) {
            int found = -1;
            for (int i = 0; i <= line && i < masked.size(); i++) {
                if (!LambdaRestoreFixer.METHOD_HEADER.matcher(masked.get(i)).matches()) continue;
                if (endOfBlock(masked, depths, i) < line) continue;
                found = i;
            }
            return found;
        }

        private static int endOfBlock(List<String> masked, int[] depths, int openLine) {
            int end = CompileFixLoop.methodEnd(masked, openLine, masked);
            if (end > openLine) return end;
            int start = depths[openLine];
            for (int i = openLine + 1; i < masked.size(); i++) {
                if (depths[i] + LambdaRestoreFixer.braceDelta(masked.get(i)) == start) return i;
            }
            return -1;
        }

        private static int enclosingOpener(List<String> masked, int[] depths, int line) {
            int depth = depths[line];
            for (int i = line - 1; i >= 0; i--) {
                if (depths[i] >= depth) continue;
                if (depths[i] + LambdaRestoreFixer.braceDelta(masked.get(i)) == depth) return i;
            }
            return -1;
        }

        private enum DeclarationShape {
            LOCAL, LAMBDA_PARAMETER
        }

        private record Declaration(int index, DeclarationShape shape) {}

        private static Declaration declarationAt(String maskedLine, String name) {
            Matcher matcher = standalone(name).matcher(maskedLine);
            while (matcher.find()) {
                if (isLambdaParameter(maskedLine, matcher.start(), matcher.end())) {
                    return new Declaration(matcher.start(), DeclarationShape.LAMBDA_PARAMETER);
                }
                String before = maskedLine.substring(0, matcher.start()).stripTrailing();
                if (before.isEmpty() || !isTypeEnd(before.charAt(before.length() - 1))) continue;
                if (endsWithWord(before, name)) continue;
                if (!precedesType(before)) continue;
                if (startsDeclaration(maskedLine.substring(matcher.end()))) {
                    return new Declaration(matcher.start(), DeclarationShape.LOCAL);
                }
            }
            return null;
        }

        private static boolean isLambdaParameter(String maskedLine, int start, int end) {
            int open = start - 1;
            while (open >= 0 && Character.isWhitespace(maskedLine.charAt(open))) open--;
            if (open < 0) return false;
            char before = maskedLine.charAt(open);
            if (before != '(' && before != ',') return false;
            int i = skipTo(maskedLine, end);
            if (i >= maskedLine.length()) return false;
            if (maskedLine.startsWith("->", i)) return true;
            if (maskedLine.charAt(i) != ')') return false;
            i = skipTo(maskedLine, i + 1);
            return i < maskedLine.length() && maskedLine.startsWith("->", i);
        }

        private static int skipTo(String text, int from) {
            int i = from;
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) i++;
            return i;
        }

        private static boolean precedesType(String before) {
            for (String keyword : NOT_TYPES) {
                if (endsWithWord(before, keyword)) return false;
            }
            return true;
        }

        private static boolean isTypeEnd(char c) {
            return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.'
                    || c == '>' || c == ']';
        }

        private static boolean endsWithWord(String text, String word) {
            int at = text.length() - word.length();
            if (at < 0 || !text.startsWith(word, at)) return false;
            return at == 0 || !Character.isJavaIdentifierPart(text.charAt(at - 1));
        }

        private static boolean startsDeclaration(String after) {
            int i = 0;
            while (i < after.length() && Character.isWhitespace(after.charAt(i))) i++;
            if (i >= after.length()) return false;
            char c = after.charAt(i);
            if (c == '=') {
                int j = i;
                while (j < after.length() && after.charAt(j) == '=') j++;
                return j - i == 1;
            }
            return c == ';' || c == '[' || c == ',' || c == ':';
        }

        private static String freshName(List<String> masked, int method, int methodEnd, String name) {
            for (int n = 2; n < 1000; n++) {
                String candidate = name + "_" + n;
                Pattern pattern = standalone(candidate);
                boolean taken = false;
                for (int i = method; i <= methodEnd && !taken; i++) {
                    taken = pattern.matcher(masked.get(i)).find();
                }
                if (!taken) return candidate;
            }
            return null;
        }

        private static Pattern standalone(String name) {
            return Pattern.compile("(?<![A-Za-z0-9_$.])" + Pattern.quote(name)
                    + "(?![A-Za-z0-9_$])");
        }

        private static String replaceStandalone(String line, String maskedLine, String name, String fresh) {
            if (line.length() != maskedLine.length()) return line;
            Matcher matcher = standalone(name).matcher(maskedLine);
            StringBuilder rewritten = new StringBuilder();
            int copied = 0;
            while (matcher.find()) {
                rewritten.append(line, copied, matcher.start()).append(fresh);
                copied = matcher.end();
            }
            if (copied == 0) return line;
            return rewritten.append(line, copied, line.length()).toString();
        }
    }

    static final class ResidualSyntaxFixer {
        private static final Pattern LOSSY_CONVERSION = Pattern.compile(
                "incompatible types: possible lossy conversion from [\\w.$]+ to (byte|short|char)");
        private static final Pattern ASSIGNMENT_TARGET = Pattern.compile(
                "[\\w.$]+(?:\\s*\\[[^\\[\\]]*])*");
        private static final Pattern LEADING_CAST = Pattern.compile(
                "^\\(\\s*[\\w.$]+(?:\\s*<[^<>]*>)?(?:\\s*\\[\\s*])*\\s*\\)");
        private static final String COMPOUND_TAIL = "=!<>+-*/%&|^";

        record Result(int fixes, List<Diagnostic<? extends JavaFileObject>> handled) {
            List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
                if (handled.isEmpty()) return all;
                return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
            }

            static final Result NONE = new Result(0, List.of());
        }

        private ResidualSyntaxFixer() {
        }

        static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                if (b.diagnostic().getSource() == null) continue;
                Path file;
                try {
                    file = Path.of(b.diagnostic().getSource().toUri()).toAbsolutePath().normalize();
                } catch (RuntimeException e) {
                    continue;
                }
                if (!file.startsWith(sourceRoot.toAbsolutePath().normalize())) continue;
                byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(b);
            }
            int fixed = 0;
            List<Diagnostic<? extends JavaFileObject>> handled = new ArrayList<>();
            for (var entry : byFile.entrySet()) {
                List<String> lines;
                try {
                    lines = new ArrayList<>(Files.readAllLines(entry.getKey()));
                } catch (IOException ioe) {
                    continue;
                }
                List<String> code = LambdaRestoreFixer.lexicalLines(lines);
                Set<String> seen = new HashSet<>();
                boolean changed = false;
                for (var b : entry.getValue()) {
                    String message = b.diagnostic().getMessage(Locale.ENGLISH);
                    long lineNo = b.diagnostic().getLineNumber();
                    if (lineNo < 1 || lineNo > lines.size()) continue;
                    if (!seen.add(lineNo + "|" + message)) continue;
                    int index = (int) lineNo - 1;
                    String rewritten = rewrite(lines.get(index), code.get(index), message);
                    if (rewritten == null) continue;
                    lines.set(index, rewritten);
                    code = LambdaRestoreFixer.lexicalLines(lines);
                    changed = true;
                    fixed++;
                    handled.add(b.diagnostic());
                }
                if (changed) Files.write(entry.getKey(), lines);
            }
            return fixed == 0 ? Result.NONE : new Result(fixed, handled);
        }

        private static String rewrite(String line, String code, String message) {
            Matcher lossy = LOSSY_CONVERSION.matcher(message);
            if (lossy.find()) return narrow(line, code, lossy.group(1));
            return null;
        }

        private static String narrow(String line, String code, String type) {
            int equals = lastTopLevelEquals(code);
            if (equals < 0) return null;
            if (equals > 0 && COMPOUND_TAIL.indexOf(code.charAt(equals - 1)) >= 0) return null;
            if (!code.trim().endsWith(";")) return null;
            if (!ASSIGNMENT_TARGET.matcher(code.substring(0, equals).trim()).matches()) return null;
            int start = equals + 1;
            while (start < line.length() && line.charAt(start) == ' ') start++;
            if (start >= line.length()) return null;
            if (LEADING_CAST.matcher(code.substring(start)).find()) return null;
            int end = code.length();
            while (end > start && Character.isWhitespace(code.charAt(end - 1))) end--;
            if (end <= start || code.charAt(end - 1) != ';') return null;
            end--;
            while (end > start && code.charAt(end - 1) == ' ') end--;
            if (end <= start) return null;
            String right = fullyWrapped(code, start, end) ? line.substring(start, end)
                    : "(" + line.substring(start, end) + ")";
            return line.substring(0, start) + "(" + type + ") " + right + line.substring(end);
        }

        private static boolean fullyWrapped(String code, int start, int end) {
            if (code.charAt(start) != '(') return false;
            int depth = 0;
            for (int i = start; i < end; i++) {
                char c = code.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') {
                    depth--;
                    if (depth == 0) return i == end - 1;
                }
            }
            return false;
        }
    }

    static final class InferredTypeFixer {
        private static final Pattern OPERAND_MISMATCH = Pattern.compile(
                "bad operand types for binary operator '(!=|==)'\\s+first type:\\s+"
                        + "(?:java\\.lang\\.)?Object\\s+second type:\\s+([\\w$]+)(?=\\s|$)",
                Pattern.DOTALL);
        private static final Set<String> PRIMITIVES = Set.of(
                "boolean", "byte", "char", "short", "int", "long", "float", "double");
        private static final Set<String> OBJECT_SPELLINGS = Set.of("Object", "java.lang.Object");
        private static final Set<String> PRIMITIVE_ARRAY_PRODUCERS = Set.of("char", "byte");
        private static final Pattern FINAL = Pattern.compile("final\\s+");
        private static final Pattern TO_ARRAY_CALL = Pattern.compile("to([A-Za-z]+)Array");
        private static final Pattern DOTTED_RECEIVER = Pattern.compile(
                "[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*(?:\\(\\))?)*(?:\\(\\))?");

        record Result(int fixes, List<Diagnostic<? extends JavaFileObject>> handled) {
            List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
                if (handled.isEmpty()) return all;
                return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
            }

            static final Result NONE = new Result(0, List.of());
        }

        private record Splice(int line, int start, int end, String text) {
            void applyTo(List<String> lines) {
                String target = lines.get(line);
                lines.set(line, target.substring(0, start) + text + target.substring(end));
            }
        }

        private InferredTypeFixer() {
        }

        static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                if (b.diagnostic().getSource() == null) continue;
                Path file;
                try {
                    file = Path.of(b.diagnostic().getSource().toUri()).toAbsolutePath().normalize();
                } catch (RuntimeException e) {
                    continue;
                }
                if (!file.startsWith(sourceRoot.toAbsolutePath().normalize())) continue;
                byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(b);
            }
            int fixed = 0;
            List<Diagnostic<? extends JavaFileObject>> handled = new ArrayList<>();
            for (var entry : byFile.entrySet()) {
                List<String> lines;
                try {
                    lines = new ArrayList<>(Files.readAllLines(entry.getKey()));
                } catch (IOException ioe) {
                    continue;
                }
                List<String> code = LambdaRestoreFixer.lexicalLines(lines);
                Set<String> seen = new HashSet<>();
                boolean changed = false;
                for (var b : entry.getValue()) {
                    String message = b.diagnostic().getMessage(Locale.ENGLISH);
                    long lineNo = b.diagnostic().getLineNumber();
                    if (lineNo < 1 || lineNo > lines.size()) continue;
                    if (!seen.add(lineNo + "|" + message)) continue;
                    int index = (int) lineNo - 1;
                    Splice splice = retypeForEachVariable(code, index, message);
                    if (splice == null) continue;
                    splice.applyTo(lines);
                    code = LambdaRestoreFixer.lexicalLines(lines);
                    changed = true;
                    fixed++;
                    handled.add(b.diagnostic());
                }
                if (changed) Files.write(entry.getKey(), lines);
            }
            return fixed == 0 ? Result.NONE : new Result(fixed, handled);
        }

        private static Splice retypeForEachVariable(List<String> code, int index, String message) {
            Matcher mismatch = OPERAND_MISMATCH.matcher(message);
            if (!mismatch.find()) return null;
            String second = mismatch.group(2);
            if (!PRIMITIVES.contains(second)) return null;
            String flagged = code.get(index);
            int operator = soleEqualityOperand(flagged);
            if (operator < 0) return null;
            int[] left = bareIdentifierBefore(flagged, operator - 1);
            if (left == null) return null;
            if (bareIdentifierAfter(flagged, operator) == null) return null;
            String ident = flagged.substring(left[0], left[1]);
            if (countWords(flagged, ident) != 1) return null;
            int header = enclosingForEachHeader(code, index, ident);
            if (header < 0) return null;
            int[] type = forEachTypeToken(code.get(header), ident);
            if (type == null) return null;
            if (!OBJECT_SPELLINGS.contains(code.get(header).substring(type[0], type[1]))) return null;
            int bodyEnd = loopBodyEnd(code, header, index);
            if (bodyEnd < 0) return null;
            if (mentionsElsewhere(code, header, index, bodyEnd, ident)) return null;
            if (!provablePrimitiveIterable(code, header, second)) return null;
            return new Splice(header, type[0], type[1], second);
        }

        private static boolean mentionsElsewhere(List<String> code, int header, int flagged, int bodyEnd,
                                                  String ident) {
            Pattern word = Pattern.compile("(?<![\\w$.])" + Pattern.quote(ident) + "(?![\\w$])");
            for (int i = header + 1; i <= bodyEnd; i++) {
                if (i == flagged) continue;
                if (word.matcher(code.get(i)).find()) return true;
            }
            return false;
        }

        private static boolean provablePrimitiveIterable(List<String> code, int header, String primitive) {
            String iterable = forEachIterable(code.get(header));
            if (iterable == null || iterable.isEmpty()) return false;
            if (iterable.endsWith("()")) {
                int dot = iterable.lastIndexOf('.');
                if (dot <= 0) return false;
                if (!DOTTED_RECEIVER.matcher(iterable.substring(0, dot)).matches()) return false;
                Matcher call = TO_ARRAY_CALL.matcher(iterable.substring(dot + 1, iterable.length() - 2));
                return PRIMITIVE_ARRAY_PRODUCERS.contains(primitive)
                        && call.matches()
                        && call.group(1).equalsIgnoreCase(primitive);
            }
            if (!iterable.matches("[A-Za-z_$][\\w$]*")) return false;
            return soleArrayDeclaration(code, header, iterable, primitive);
        }

        private static boolean soleArrayDeclaration(List<String> code, int header, String name, String primitive) {
            Pattern declaration = Pattern.compile("(?<![\\w$.])(?:final\\s+)?" + Pattern.quote(primitive)
                    + "\\s*\\[\\s*\\]\\s+" + Pattern.quote(name) + "(?![\\w$])");
            int found = 0;
            for (int i = header; i >= 0; i--) {
                if (declaration.matcher(code.get(i)).find()) found++;
                if (METHOD_HEADER.matcher(code.get(i)).matches()) break;
            }
            return found == 1;
        }

        private static String forEachIterable(String code) {
            int w = indexOfWord(code, "for", 0);
            if (w < 0) return null;
            int open = code.indexOf('(', w);
            if (open < 0) return null;
            int close = RawCastFixer.findMatchingParen(code, open);
            if (close < 0) return null;
            String head = code.substring(open + 1, close);
            int colon = RawCastFixer.topLevelChar(head, ':');
            if (colon < 0) return null;
            return head.substring(colon + 1).trim();
        }

        private static int enclosingForEachHeader(List<String> code, int flagged, String ident) {
            for (int i = flagged - 1; i >= 0; i--) {
                String line = code.get(i);
                if (METHOD_HEADER.matcher(line).matches()) return -1;
                if (!declaresForEachVariable(line, ident)) continue;
                if (countWords(line, "for") != 1) return -1;
                if (redeclaredBetween(code, i, flagged, ident)) return -1;
                if (loopBodyEnd(code, i, flagged) < 0) return -1;
                return i;
            }
            return -1;
        }

        private static boolean redeclaredBetween(List<String> code, int header, int flagged, String ident) {
            for (int i = header + 1; i < flagged; i++) {
                String line = code.get(i);
                if (declaresForEachVariable(line, ident)) return true;
                if (Pattern.compile("(?<![\\w$.])(?:final\\s+)?[\\w.$<>\\[\\]]+\\s+"
                                + Pattern.quote(ident) + "\\s*(?=[=;,\\[:)])")
                        .matcher(line).find()) return true;
            }
            return false;
        }

        private static int loopBodyEnd(List<String> code, int header, int flagged) {
            String first = code.get(header);
            int open = first.indexOf('{');
            if (open < 0) return -1;
            int depth = 0;
            for (int i = header; i <= flagged; i++) {
                String line = code.get(i);
                int from = i == header ? open : 0;
                for (int j = from; j < line.length(); j++) {
                    char c = line.charAt(j);
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) return -1;
                    }
                }
            }
            for (int i = flagged + 1; i < code.size(); i++) {
                for (int j = 0; j < code.get(i).length(); j++) {
                    char c = code.get(i).charAt(j);
                    if (c == '{') depth++;
                    else if (c == '}') {
                        depth--;
                        if (depth == 0) return i;
                    }
                }
            }
            return -1;
        }

        private static boolean declaresForEachVariable(String code, String ident) {
            for (int w = indexOfWord(code, "for", 0); w >= 0; w = indexOfWord(code, "for", w + 3)) {
                int open = code.indexOf('(', w);
                if (open < 0) return false;
                int close = RawCastFixer.findMatchingParen(code, open);
                if (close < 0) return false;
                String head = code.substring(open + 1, close);
                int colon = RawCastFixer.topLevelChar(head, ':');
                if (colon < 0) continue;
                if (declaredTypeAtEnd(code, open + 1, open + 1 + colon, ident) != null) return true;
            }
            return false;
        }

        private static int[] forEachTypeToken(String code, String ident) {
            int w = indexOfWord(code, "for", 0);
            if (w < 0) return null;
            int open = code.indexOf('(', w);
            if (open < 0) return null;
            int close = RawCastFixer.findMatchingParen(code, open);
            if (close < 0) return null;
            String head = code.substring(open + 1, close);
            int colon = RawCastFixer.topLevelChar(head, ':');
            if (colon < 0) return null;
            return declaredTypeAtEnd(code, open + 1, open + 1 + colon, ident);
        }

        private static int[] declaredTypeAtEnd(String code, int from, int to, String ident) {
            int nameEnd = skipSpacesBackwards(code, to - 1, from);
            if (nameEnd < from) return null;
            if (!Character.isJavaIdentifierPart(code.charAt(nameEnd))) return null;
            int nameStart = nameEnd;
            while (nameStart > from && Character.isJavaIdentifierPart(code.charAt(nameStart - 1))) nameStart--;
            if (!code.substring(nameStart, nameEnd + 1).equals(ident)) return null;
            int typeEnd = skipSpacesBackwards(code, nameStart - 1, from);
            if (typeEnd < from) return null;
            int typeStart = typeEnd;
            while (typeStart > from && isTypeChar(code.charAt(typeStart - 1))) typeStart--;
            while (typeStart < typeEnd && isSpace(code.charAt(typeStart))) typeStart++;
            Matcher modifier = FINAL.matcher(code);
            modifier.region(typeStart, typeEnd + 1);
            if (modifier.lookingAt()) typeStart = modifier.end();
            if (typeStart > typeEnd) return null;
            if (!Character.isJavaIdentifierStart(code.charAt(typeStart))) return null;
            return new int[]{typeStart, typeEnd + 1};
        }

        private static boolean isTypeChar(char c) {
            return Character.isJavaIdentifierPart(c) || c == '.' || c == '<' || c == '>'
                    || c == '[' || c == ']' || c == ' ' || c == ',';
        }

        private static int soleEqualityOperand(String code) {
            int best = -1;
            int bestDepth = Integer.MAX_VALUE;
            int ties = 0;
            int depth = 0;
            for (int i = 0; i + 1 < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '(') {
                    depth++;
                    continue;
                }
                if (c == ')') {
                    depth--;
                    continue;
                }
                if (code.charAt(i + 1) != '=') continue;
                if (c != '=' && c != '!') continue;
                if (i > 0 && "=!<>&|".indexOf(code.charAt(i - 1)) >= 0) continue;
                if (bareIdentifierBefore(code, i - 1) == null) continue;
                if (bareIdentifierAfter(code, i) == null) continue;
                if (depth < bestDepth) {
                    bestDepth = depth;
                    best = i;
                    ties = 1;
                } else if (depth == bestDepth) {
                    ties++;
                }
            }
            return ties == 1 ? best : -1;
        }

        private static int[] bareIdentifierBefore(String code, int operator) {
            int nameEnd = skipSpacesBackwards(code, operator);
            if (nameEnd < 0 || !Character.isJavaIdentifierPart(code.charAt(nameEnd))) return null;
            int nameStart = nameEnd;
            while (nameStart > 0 && Character.isJavaIdentifierPart(code.charAt(nameStart - 1))) nameStart--;
            if (nameStart > 0) {
                char before = code.charAt(nameStart - 1);
                if (Character.isJavaIdentifierPart(before) || before == '.') return null;
            }
            if (!Character.isJavaIdentifierStart(code.charAt(nameStart))) return null;
            return new int[]{nameStart, nameEnd + 1};
        }

        private static int[] bareIdentifierAfter(String code, int operator) {
            int nameStart = skipSpaces(code, operator + 2, code.length());
            if (nameStart >= code.length() || !Character.isJavaIdentifierStart(code.charAt(nameStart))) return null;
            int nameEnd = identifierEnd(code, nameStart);
            if (nameEnd < 0) return null;
            if (nameEnd < code.length()) {
                char after = code.charAt(nameEnd);
                if (Character.isJavaIdentifierPart(after) || after == '.' || after == '[' || after == '(') return null;
            }
            return new int[]{nameStart, nameEnd};
        }

        private static int identifierEnd(String code, int start) {
            int i = start;
            while (i < code.length() && Character.isJavaIdentifierPart(code.charAt(i))) i++;
            return i;
        }

        private static int skipSpaces(String code, int from, int limit) {
            int i = from;
            while (i < limit && isSpace(code.charAt(i))) i++;
            return i;
        }

        private static int skipSpacesBackwards(String code, int from) {
            return skipSpacesBackwards(code, from, 0);
        }

        private static int skipSpacesBackwards(String code, int from, int limit) {
            int i = from;
            while (i >= limit && isSpace(code.charAt(i))) i--;
            return i;
        }

        private static boolean isSpace(char c) {
            return c == ' ' || c == '\t';
        }

        private static int indexOfWord(String code, String word, int from) {
            int at = from;
            while (at >= 0) {
                at = code.indexOf(word, at);
                if (at < 0) return -1;
                boolean beforeOk = at == 0 || !Character.isJavaIdentifierPart(code.charAt(at - 1));
                int end = at + word.length();
                boolean afterOk = end >= code.length() || !Character.isJavaIdentifierPart(code.charAt(end));
                if (beforeOk && afterOk) return at;
                at = at + 1;
            }
            return -1;
        }

        private static int countWords(String code, String word) {
            int count = 0;
            for (int at = indexOfWord(code, word, 0); at >= 0; at = indexOfWord(code, word, at + 1)) count++;
            return count;
        }
    }

    static final class VoidAbstractStubFixer {
        private static final Pattern MISSING_OVERRIDE = Pattern.compile(
                "^(?:[\\w.$]+\\.)?(\\w+) is not abstract and does not override abstract method "
                        + "(\\w+)\\(([^)]*)\\) in ((?:[\\w.$]+\\.)?[\\w$]+);?$");
        private static final Pattern CLASS_DECLARATION = Pattern.compile(
                "^\\s*(?:(?:public|protected|private|static|final|abstract|strictfp)\\s+)*class\\s+(\\w+)\\b");
        private static final Pattern EXTENDS_CLAUSE = Pattern.compile(
                "\\bextends\\s+(?:[\\w.$]+\\.)?(\\w+)\\b");
        private static final Pattern ABSTRACT_METHOD = Pattern.compile(
                "^\\s*(?:(?:public|protected|private|static|final)\\s+)*abstract\\s+"
                        + "([\\w.$<>\\[\\]]+)\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w., $]+)?;\\s*$");
        private static final Pattern MEMBER_DECLARATION = Pattern.compile(
                "^\\s*(?:(?:public|protected|private|static|final|synchronized|abstract|native"
                        + "|transient|volatile|strictfp)\\s+)*[\\w.$<>\\[\\]]+\\s+\\w+\\s*[;(=(]");
        private static final Pattern TYPE_DECLARATION = Pattern.compile(
                "\\b(?:class|interface|enum|record)\\s+(\\w+)\\b");
        private static final Set<String> PRIMITIVES = Set.of(
                "boolean", "byte", "char", "short", "int", "long", "float", "double");
        private static final Set<String> JAVA_LANG = Set.of(
                "Object", "String", "Integer", "Long", "Double", "Float", "Short", "Byte",
                "Character", "Boolean", "Number", "CharSequence", "Comparable",
                "Iterable", "Runnable", "Thread", "Throwable", "Exception", "RuntimeException",
                "Error", "Class", "Void", "Math", "System", "StringBuilder", "StringBuffer",
                "Appendable", "AutoCloseable", "Cloneable", "Serializable", "Enum", "Record",
                "Override", "IllegalArgumentException", "IllegalStateException",
                "CloneNotSupportedException", "NullPointerException", "NumberFormatException",
                "UnsupportedOperationException", "IndexOutOfBoundsException",
                "InterruptedException", "NoSuchFieldException", "NoSuchMethodException",
                "SecurityException", "StackOverflowError", "AssertionError", "OutOfMemoryError");

        record Result(int fixes, List<Diagnostic<? extends JavaFileObject>> handled) {
            List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
                if (handled.isEmpty()) return all;
                return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
            }

            static final Result NONE = new Result(0, List.of());
        }

        private record Insertion(int at, List<String> stub) {}

        private VoidAbstractStubFixer() {
        }

        static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
            Map<Path, List<DiagnosticBucketer.Bucketed>> byFile = new LinkedHashMap<>();
            for (var b : bucketed) {
                if (b.diagnostic().getSource() == null) continue;
                Path file;
                try {
                    file = Path.of(b.diagnostic().getSource().toUri()).toAbsolutePath().normalize();
                } catch (RuntimeException e) {
                    continue;
                }
                if (!file.startsWith(sourceRoot.toAbsolutePath().normalize())) continue;
                byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(b);
            }
            int fixed = 0;
            List<Diagnostic<? extends JavaFileObject>> handled = new ArrayList<>();
            for (var entry : byFile.entrySet()) {
                List<String> lines;
                try {
                    lines = new ArrayList<>(Files.readAllLines(entry.getKey()));
                } catch (IOException ioe) {
                    continue;
                }
                List<String> code = LambdaRestoreFixer.lexicalLines(lines);
                Set<String> seen = new HashSet<>();
                boolean changed = false;
                for (var b : entry.getValue()) {
                    Matcher m = MISSING_OVERRIDE.matcher(b.diagnostic().getMessage(Locale.ENGLISH).strip());
                    if (!m.matches()) continue;
                    if (!seen.add(m.group(1) + "/" + m.group(2) + "/" + m.group(3))) continue;
                    Insertion insertion;
                    try {
                        insertion = insertion(lines, code, sourceRoot, entry.getKey(), m);
                    } catch (IOException ioe) {
                        continue;
                    }
                    if (insertion == null) continue;
                    lines.addAll(insertion.at(), insertion.stub());
                    code = LambdaRestoreFixer.lexicalLines(lines);
                    changed = true;
                    fixed++;
                    handled.add(b.diagnostic());
                }
                if (changed) Files.write(entry.getKey(), lines);
            }
            return fixed == 0 ? Result.NONE : new Result(fixed, handled);
        }

        private static Insertion insertion(List<String> lines, List<String> code, Path sourceRoot,
                                           Path file, Matcher m) throws IOException {
            String owner = m.group(4);
            int declaration = classDeclaration(code, m.group(1));
            if (declaration < 0) return null;
            Matcher extendsClause = EXTENDS_CLAUSE.matcher(code.get(declaration));
            if (!extendsClause.find() || !extendsClause.group(1).equals(simpleName(owner))) return null;
            int close = classBodyEnd(code, declaration);
            if (close < 0) return null;
            if (declaresMethod(code, declaration, close, m.group(2))) return null;
            String parameters = abstractParameters(sourceRoot, file, owner, m.group(2),
                    paramCount(m.group(3)));
            if (parameters == null) return null;
            if (!typesVisible(sourceRoot, file, code, owner, parameters)) return null;
            String indent = memberIndent(code, declaration, close);
            return new Insertion(close, List.of(
                    indent + "@Override",
                    indent + "public void " + m.group(2) + "(" + parameters + ") {",
                    indent + "}"));
        }

        private static int classDeclaration(List<String> code, String typeName) {
            for (int i = 0; i < code.size(); i++) {
                Matcher m = CLASS_DECLARATION.matcher(code.get(i));
                if (m.find() && m.group(1).equals(typeName)) return i;
            }
            return -1;
        }

        private static int classBodyEnd(List<String> code, int declaration) {
            int depth = 0;
            boolean opened = false;
            for (int i = declaration; i < code.size(); i++) {
                String line = code.get(i);
                for (int j = 0; j < line.length(); j++) {
                    char c = line.charAt(j);
                    if (c == '{') {
                        opened = true;
                        depth++;
                    } else if (c == '}' && --depth == 0 && opened) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private static boolean declaresMethod(List<String> code, int declaration, int close, String name) {
            for (int i = declaration; i <= close; i++) {
                String line = code.get(i);
                int from = 0;
                while (true) {
                    int at = line.indexOf(name, from);
                    if (at < 0) break;
                    from = at + name.length();
                    if (at > 0 && Character.isJavaIdentifierPart(line.charAt(at - 1))) continue;
                    if (at + name.length() < line.length() && line.charAt(at + name.length()) == '(') return true;
                }
            }
            return false;
        }

        private static String abstractParameters(Path sourceRoot, Path file, String ownerName,
                                                String methodName, int arity) throws IOException {
            Path ownerFile = ownerSource(sourceRoot, file, ownerName);
            if (ownerFile == null) return null;
            for (String line : Files.readAllLines(ownerFile)) {
                Matcher m = ABSTRACT_METHOD.matcher(line);
                if (!m.matches() || !m.group(2).equals(methodName)) continue;
                if (paramCount(m.group(3)) != arity) continue;
                if (!"void".equals(m.group(1))) return null;
                return m.group(3).strip();
            }
            return null;
        }

        private static boolean typesVisible(Path sourceRoot, Path file, List<String> code,
                                            String ownerName, String parameters) throws IOException {
            Path ownerFile = ownerSource(sourceRoot, file, ownerName);
            if (ownerFile == null) return false;
            if (ownerFile.toAbsolutePath().normalize().getParent()
                    .equals(file.toAbsolutePath().normalize().getParent())) return true;
            Set<String> visible = new HashSet<>(scopeOf(code));
            if (extendsType(code, simpleName(ownerName))) {
                for (String line : LambdaRestoreFixer.lexicalLines(Files.readAllLines(ownerFile))) {
                    Matcher declared = TYPE_DECLARATION.matcher(line.strip());
                    if (declared.find()) visible.add(declared.group(1));
                }
            }
            for (String type : unqualifiedTypeNames(parameters)) {
                if (JAVA_LANG.contains(type) || visible.contains(type)) continue;
                if (Files.isRegularFile(file.resolveSibling(type + ".java"))) continue;
                return false;
            }
            return true;
        }

        private static Set<String> scopeOf(List<String> lines) {
            Set<String> names = new HashSet<>();
            for (String raw : lines) {
                String line = raw.strip();
                if (line.startsWith("import ")) {
                    String spelled = line.substring("import ".length()).replace(";", "").strip();
                    if (spelled.startsWith("static ")) spelled = spelled.substring("static ".length()).strip();
                    if (!spelled.endsWith(".*")) names.add(simpleName(spelled));
                } else {
                    Matcher declared = TYPE_DECLARATION.matcher(line);
                    if (declared.find()) names.add(declared.group(1));
                }
            }
            return names;
        }

        private static boolean extendsType(List<String> lines, String typeName) {
            for (String line : lines) {
                Matcher m = EXTENDS_CLAUSE.matcher(line);
                if (m.find() && m.group(1).equals(typeName)) return true;
            }
            return false;
        }

        private static List<String> unqualifiedTypeNames(String parameters) {
            List<String> names = new ArrayList<>();
            for (String parameter : splitTopLevel(parameters)) {
                String type = parameterType(parameter);
                if (type.isEmpty()) continue;
                String name = unqualifiedTypeName(type);
                if (name == null || PRIMITIVES.contains(name)) continue;
                names.add(name);
            }
            return names;
        }

        private static String parameterType(String parameter) {
            String text = parameter.strip();
            while (true) {
                if (text.startsWith("final ")) {
                    text = text.substring("final ".length()).strip();
                    continue;
                }
                if (text.startsWith("@")) {
                    int space = text.indexOf(' ');
                    if (space < 0) return "";
                    text = text.substring(space + 1).strip();
                    continue;
                }
                break;
            }
            for (int i = text.length() - 1; i >= 0; i--) {
                if (Character.isWhitespace(text.charAt(i))) return text.substring(0, i).strip();
            }
            return text;
        }

        private static String unqualifiedTypeName(String type) {
            String text = type.replace("...", "[]");
            int open = text.indexOf('<');
            if (open >= 0) {
                int close = matchingAngle(text, open);
                if (close < 0) return null;
                text = text.substring(0, open) + text.substring(close + 1);
            }
            text = text.replaceAll("\\s*\\[\\s*]", "").strip();
            if (text.isEmpty() || text.indexOf('.') >= 0) return null;
            return text;
        }

        private static int matchingAngle(String text, int open) {
            int depth = 0;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '<') depth++;
                else if (c == '>' && --depth == 0) return i;
            }
            return -1;
        }

        private static String simpleName(String spelled) {
            int dot = spelled.lastIndexOf('.');
            return dot < 0 ? spelled : spelled.substring(dot + 1);
        }

        private static Path ownerSource(Path sourceRoot, Path file, String ownerName) throws IOException {
            if (ownerName.contains(".")) {
                Path named = sourceRoot.resolve(Path.of(ownerName.replace('.', '/') + ".java"));
                return Files.isRegularFile(named) ? named : null;
            }
            Path sibling = file.resolveSibling(ownerName + ".java");
            if (Files.isRegularFile(sibling)) return sibling;
            List<Path> candidates = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                for (Path p : (Iterable<Path>) walk
                        .filter(f -> f.getFileName().toString().equals(ownerName + ".java"))::iterator) {
                    candidates.add(p);
                }
            }
            return candidates.size() == 1 ? candidates.get(0) : null;
        }

        private static int paramCount(String parameters) {
            return splitTopLevel(parameters).size();
        }

        private static List<String> splitTopLevel(String parameters) {
            List<String> parts = new ArrayList<>();
            String code = parameters.strip();
            if (code.isEmpty()) return parts;
            int depth = 0;
            int start = 0;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '(' || c == '[' || c == '<') depth++;
                else if (c == ')' || c == ']' || c == '>') depth--;
                else if (c == ',' && depth == 0) {
                    parts.add(code.substring(start, i));
                    start = i + 1;
                }
            }
            parts.add(code.substring(start));
            return parts;
        }

        private static String memberIndent(List<String> code, int declaration, int close) {
            int depth = 0;
            for (int i = declaration; i < close; i++) {
                String line = code.get(i);
                if (depth == 1 && MEMBER_DECLARATION.matcher(line).find()) return leadingSpace(line);
                for (int j = 0; j < line.length(); j++) {
                    char c = line.charAt(j);
                    if (c == '{') depth++;
                    else if (c == '}') depth--;
                }
            }
            return leadingSpace(code.get(declaration)) + "   ";
        }

        private static String leadingSpace(String line) {
            return line.substring(0, line.length() - line.stripLeading().length());
        }
    }
}