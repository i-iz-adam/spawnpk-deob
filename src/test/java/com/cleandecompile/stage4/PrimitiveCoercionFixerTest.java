package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * int/boolean conflation: the verifier cannot tell a boolean from an int,
 * so decompilers print one where the bytecode moves the other. Each case
 * mirrors a diagnostic shape from the Client/ItemDefinition remainder, run
 * against REAL javac output and re-compiled after the fix.
 */
class PrimitiveCoercionFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private String fixUntilStable(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        // The loop's own shape: fix, recompile, repeat (an operand fix can reveal a follow-up).
        for (int round = 0; round < 6; round++) {
            var outcome = javac.compile(root, root.resolveSibling("classes" + round), List.of(), "17");
            if (outcome.success()) break;
            var result = SpanFixers.run(root, bucketer.categorize(outcome.diagnostics()),
                    List.of(new PrimitiveCoercionFixer()));
            if (result.fixes() == 0) break;
        }
        return Files.readString(file);
    }

    private void assertCompiles(Path root, String context) throws IOException {
        var after = javac.compile(root, root.resolveSibling("final"), List.of(), "17");
        assertTrue(after.success(), context + "\n" + after.diagnostics());
    }

    @Test
    void intInBooleanPositionsBecomesNotEqualZero(@TempDir Path root) throws IOException {
        String updated = fixUntilStable(root, "rs/A.java", """
                package rs;

                public class A {
                    int flags;
                    boolean field() { return this.flags; }
                    void m(int v) {
                        boolean x = v;
                        if (v) { x = false; }
                        take(this.flags);
                        while (v && x) { v--; }
                    }
                    void take(boolean b) { }
                }
                """);
        assertTrue(updated.contains("return this.flags != 0;"), updated);
        assertTrue(updated.contains("boolean x = v != 0;"), updated);
        assertTrue(updated.contains("if (v != 0)"), updated);
        assertTrue(updated.contains("take(this.flags != 0);"), updated);
        assertTrue(updated.contains("while (v != 0 && x)"), updated);
        assertCompiles(root, updated);
    }

    @Test
    void integerLiteralsBecomeBooleanLiterals(@TempDir Path root) throws IOException {
        String updated = fixUntilStable(root, "rs/B.java", """
                package rs;

                public class B {
                    boolean a() { return 1; }
                    boolean b() { return 0; }
                    void c() { boolean x = 1; x = 0; }
                }
                """);
        assertTrue(updated.contains("return true;"), updated);
        assertTrue(updated.contains("return false;"), updated);
        assertTrue(updated.contains("boolean x = true; x = false;"), updated);
        assertCompiles(root, updated);
    }

    @Test
    void booleanInIntPositionsBecomesTernary(@TempDir Path root) throws IOException {
        String updated = fixUntilStable(root, "rs/C.java", """
                package rs;

                public class C {
                    boolean flag;
                    int a() { return this.flag; }
                    void m(int[] arr) {
                        int y = this.flag;
                        int z = arr[this.flag];
                        take(this.flag);
                        int t = true;
                    }
                    void take(int v) { }
                }
                """);
        assertTrue(updated.contains("return this.flag ? 1 : 0;"), updated);
        assertTrue(updated.contains("int y = this.flag ? 1 : 0;"), updated);
        assertTrue(updated.contains("arr[this.flag ? 1 : 0]"), updated);
        assertTrue(updated.contains("take(this.flag ? 1 : 0);"), updated);
        assertTrue(updated.contains("int t = 1;"), updated);
        assertCompiles(root, updated);
    }

    @Test
    void mixedBitwiseOperandsCoerceTheBooleanSide(@TempDir Path root) throws IOException {
        // Client:5531 shape -- `int & boolean`. The bytecode has only iand/ior/ixor.
        String updated = fixUntilStable(root, "rs/D.java", """
                package rs;

                public class D {
                    int n;
                    boolean flag;
                    boolean other;
                    void m() {
                        int a = n & flag;
                        int b = flag | n;
                        n |= flag;
                        int c = (n) & (flag && other);
                    }
                }
                """);
        assertTrue(updated.contains("int a = n & (flag ? 1 : 0);"), updated);
        assertTrue(updated.contains("int b = (flag ? 1 : 0) | n;"), updated);
        assertTrue(updated.contains("n |= flag ? 1 : 0;"), updated);
        assertTrue(updated.contains("(n) & (flag && other ? 1 : 0)") || updated.contains("(n) & ((flag && other) ? 1 : 0)"),
                updated);
        assertCompiles(root, updated);
    }

    @Test
    void operatorsThatNeedBooleansGetNotEqualZero(@TempDir Path root) throws IOException {
        String updated = fixUntilStable(root, "rs/E.java", """
                package rs;

                public class E {
                    boolean f;
                    int n;
                    boolean m() {
                        boolean a = f && n;
                        boolean b = n || f;
                        boolean c = !n;
                        return a && b && c;
                    }
                }
                """);
        assertTrue(updated.contains("f && n != 0") || updated.contains("f && (n != 0)"), updated);
        assertTrue(updated.contains("n != 0 || f") || updated.contains("(n != 0) || f"), updated);
        assertTrue(updated.contains("n == 0"), updated);
        assertCompiles(root, updated);
    }

    @Test
    void mixedEqualityComparesAsIntsOrLiterals(@TempDir Path root) throws IOException {
        String updated = fixUntilStable(root, "rs/F.java", """
                package rs;

                public class F {
                    boolean f;
                    int n;
                    boolean m() {
                        boolean a = f == 1;
                        boolean b = f != n;
                        return a && b;
                    }
                }
                """);
        assertTrue(updated.contains("f == true"), updated);
        assertTrue(updated.contains("(f ? 1 : 0) != n"), updated);
        assertCompiles(root, updated);
    }

    @Test
    void precedenceIsPreservedForComplexOperands(@TempDir Path root) throws IOException {
        String updated = fixUntilStable(root, "rs/G.java", """
                package rs;

                public class G {
                    int a;
                    int b;
                    boolean m() { return this.a & this.b; }
                    boolean cast() { return (boolean) (this.a | this.b); }
                }
                """);
        assertTrue(updated.contains("return (this.a & this.b) != 0;"), updated);
    }

    @Test
    void leavesUnrelatedConversionsAlone(@TempDir Path root) throws IOException {
        Path file = root.resolve("rs/H.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package rs;

                public class H {
                    String s;
                    int m(long v) { return v; }
                    boolean n(String t) { return t; }
                }
                """);
        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var result = SpanFixers.run(root, bucketer.categorize(outcome.diagnostics()),
                List.of(new PrimitiveCoercionFixer()));
        assertEquals(0, result.fixes(), "long->int and String->boolean are real type errors");
    }

    @Test
    void handledDiagnosticsAreWithheldFromLaterFixers(@TempDir Path root) throws IOException {
        Path file = root.resolve("rs/I.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package rs;

                public class I {
                    boolean m(int v) { return v; }
                }
                """);
        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var bucketed = bucketer.categorize(outcome.diagnostics());
        var result = SpanFixers.run(root, bucketed, List.of(new PrimitiveCoercionFixer()));
        assertEquals(1, result.fixes());
        assertTrue(result.unhandled(bucketed).isEmpty());
    }

    @Test
    void preservesCrlfLineEndingsAndUntouchedText(@TempDir Path root) throws IOException {
        Path file = root.resolve("rs/J.java");
        Files.createDirectories(file.getParent());
        String source = "package rs;\r\n\r\npublic class J {\r\n    // caf\u00e9 \u00fcber\r\n    boolean m(int v) { return v; }\r\n}\r\n";
        Files.writeString(file, source);
        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        SpanFixers.run(root, bucketer.categorize(outcome.diagnostics()), List.of(new PrimitiveCoercionFixer()));
        assertEquals(source.replace("return v;", "return v != 0;"), Files.readString(file));
    }
}
