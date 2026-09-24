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

    public record IterationSummary(int iteration, int errorCountBefore, int errorCountAfter, int autoFixesApplied) {}

    public record LoopReport(boolean converged, List<IterationSummary> iterations,
                             List<String> remainingErrorSummaries,
                             List<String> failingFiles) {
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

        for (int i = 1; i <= config.maxFixLoopIterations(); i++) {
            JavacRunner.CompileOutcome outcome = javac.compile(sourceRoot, classOutput, classpath,
                    config.releaseLevel());
            if (outcome.success()) {
                iterations.add(new IterationSummary(i, 0, 0, 0));
                LoopReport report = new LoopReport(true, iterations, List.of(), List.of());
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
                iterations.add(new IterationSummary(i, errorsBefore, errorsBefore, 0));
                LoopReport report = new LoopReport(false, iterations, summarize(outcome.diagnostics()),
                        failingFiles(sourceRoot, outcome.diagnostics()));
                writeReport(config, report);
                return report;
            }
            previousSignatures = signatures;

            int fixesApplied = applyMechanicalFixes(sourceRoot, bucketed, classpath, config.releaseLevel());

            iterations.add(new IterationSummary(i, errorsBefore, -1 /* filled in next loop */, fixesApplied));

            if (fixesApplied == 0) {
                // Nothing left we know how to fix mechanically -- report the
                // remainder for a human. Error counts are NOT compared
                // across iterations: fixing syntax routinely reveals deeper
                // attribution errors, so a rising count is progress, not
                // regress.
                List<String> remaining = summarize(outcome.diagnostics());
                LoopReport report = new LoopReport(false, iterations, remaining,
                        failingFiles(sourceRoot, outcome.diagnostics()));
                writeReport(config, report);
                return report;
            }
        }

        JavacRunner.CompileOutcome finalOutcome = javac.compile(sourceRoot, classOutput, classpath,
                config.releaseLevel());
        LoopReport report = new LoopReport(finalOutcome.success(), iterations,
                summarize(finalOutcome.diagnostics()),
                failingFiles(sourceRoot, finalOutcome.diagnostics()));
        writeReport(config, report);
        return report;
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

        if (fixes > 0) {
            System.out.printf("  fixes applied: objectTyped=%d imports=%d concat=%d casts=%d artifacts=%d compare=%d widen=%d receiver=%d%n",
                    objectFixes, importFixes, concatFixes, castFixes, artifactFixes, compareFixes, widenFixes, receiverFixes);
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
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(reportPath.toFile(), report);
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
        private static final Map<String, String> RECEIVER_TYPE = Map.of(
                "split/1", "java.lang.String",
                "toCharArray/0", "java.lang.String",
                "intern/0", "java.lang.String",
                "exists/0", "java.io.File",
                "mkdir/0", "java.io.File",
                "mkdirs/0", "java.io.File",
                "delete/0", "java.io.File",
                "createNewFile/0", "java.io.File",
                "listFiles/0", "java.io.File");

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
