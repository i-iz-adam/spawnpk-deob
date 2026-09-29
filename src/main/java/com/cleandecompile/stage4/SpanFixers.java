package com.cleandecompile.stage4;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Predicate;

/**
 * Driver for fixers that edit by <b>javac's own diagnostic span</b> instead
 * of by line-and-regex.
 *
 * <p>Every javac diagnostic carries the exact character range of the tree
 * it is about ({@code getStartPosition}/{@code getEndPosition}). A planner
 * given that range can find the corresponding node in a fresh parse of the
 * same text, look at its kind and its neighbours (parent, operands,
 * arguments) and emit a precise replacement -- no guessing which of three
 * identical-looking tokens on a line the error meant.
 *
 * <p>Offsets are only meaningful against the text javac compiled, so the
 * driver must run BEFORE any fixer that rewrites the same file in the same
 * round; it is wired first in {@link CompileFixLoop}. All planners share one
 * pass per file: their edits are collected, overlapping ones are dropped
 * (the dropped diagnostic simply resurfaces next round, against fresh
 * text), and the rest are applied back-to-front in a single write. Line
 * endings and every untouched character are preserved byte for byte.
 */
final class SpanFixers {

    /** Replace {@code [start, end)} with {@code replacement}. */
    record Edit(int start, int end, String replacement) {
        boolean conflictsWith(Edit other) {
            if (start == end && other.start == other.end) return start == other.start;
            if (start == end) return other.start < start && start < other.end;
            if (other.start == other.end) return start < other.start && other.start < end;
            return start < other.end && other.start < end;
        }
    }

    interface Planner {
        String name();

        /** Cheap message-only filter, so files are only parsed when a planner might act. */
        boolean wants(String firstMessageLine);

        /**
         * Plans the edits for one diagnostic. Returns {@code true} when it
         * owns the diagnostic (edits appended to {@code out}); {@code false}
         * leaves the diagnostic for later fixers.
         */
        boolean plan(ParsedFile file, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> out);
    }

    record Result(int fixes, Map<String, Integer> byPlanner,
                  Set<Diagnostic<? extends JavaFileObject>> handled) {
        static final Result NONE = new Result(0, Map.of(), Set.of());

        int count(String planner) {
            return byPlanner.getOrDefault(planner, 0);
        }

        List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
            if (handled.isEmpty()) return all;
            return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
        }
    }

    private SpanFixers() {
    }

    static Result run(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed, List<Planner> planners)
            throws IOException {
        Path root = sourceRoot.toAbsolutePath().normalize();
        Map<Path, List<Diagnostic<? extends JavaFileObject>>> byFile = new LinkedHashMap<>();
        for (var b : bucketed) {
            var d = b.diagnostic();
            if (d.getSource() == null || d.getStartPosition() < 0 || d.getEndPosition() <= d.getStartPosition()) {
                continue;
            }
            String first = firstLine(d.getMessage(Locale.ENGLISH));
            if (planners.stream().noneMatch(p -> p.wants(first))) continue;
            Path file;
            try {
                file = Path.of(d.getSource().toUri()).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                continue; // unresolvable source -- not attributable to a file
            }
            if (!file.startsWith(root)) continue;
            byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(d);
        }
        if (byFile.isEmpty()) return Result.NONE;

        Set<Diagnostic<? extends JavaFileObject>> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (var entry : byFile.entrySet()) {
            String text;
            try {
                text = Files.readString(entry.getKey(), StandardCharsets.UTF_8);
            } catch (CharacterCodingException e) {
                continue; // not valid UTF-8 -- javac's offsets can't be trusted against it
            } catch (IOException e) {
                continue;
            }
            ParsedFile parsed = ParsedFile.parse(entry.getKey(), text);
            if (parsed == null) continue;

            record Group(String planner, Diagnostic<? extends JavaFileObject> diagnostic, List<Edit> edits) {}
            List<Group> groups = new ArrayList<>();
            for (var d : entry.getValue()) {
                String first = firstLine(d.getMessage(Locale.ENGLISH));
                for (Planner planner : planners) {
                    if (!planner.wants(first)) continue;
                    List<Edit> edits = new ArrayList<>();
                    boolean owned;
                    try {
                        owned = planner.plan(parsed, d, edits);
                    } catch (RuntimeException e) {
                        owned = false; // a planner bug must never take the loop down
                    }
                    if (owned && !edits.isEmpty() && validEdits(edits, text.length())) {
                        groups.add(new Group(planner.name(), d, edits));
                        break;
                    }
                }
            }
            if (groups.isEmpty()) continue;

            // Back-to-front, first come first kept: a group conflicting with an
            // already accepted one is postponed to the next round.
            groups.sort(Comparator.comparingInt((Group g) ->
                    g.edits().stream().mapToInt(Edit::start).min().orElse(0)).reversed());
            List<Edit> accepted = new ArrayList<>();
            List<List<Edit>> acceptedGroups = new ArrayList<>();
            int fileFixes = 0;
            for (Group g : groups) {
                boolean duplicate = acceptedGroups.stream().anyMatch(a -> a.equals(g.edits()));
                if (duplicate) {
                    handled.add(g.diagnostic()); // identical fix already queued (javac reported the span twice)
                    continue;
                }
                boolean conflict = g.edits().stream()
                        .anyMatch(e -> accepted.stream().anyMatch(a -> a.conflictsWith(e)));
                if (conflict) continue;
                accepted.addAll(g.edits());
                acceptedGroups.add(g.edits());
                handled.add(g.diagnostic());
                counts.merge(g.planner(), 1, Integer::sum);
                fileFixes++;
            }
            if (fileFixes == 0) continue;

            accepted.sort(Comparator.comparingInt(Edit::start).reversed());
            StringBuilder out = new StringBuilder(text);
            for (Edit e : accepted) out.replace(e.start(), e.end(), e.replacement());
            Files.writeString(entry.getKey(), out.toString(), StandardCharsets.UTF_8);
            total += fileFixes;
        }
        return total == 0 ? Result.NONE : new Result(total, counts, handled);
    }

    private static boolean validEdits(List<Edit> edits, int length) {
        for (Edit e : edits) {
            if (e.start() < 0 || e.end() < e.start() || e.end() > length) return false;
        }
        return true;
    }

    static String firstLine(String message) {
        int nl = message.indexOf('\n');
        String line = nl < 0 ? message : message.substring(0, nl);
        return line.strip();
    }

    /** A source file parsed (never attributed) with position lookup by exact span. */
    static final class ParsedFile {
        final Path path;
        final String text;
        final CompilationUnitTree unit;
        private final SourcePositions positions;
        private Map<Long, List<TreePath>> bySpan;

        private ParsedFile(Path path, String text, CompilationUnitTree unit, SourcePositions positions) {
            this.path = path;
            this.text = text;
            this.unit = unit;
            this.positions = positions;
        }

        /** {@code null} when the text does not parse cleanly (javac would not have attributed it either). */
        static ParsedFile parse(Path path, String text) {
            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) return null;
            DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
            JavaFileObject source = new SimpleJavaFileObject(
                    URI.create("string:///" + path.getFileName()), JavaFileObject.Kind.SOURCE) {
                @Override
                public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                    return text;
                }
            };
            try {
                JavacTask task = (JavacTask) compiler.getTask(null, null, collector,
                        List.of("-proc:none"), null, List.of(source));
                Iterator<? extends CompilationUnitTree> units = task.parse().iterator();
                if (!units.hasNext()) return null;
                CompilationUnitTree unit = units.next();
                for (var d : collector.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) return null;
                }
                return new ParsedFile(path, text, unit, Trees.instance(task).getSourcePositions());
            } catch (Exception e) {
                return null;
            }
        }

        long start(Tree tree) {
            return positions.getStartPosition(unit, tree);
        }

        long end(Tree tree) {
            return positions.getEndPosition(unit, tree);
        }

        String slice(Tree tree) {
            long s = start(tree);
            long e = end(tree);
            if (s < 0 || e < s || e > text.length()) return null;
            return text.substring((int) s, (int) e);
        }

        String slice(long start, long end) {
            if (start < 0 || end < start || end > text.length()) return null;
            return text.substring((int) start, (int) end);
        }

        /** The deepest tree spanning exactly {@code [start, end)} that passes {@code filter}, or null. */
        TreePath findExact(long start, long end, Predicate<Tree> filter) {
            if (bySpan == null) index();
            List<TreePath> candidates = bySpan.get(key(start, end));
            if (candidates == null) return null;
            for (int i = candidates.size() - 1; i >= 0; i--) {
                if (filter.test(candidates.get(i).getLeaf())) return candidates.get(i);
            }
            return null;
        }

        /** All trees passing {@code filter}, in pre-order. */
        List<TreePath> findAll(Predicate<Tree> filter) {
            List<TreePath> found = new ArrayList<>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void scan(Tree node, Void p) {
                    if (node == null) return null;
                    if (filter.test(node)) found.add(new TreePath(getCurrentPath(), node));
                    return super.scan(node, p);
                }
            }.scan(new TreePath(unit), null);
            return found;
        }

        private void index() {
            bySpan = new HashMap<>();
            new TreePathScanner<Void, Void>() {
                @Override
                public Void scan(Tree node, Void p) {
                    if (node == null) return null;
                    long s = start(node);
                    long e = end(node);
                    if (s >= 0 && e > s) {
                        bySpan.computeIfAbsent(key(s, e), k -> new ArrayList<>())
                                .add(new TreePath(getCurrentPath(), node));
                    }
                    return super.scan(node, p);
                }
            }.scan(new TreePath(unit), null);
        }

        private static long key(long start, long end) {
            return (start << 32) ^ end;
        }
    }
}
