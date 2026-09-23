package com.cleandecompile.stage2;

import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where more than one decompiler backend produced usable output for the
 * same class, scores each candidate and picks the best -- and, either way,
 * runs the winner through a single {@code google-java-format} pass so
 * every file in the tree has consistent formatting and later diffs between
 * pipeline runs (or between decompiler versions) are meaningful instead of
 * dominated by whitespace noise.
 *
 * <p>{@link com.cleandecompile.stage1.Stage1Runner} currently takes the
 * plan's "simpler alternative" and stops at the first backend that
 * succeeds, so in that mode there's nothing to select between and this
 * class only does the formatting pass. If Stage 1 is later changed to run
 * every backend and collect all successes, feed them to
 * {@link #pickBest(Map)} first.
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
