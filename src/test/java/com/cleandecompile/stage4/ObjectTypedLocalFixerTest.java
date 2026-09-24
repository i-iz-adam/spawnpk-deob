package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cleandecompile.PipelineConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the fixer against REAL javac diagnostics (not hand-built ones): the
 * message shapes, line numbers and Object-typed locals are exactly what
 * Stage 4 sees on a decompiled tree.
 */
class ObjectTypedLocalFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    // ---- helpers ----

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static String read(Path root, String rel) throws IOException {
        return Files.readString(root.resolve(rel));
    }

    private JavacRunner.CompileOutcome compile(Path root) throws IOException {
        return javac.compile(root, root.resolveSibling("classes-" + root.getFileName()), List.of(), "17");
    }

    private ObjectTypedLocalFixer.Result fix(Path root) throws IOException {
        var outcome = compile(root);
        return ObjectTypedLocalFixer.tryFixAll(root, bucketer.categorize(outcome.diagnostics()), List.of(), "17");
    }

    // ---- retyping the declaration (sound case) ----

    @Test
    void retypesEveryFlaggedShapeToTheAssignedType(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                import java.util.ArrayList;
                import java.util.Iterator;
                import java.util.List;

                public class Client {
                    static class Node { int field1718; }

                    int[][] table = new int[4][4];
                    List<String> names = new ArrayList<>();
                    Node node = new Node();

                    int run() {
                        Object object = this.table[1];
                        object[0] = 5;
                        int x = object[1] + object.length;
                        Object it = names.iterator();
                        while (it.hasNext()) {
                            it.next();
                        }
                        Object list = new ArrayList<String>();
                        for (String s : list) {
                            System.out.println(s);
                        }
                        Object nd = this.node;
                        return x + nd.field1718;
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/Client.java");

        assertTrue(out.contains("int[] object = this.table[1];"), out);
        assertTrue(out.contains("Iterator<String> it = names.iterator();"), out);
        assertTrue(out.contains("ArrayList<String> list = new ArrayList<String>();"), out);
        assertTrue(out.contains("Client.Node nd = this.node;") || out.contains("Node nd = this.node;"), out);
        assertTrue(result.fixes() >= 4);
        assertTrue(compile(root).success(), "tree should compile after one pass");
    }

    @Test
    void retypesWhenEveryAssignmentIsAssignableEvenAcrossBranches(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.Iterator;
                import java.util.List;

                public class A {
                    int run(List<String> a, List<String> b, boolean flag) {
                        Object v = a.iterator();
                        if (flag) {
                            v = b.iterator();
                        }
                        int n = 0;
                        while (v.hasNext()) {
                            n++;
                            v.next();
                        }
                        return n;
                    }
                }
                """);
        fix(root);
        assertTrue(read(root, "rs/A.java").contains("Iterator<String> v = a.iterator();"));
        assertTrue(compile(root).success());
    }

    // ---- slot reuse: one variable, several unrelated types ----

    @Test
    void slotReuseGetsPerUseCastsFromTheReachingAssignment(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.Iterator;
                import java.util.List;

                public class A {
                    int run(String csv, List<String> names) {
                        Object v = csv.split(",");
                        int n = v.length;
                        Object first = v[0];
                        v = names.iterator();
                        int c = 0;
                        while (v.hasNext()) {
                            c++;
                            v.next();
                        }
                        return n + c + first.hashCode();
                    }
                }
                """);
        fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("Object v = csv.split(\",\");"), "declaration must stay Object: " + out);
        assertTrue(out.contains("((String[]) v).length"), out);
        assertTrue(out.contains("((String[]) v)[0]"), out);
        assertTrue(out.contains("((Iterator<String>) v).hasNext()"), out);
        assertTrue(compile(root).success());
    }

    // ---- soundness: cases where guessing would compile but be wrong ----

    @Test
    void refusesToCastAfterABranchMergeWithDifferentTypes(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.Iterator;
                import java.util.List;

                public class A {
                    int run(List<String> names, boolean flag) {
                        Object v = names;
                        if (flag) {
                            v = names.iterator();
                        }
                        return v.size();
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        var result = fix(root);
        assertEquals(0, result.fixes(), "v is a List OR an Iterator here; no single cast is sound");
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void refusesWhenALoopCarriedReassignmentCanReachTheUse(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.List;

                public class A {
                    int run(List<String> names) {
                        Object v = names.iterator();
                        int n = 0;
                        for (int i = 0; i < 3; i++) {
                            if (v.hasNext()) {
                                n++;
                            }
                            v = names.size();
                        }
                        return n;
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        assertEquals(0, fix(root).fixes());
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void doesNotTrustAnAssignmentBehindAShortCircuit(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.List;

                public class A {
                    int run(List<String> names, boolean flag) {
                        Object v = "seed";
                        boolean ok = flag && (v = names.iterator()) != null;
                        return v.hashCode() + (v.hasNext() ? 1 : 0);
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        assertEquals(0, fix(root).fixes(), "the Iterator assignment may not have executed");
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void leavesLegitimateObjectUsesAlone(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    boolean run(Object o, Object p) {
                        String s = o.toString();
                        return o.equals(p) && o.hashCode() != 0 && p != null && s != null;
                    }
                }
                """);
        assertTrue(compile(root).success());
        assertEquals(0, fix(root).fixes());
    }

    @Test
    void skipsTypesThatCannotBeWrittenAtTheSite(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    int run() {
                        Object o = new Object() {
                            public int foo() {
                                return 1;
                            }
                        };
                        return o.foo();
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        assertEquals(0, fix(root).fixes(), "an anonymous class has no name to cast to");
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void multiDeclaratorsAreNeverRetypedButStillGetCasts(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.List;

                public class A {
                    int run(List<String> names) {
                        Object a = names, b = names;
                        return a.size() + b.hashCode();
                    }
                }
                """);
        fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("Object a = names, b = names;"), "shared type node must stay Object: " + out);
        assertTrue(out.contains("((List<String>) a).size()"), out);
        assertTrue(compile(root).success());
    }

    // ---- inference from demand when no assignment has a usable type ----

    @Test
    void infersTheOwnerOfAUniqueFieldNameFromTheSourceTree(@TempDir Path root) throws IOException {
        write(root, "rs/pkg/Class53.java", """
                package rs.pkg;

                public class Class53 {
                    public int field1718;
                    public int field1719;
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                import java.util.List;

                public class Client {
                    @SuppressWarnings("rawtypes")
                    int run(List raw) {
                        Object o = raw.get(0);
                        return o.field1718 + o.field1719;
                    }
                }
                """);
        fix(root);
        String out = read(root, "rs/Client.java");
        assertTrue(out.contains("((rs.pkg.Class53) o).field1718"), out);
        assertTrue(compile(root).success());
    }

    @Test
    void ambiguousFieldNamesAreNotGuessed(@TempDir Path root) throws IOException {
        write(root, "rs/One.java", "package rs;\npublic class One { public int field9; }\n");
        write(root, "rs/Two.java", "package rs;\npublic class Two { public int field9; }\n");
        write(root, "rs/Client.java", """
                package rs;

                import java.util.List;

                public class Client {
                    @SuppressWarnings("rawtypes")
                    int run(List raw) {
                        Object o = raw.get(0);
                        return o.field9;
                    }
                }
                """);
        assertEquals(0, fix(root).fixes());
    }

    @Test
    void nameBasedGuessesNeedStringSpecificOrTwoDemands(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                import java.util.List;

                public class Client {
                    @SuppressWarnings("rawtypes")
                    int run(List raw) {
                        Object s = raw.get(0);
                        Object it = raw.get(1);
                        Object q = raw.get(2);
                        int n = 0;
                        if (s.startsWith("a")) {
                            n++;
                        }
                        while (it.hasNext()) {
                            it.next();
                        }
                        return n + q.size();
                    }
                }
                """);
        fix(root);
        String out = read(root, "rs/Client.java");
        assertTrue(out.contains("((String) s).startsWith(\"a\")"), out);
        assertTrue(out.contains("((java.util.Iterator) it).hasNext()")
                || out.contains("((Iterator) it).hasNext()"), out);
        // size() alone is declared by Collection, Map and library classes alike.
        assertTrue(out.contains("q.size()") && !out.contains("(q)"), out);
        var remaining = compile(root).diagnostics();
        assertEquals(1, remaining.size(), "only the ambiguous q.size() should remain");
    }

    @Test
    void parametersTypedObjectGetCastsNeverRetypes(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    boolean run(Object p) {
                        return p.startsWith("x");
                    }
                }
                """);
        fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("boolean run(Object p)"), "signature must not change: " + out);
        assertTrue(out.contains("((String) p).startsWith(\"x\")"), out);
    }

    // ---- loop-level behaviour ----

    @Test
    void isIdempotentAndWithholdsHandledDiagnosticsFromLaterFixers(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.List;

                public class A {
                    int run(List<String> names) {
                        Object it = names.iterator();
                        int n = 0;
                        while (it.hasNext()) {
                            n++;
                        }
                        return n;
                    }
                }
                """);
        var outcome = compile(root);
        var bucketed = bucketer.categorize(outcome.diagnostics());
        var first = ObjectTypedLocalFixer.tryFixAll(root, bucketed, List.of(), "17");
        assertTrue(first.fixes() > 0);
        assertTrue(first.unhandled(bucketed).isEmpty(), "every diagnostic here was addressed");

        assertTrue(compile(root).success());
        assertEquals(0, fix(root).fixes(), "second pass over a fixed tree changes nothing");
    }

    @Test
    void unrelatedErrorsProduceNoWork(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    int run() {
                        return missing;
                    }
                }
                """);
        var result = fix(root);
        assertEquals(0, result.fixes());
        assertTrue(result.handled().isEmpty());
    }

    @Test
    void fullFixLoopConvergesOnAnObjectTypedTree(@TempDir Path temp) throws IOException {
        Path out = temp.resolve("out");
        Path src = out.resolve("src-generated/src/main/java");
        write(src, "rs/Client.java", """
                package rs;

                import java.util.ArrayList;
                import java.util.List;

                public class Client {
                    List<String> names = new ArrayList<>();
                    int[] counts = new int[8];

                    String last() {
                        Object it = names.iterator();
                        String last = null;
                        while (it.hasNext()) {
                            last = it.next();
                        }
                        Object c = counts;
                        c[0] = 1;
                        return last;
                    }
                }
                """);
        var config = new PipelineConfig(null, out, List.of(), PipelineConfig.DEFAULT_TIMEOUT_MS,
                PipelineConfig.DEFAULT_MAX_FIX_ITERATIONS, null, false, "17", "", "", null, null);
        var report = new CompileFixLoop().run(config);
        assertTrue(report.converged(), () -> String.join("\n", report.remainingErrorSummaries()));
        assertFalse(read(src, "rs/Client.java").contains("Object it"));
    }
}
