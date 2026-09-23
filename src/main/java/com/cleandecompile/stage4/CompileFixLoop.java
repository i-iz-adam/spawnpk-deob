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
                              List<String> remainingErrorSummaries) {}

    public LoopReport run(PipelineConfig config) throws IOException {
        Path sourceRoot = config.decompiledSourcesDir();
        Path classOutput = config.outputDir().resolve("classes");
        Path vendoredJar = config.vendoredLibsDir().resolve("vendored-unidentified.jar");
        List<Path> classpath = Files.exists(vendoredJar) ? List.of(vendoredJar) : List.of();
        JavacRunner javac = new JavacRunner();

        List<IterationSummary> iterations = new ArrayList<>();
        int previousErrorCount = Integer.MAX_VALUE;

        for (int i = 1; i <= config.maxFixLoopIterations(); i++) {
            JavacRunner.CompileOutcome outcome = javac.compile(sourceRoot, classOutput, classpath,
                    config.releaseLevel());
            if (outcome.success()) {
                iterations.add(new IterationSummary(i, 0, 0, 0));
                LoopReport report = new LoopReport(true, iterations, List.of());
                writeReport(config, report);
                return report;
            }

            var bucketed = bucketer.categorize(outcome.diagnostics());
            int errorsBefore = bucketed.size();

            int fixesApplied = applyMechanicalFixes(sourceRoot, bucketed);

            iterations.add(new IterationSummary(i, errorsBefore, -1 /* filled in next loop */, fixesApplied));

            if (fixesApplied == 0 || errorsBefore >= previousErrorCount) {
                // No progress this iteration (either nothing was
                // mechanically fixable, or fixes didn't actually reduce
                // the error count) -- stop early rather than burn the
                // remaining iteration budget for nothing.
                List<String> remaining = summarize(outcome.diagnostics());
                LoopReport report = new LoopReport(false, iterations, remaining);
                writeReport(config, report);
                return report;
            }
            previousErrorCount = errorsBefore;
        }

        JavacRunner.CompileOutcome finalOutcome = javac.compile(sourceRoot, classOutput, classpath,
                config.releaseLevel());
        LoopReport report = new LoopReport(finalOutcome.success(), iterations, summarize(finalOutcome.diagnostics()));
        writeReport(config, report);
        return report;
    }

    private int applyMechanicalFixes(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed) throws IOException {
        Map<DiagnosticBucketer.Category, List<DiagnosticBucketer.Bucketed>> grouped = bucketer.group(bucketed);
        int fixes = 0;

        var unresolved = grouped.getOrDefault(DiagnosticBucketer.Category.UNRESOLVED_SYMBOL, List.of());
        if (!unresolved.isEmpty()) {
            fixes += ImportInserter.tryFixAll(sourceRoot, unresolved);
        }

        // Vineflower leaks invokedynamic string concatenation as a literal
        // bootstrap call (StringConcatFactory.makeConcatWithConstants<...>(...))
        // whenever it can't inline the recipe -- valid IR, invalid Java.
        // Recipes are mechanically convertible to plain + chains.
        fixes += StringConcatFixer.tryFixAll(sourceRoot);

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
                    insertImports(entry.getKey(), importsToAdd);
                    fixed += importsToAdd.size();
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

        private static void insertImports(Path javaFile, Set<String> fqns) throws IOException {
            List<String> lines = new ArrayList<>(Files.readAllLines(javaFile));
            int insertAt = 0;
            for (int i = 0; i < lines.size(); i++) {
                String trimmed = lines.get(i).trim();
                if (trimmed.startsWith("package ") || trimmed.startsWith("import ")) {
                    insertAt = i + 1;
                } else if (!trimmed.isEmpty() && !trimmed.startsWith("//")) {
                    break;
                }
            }
            List<String> newImports = fqns.stream().map(fqn -> "import " + fqn + ";").toList();
            lines.addAll(insertAt, newImports);
            Files.write(javaFile, lines);
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
                String rewritten = rewriteLine(line);
                if (rewritten != null) {
                    lines.set(i, rewritten);
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
