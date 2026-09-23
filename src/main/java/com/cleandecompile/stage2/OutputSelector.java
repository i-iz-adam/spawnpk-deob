package com.cleandecompile.stage2;

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where more than one decompiler backend produced usable output for the
 * same class, scores each candidate and picks the best -- and, either way,
 * runs the winner through a single {@code google-java-format} pass so
 * every file in the tree has consistent formatting and later diffs between
 * pipeline runs (or between decompiler versions) are meaningful instead of
 * dominated by whitespace noise.
 *
 * <p>{@link com.cleandecompile.stage1.Stage1Runner} runs every backend and
 * feeds all successes to {@link #pickBest(Map)}; when only one succeeds it
 * wins by default, and when all fail the caller falls back to a
 * bytecode-recovered stub (which never competes here).
 */
public final class OutputSelector {

    // Heuristic weights: penalize raw-type usage (lost generics -- a sign
    // of a lower-fidelity decompile), synthetic/bridge leakage into source
    // (should have been elided), and, as a tie-breaker, prefer output
    // whose method count roughly matches what a careful decompile should
    // recover (extremely low method count vs. the class's real method
    // count usually means the decompiler silently dropped something).
    private static final Pattern RAW_TYPE_WARNING = Pattern.compile("\\braw[- ]?type\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SYNTHETIC_LEAK = Pattern.compile("\\bsynthetic\\b|\\baccess\\$\\d+\\b");
    // One backend's invalid IR leaked into otherwise compilable-looking
    // source: Vineflower's uninlined string-concat bootstraps and capture-of
    // inference text never compile, so any candidate containing them loses
    // to one without, all else equal.
    private static final Pattern DECOMPILER_RESIDUE =
            Pattern.compile("makeConcatWithConstants<|capture#\\d|\\$VF:");
    // Lost type precision degrades to Object declarations, casts, and
    // instanceof checks. Some are legitimate, so the weight stays small:
    // only a large imbalance decides.
    private static final Pattern OBJECT_TYPE = Pattern.compile("\\bObject\\b");

    public record ScoredCandidate(String decompilerName, String source, double score) {}

    /**
     * @param candidatesByDecompiler decompiler name -> source it produced (only
     *                               backends that succeeded should be included).
     */
    public ScoredCandidate pickBest(Map<String, String> candidatesByDecompiler) {
        if (candidatesByDecompiler.isEmpty()) {
            throw new IllegalArgumentException("no candidates to select between");
        }
        ScoredCandidate best = null;
        for (var entry : candidatesByDecompiler.entrySet()) {
            double score = score(entry.getValue());
            if (best == null || score > best.score()) {
                best = new ScoredCandidate(entry.getKey(), entry.getValue(), score);
            }
        }
        return best;
    }

    private double score(String source) {
        double score = 100.0;
        score -= 5.0 * countMatches(RAW_TYPE_WARNING, source);
        score -= 8.0 * countMatches(SYNTHETIC_LEAK, source);
        score -= 12.0 * countMatches(DECOMPILER_RESIDUE, source);
        score -= 1.5 * countMatches(OBJECT_TYPE, source);
        score -= 25.0 * MethodRefCheck.mismatchedCount(source);
        // Mild bonus for output that at least parenthetically looks complete:
        // penalize suspiciously short files relative to their brace-nesting depth,
        // which tends to correlate with a decompiler bailing out early on a method.
        long openBraces = source.chars().filter(c -> c == '{').count();
        long lines = source.lines().count();
        if (openBraces > 0 && lines / (double) openBraces < 1.5) {
            score -= 10.0; // very dense/collapsed output, likely truncated
        }
        return score;
    }

    private int countMatches(Pattern pattern, String text) {
        var matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }

    /**
     * Counts method references that cannot possibly resolve: {@code X::m}
     * in a stream-pipeline call whose SAM arity no same-file declaration
     * of {@code m} satisfies. Catches a systematic mis-reduction where a
     * capturing lambda ({@code x -> Class.m(a, b, x)}) is collapsed into a
     * bare, wrong-arity reference ({@code Class::m}). Only same-file
     * declarations are consulted (imports and JDK types are skipped, never
     * penalized); overload sets pass when any member fits, accounting for
     * unbound instance refs (receiver counts as one) and skipping
     * constructor refs and ambiguous SAMs (collect, reduce, thenComparing,
     * toArray) entirely.
     */
    static final class MethodRefCheck {
        /** Stream call -> SAM parameter count; absent means ambiguous, skip. */
        private static final Map<String, Integer> SAM_ARITY = Map.ofEntries(
                Map.entry("anyMatch", 1), Map.entry("allMatch", 1), Map.entry("noneMatch", 1),
                Map.entry("findFirst", 1), Map.entry("findAny", 1),
                Map.entry("map", 1), Map.entry("mapToInt", 1), Map.entry("mapToLong", 1),
                Map.entry("mapToDouble", 1), Map.entry("mapToObj", 1),
                Map.entry("filter", 1), Map.entry("flatMap", 1),
                Map.entry("flatMapToInt", 1), Map.entry("flatMapToLong", 1),
                Map.entry("flatMapToDouble", 1),
                Map.entry("forEach", 1), Map.entry("forEachOrdered", 1), Map.entry("peek", 1),
                Map.entry("sorted", 2), Map.entry("min", 2), Map.entry("max", 2),
                Map.entry("generate", 0), Map.entry("orElseGet", 0), Map.entry("orElseThrow", 0),
                Map.entry("or", 0), Map.entry("ifPresent", 1),
                Map.entry("comparing", 1), Map.entry("comparingDouble", 1),
                Map.entry("comparingInt", 1), Map.entry("comparingLong", 1));

        private static final Pattern REF_SITE = Pattern.compile(
                "\\.\\s*(anyMatch|allMatch|noneMatch|findFirst|findAny|map|mapToInt|mapToLong|"
                        + "mapToDouble|mapToObj|filter|flatMap|flatMapToInt|flatMapToLong|flatMapToDouble|"
                        + "forEach|forEachOrdered|peek|sorted|min|max|generate|orElseGet|orElseThrow|or|"
                        + "ifPresent|comparing|comparingDouble|comparingInt|comparingLong)"
                        + "\\s*\\(\\s*([\\w.$]+)::(\\w+)\\s*\\)");
        private static final Pattern NEW_REF_CONTAINER = Pattern.compile(
                "new\\s+(?:[\\w.$]+\\s*\\.\\s*)?(?:TreeMap|TreeSet|PriorityQueue)\\s*\\(\\s*([\\w.$]+)::(\\w+)\\s*\\)");
        private static final Pattern DECLARATOR = Pattern.compile(
                "(?m)^\\s*(?:(?:public|private|protected|static|final|synchronized|native|abstract|default|strictfp)\\s+)*"
                        + "[\\w<>,?\\[\\]. ]+?\\s+(\\w+)\\s*\\(");

        static int mismatchedCount(String source) {
            Map<String, List<Decl>> declarations = collectDeclarations(source);
            if (declarations.isEmpty()) return 0;
            String ownClass = ownClassName(source);
            java.util.Set<String> imported = importedSimpleNames(source);
            int mismatches = 0;
            mismatches += checkSites(source, declarations, ownClass, imported);
            return mismatches;
        }

        private record Decl(String name, int arity, boolean isStatic) {
        }

        private static int checkSites(String source, Map<String, List<Decl>> declarations,
                                      String ownClass, java.util.Set<String> imported) {
            int mismatches = 0;
            Matcher refs = REF_SITE.matcher(source);
            while (refs.find()) {
                Integer sam = SAM_ARITY.get(refs.group(1));
                if (sam == null) continue;
                if (isMismatch(refs.group(2), refs.group(3), sam, declarations, ownClass, imported)) {
                    mismatches++;
                }
            }
            Matcher news = NEW_REF_CONTAINER.matcher(source);
            while (news.find()) {
                if (isMismatch(news.group(1), news.group(2), 2, declarations, ownClass, imported)) {
                    mismatches++;
                }
            }
            return mismatches;
        }

        /** True when no same-file declaration of {@code method} can satisfy
         *  the SAM: unknown receivers (imports, JDK, other files) abstain. */
        private static boolean isMismatch(String receiver, String method, int sam,
                                          Map<String, List<Decl>> declarations,
                                          String ownClass, java.util.Set<String> imported) {
            List<Decl> overloads = declarations.get(method);
            if (overloads == null || overloads.isEmpty()) return false;
            String simpleReceiver = receiver.contains(".")
                    ? receiver.substring(receiver.lastIndexOf('.') + 1)
                    : receiver;
            boolean classPosition;
            if (receiver.equals("this")) {
                classPosition = false;
            } else if (simpleReceiver.equals(ownClass) || imported.contains(simpleReceiver)
                    || Character.isUpperCase(simpleReceiver.charAt(0))) {
                classPosition = true;
            } else {
                classPosition = false;
            }
            for (Decl decl : overloads) {
                if (classPosition) {
                    // Static ref needs exact arity; unbound instance ref
                    // consumes the receiver as one argument.
                    if ((decl.isStatic() && decl.arity() == sam)
                            || (!decl.isStatic() && decl.arity() == sam - 1)) {
                        return false;
                    }
                } else if (decl.arity() == sam) {
                    return false;
                }
            }
            return true;
        }

        private static Map<String, List<Decl>> collectDeclarations(String source) {
            Map<String, List<Decl>> declarations = new java.util.HashMap<>();
            Matcher declarators = DECLARATOR.matcher(source);
            while (declarators.find()) {
                String name = declarators.group(1);
                int open = declarators.end() - 1;
                int close = findMatchingParen(source, open);
                if (close < 0) continue;
                String params = source.substring(open + 1, close).trim();
                int arity = params.isEmpty() ? 0 : splitTopLevelCommas(params) + 1;
                String prefix = declarators.group(0);
                boolean isStatic = prefix.matches("(?s).*\\bstatic\\b.*");
                declarations.computeIfAbsent(name, k -> new java.util.ArrayList<>())
                        .add(new Decl(name, arity, isStatic));
            }
            return declarations;
        }

        private static String ownClassName(String source) {
            Matcher m = Pattern.compile(
                    "(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+)*(?:class|interface|enum|record)\\s+(\\w+)")
                    .matcher(source);
            return m.find() ? m.group(1) : "";
        }

        private static java.util.Set<String> importedSimpleNames(String source) {
            java.util.Set<String> names = new java.util.HashSet<>();
            Matcher m = Pattern.compile("(?m)^\\s*import\\s+(?:static\\s+)?([\\w.$]+)\\s*;").matcher(source);
            while (m.find()) {
                String fqn = m.group(1);
                names.add(fqn.substring(fqn.lastIndexOf('.') + 1));
            }
            names.addAll(java.util.List.of("String", "Integer", "Long", "Double", "Float", "Short",
                    "Byte", "Character", "Boolean", "Object", "Class", "Math", "System"));
            return names;
        }

        private static int findMatchingParen(String text, int open) {
            int depth = 0;
            boolean inStr = false;
            boolean inChr = false;
            for (int i = open; i < text.length(); i++) {
                char c = text.charAt(i);
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

        private static int splitTopLevelCommas(String params) {
            int depthAngle = 0, depthBracket = 0;
            boolean inStr = false;
            int commas = 0;
            for (int i = 0; i < params.length(); i++) {
                char c = params.charAt(i);
                if (inStr) {
                    if (c == '\\') i++;
                    else if (c == '"') inStr = false;
                    continue;
                }
                switch (c) {
                    case '"' -> inStr = true;
                    case '<' -> depthAngle++;
                    case '>' -> { if (depthAngle > 0) depthAngle--; }
                    case '[' -> depthBracket++;
                    case ']' -> depthBracket--;
                    case ',' -> { if (depthAngle == 0 && depthBracket == 0) commas++; }
                    default -> { }
                }
            }
            return commas;
        }
    }

    /** Formats source text; returns it unchanged if it doesn't parse (e.g.
     *  a stub whose bytecode-dump comment happens to confuse the formatter). */
    public String format(String source) {
        try {
            return new Formatter().formatSource(source);
        } catch (FormatterException e) {
            return source;
        }
    }
}
