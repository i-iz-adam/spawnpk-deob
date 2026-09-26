package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * javac names the exact type a for-each loop variable must have: when the
 * first type of a {@code ==}/{@code !=} comparison is {@code Object} and the
 * second is a primitive, the decompiler simply dropped the element type off
 * the loop variable's declaration. Retyping that declaration is line
 * preserving and has no runtime effect, since erasure discards the type
 * argument -- but only once the iterable's element type is provably that
 * same primitive, and only while the rest of the body still treats the
 * variable as a reference.
 */
class InferredTypeFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

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

    private static void assertOperandMismatch(JavacRunner.CompileOutcome outcome, String firstType,
                                              String secondType) {
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> {
            var m = d.getMessage(java.util.Locale.ENGLISH);
            return m.contains("bad operand types for binary operator '!='")
                    && m.contains("first type:  " + firstType)
                    && m.contains("second type: " + secondType);
        }), "fixture must produce the " + firstType + "-versus-" + secondType + " operand mismatch, got: "
                + outcome.diagnostics().stream()
                        .map(d -> d.getMessage(java.util.Locale.ENGLISH)).toList());
    }

    private static void assertObjectVersus(JavacRunner.CompileOutcome outcome, String secondType) {
        assertOperandMismatch(outcome, "java.lang.Object", secondType);
    }

    private static void assertSomeOperandMismatch(JavacRunner.CompileOutcome outcome) {
        assertTrue(outcome.diagnostics().stream().anyMatch(d ->
                        d.getMessage(java.util.Locale.ENGLISH)
                                .contains("bad operand types for binary operator '!='")),
                "fixture must produce the operand mismatch, got: "
                        + outcome.diagnostics().stream()
                                .map(d -> d.getMessage(java.util.Locale.ENGLISH)).toList());
    }

    @Test
    void retypesAForEachVariableToCharFromToCharArray(@TempDir Path root) throws IOException {
        write(root, "rs/B1.java", """
                package rs;

                public class B1 {
                    void method1(String string) {
                        char c = 'a';
                        for (Object object : string.toLowerCase().toCharArray()) {
                            if (object != c) continue;
                        }
                        System.out.println(c);
                    }
                }
                """);

        var outcome = compile(root);
        assertObjectVersus(outcome, "char");

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = read(root, "rs/B1.java");
        assertTrue(updated.contains("for (char object : string.toLowerCase().toCharArray()) {"),
                "the declaration line must carry the retyped variable:\n" + updated);
        var after = compile(root);
        assertTrue(after.success(), "expected clean compile after retyping");
    }

    @Test
    void retypesAForEachVariableToIntFromAnArrayParameter(@TempDir Path root) throws IOException {
        write(root, "rs/B2.java", """
                package rs;

                public class B2 {
                    void method1(int[] numbers) {
                        int c = 1;
                        for (Object object : numbers) {
                            if (object != c) continue;
                        }
                        System.out.println(c);
                    }
                }
                """);

        var outcome = compile(root);
        assertObjectVersus(outcome, "int");

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = read(root, "rs/B2.java");
        assertTrue(updated.contains("for (int object : numbers) {"),
                "the declaration line must carry the retyped variable:\n" + updated);
        var after = compile(root);
        assertTrue(after.success(), "expected clean compile after retyping");
    }

    @Test
    void retypesAForEachVariableToCharFromAnArrayLocal(@TempDir Path root) throws IOException {
        write(root, "rs/B3.java", """
                package rs;

                public class B3 {
                    void method1(String string) {
                        char[] chars = string.toCharArray();
                        char c = 'a';
                        for (Object object : chars) {
                            if (object != c) continue;
                        }
                        System.out.println(c);
                    }
                }
                """);

        var outcome = compile(root);
        assertObjectVersus(outcome, "char");

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = read(root, "rs/B3.java");
        assertTrue(updated.contains("for (char object : chars) {"),
                "the declaration line must carry the retyped variable:\n" + updated);
        var after = compile(root);
        assertTrue(after.success(), "expected clean compile after retyping");
    }

    @Test
    void bailsWhenTheSecondOperandTypeIsNotPrimitive(@TempDir Path root) throws IOException {
        write(root, "rs/B4.java", """
                package rs;

                public class B4 {
                    void method1(String[] names) {
                        for (char c : names[0].toCharArray()) {
                            Object object = names[0];
                            if (c != object) continue;
                        }
                    }
                }
                """);
        String before = read(root, "rs/B4.java");

        var outcome = compile(root);
        assertOperandMismatch(outcome, "char", "java.lang.Object");

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "only a primitive second operand proves the loop variable is a primitive");
        assertEquals(before, read(root, "rs/B4.java"));
    }

    @Test
    void bailsWhenTheComparisonIsAgainstAnArray(@TempDir Path root, @TempDir Path other) throws IOException {
        write(root, "rs/B5.java", """
                package rs;

                public class B5 {
                    void method1(int[] intArray) {
                        char c = 'a';
                        for (char o : intArray) {
                            if (o != intArray) continue;
                        }
                    }
                }
                """);
        String before = read(root, "rs/B5.java");

        var outcome = compile(root);
        assertOperandMismatch(outcome, "char", "int[]");

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "an array second operand is not a primitive and must never be truncated to one");
        assertEquals(before, read(root, "rs/B5.java"));

        write(other, "rs/B5b.java", """
                package rs;

                public class B5b {
                    void method1(int[] intArray) {
                        int[] someIntArr = intArray;
                        for (Object o : intArray) {
                            if (o != someIntArr) continue;
                        }
                    }
                }
                """);
        String beforeOther = read(other, "rs/B5b.java");

        var otherOutcome = compile(other);
        var otherResult = CompileFixLoop.InferredTypeFixer.tryFixAll(
                other, bucketer.categorize(otherOutcome.diagnostics()));

        assertEquals(0, otherResult.fixes());
        assertEquals(beforeOther, read(other, "rs/B5b.java"));
    }

    @Test
    void bailsWhenTheIterableIsAnObjectArray(@TempDir Path root) throws IOException {
        write(root, "rs/B6.java", """
                package rs;

                public class B6 {
                    void method1(Object[] values) {
                        char c = 'a';
                        for (Object object : values) {
                            if (object != c) continue;
                        }
                    }
                }
                """);
        String before = read(root, "rs/B6.java");

        var outcome = compile(root);
        assertObjectVersus(outcome, "char");

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "an Object[] element is erased, so narrowing the loop variable would checkcast at runtime");
        assertEquals(before, read(root, "rs/B6.java"));
    }

    @Test
    void bailsWhenTheIterableIsAListOfObject(@TempDir Path root) throws IOException {
        write(root, "rs/B7.java", """
                package rs;

                import java.util.List;

                public class B7 {
                    void method1(List<Object> values) {
                        char c = 'a';
                        for (Object object : values) {
                            if (object != c) continue;
                        }
                    }
                }
                """);
        String before = read(root, "rs/B7.java");

        var outcome = compile(root);
        assertObjectVersus(outcome, "char");

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "a List<Object> element is erased, so narrowing the loop variable would checkcast at runtime");
        assertEquals(before, read(root, "rs/B7.java"));
    }

    @Test
    void bailsWhenTheBodyUsesTheVariableAfterTheFlaggedLine(@TempDir Path root) throws IOException {
        write(root, "rs/B8.java", """
                package rs;

                public class B8 {
                    void method1(String string) {
                        char c = 'a';
                        for (Object object : string.toLowerCase().toCharArray()) {
                            if (object != c) continue;
                            sink(object);
                        }
                    }

                    void sink(Object value) { }
                }
                """);
        String before = read(root, "rs/B8.java");

        var outcome = compile(root);
        assertObjectVersus(outcome, "char");

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "a later sink(object) call needs the declared reference type");
        assertEquals(before, read(root, "rs/B8.java"));
    }

    @Test
    void bailsWhenTheBodyTestsTheVariableAgainstAClass(@TempDir Path root) throws IOException {
        write(root, "rs/B9.java", """
                package rs;

                public class B9 {
                    void method1(String string) {
                        char c = 'a';
                        for (Object object : string.toLowerCase().toCharArray()) {
                            if (object != c) continue;
                            if (object instanceof Integer) continue;
                        }
                    }
                }
                """);
        String before = read(root, "rs/B9.java");

        var outcome = compile(root);
        assertObjectVersus(outcome, "char");

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "instanceof is only legal on a reference type");
        assertEquals(before, read(root, "rs/B9.java"));
    }

    @Test
    void bailsWhenTheIdentifierIsNotAForEachVariable(@TempDir Path root) throws IOException {
        write(root, "rs/B10.java", """
                package rs;

                public class B10 {
                    void method1(String string) {
                        char c = 'a';
                        Object object = string.toCharArray()[0];
                        if (object != c) continue;
                        System.out.println(object);
                    }
                }
                """);
        String before = read(root, "rs/B10.java");

        var outcome = compile(root);
        assertSomeOperandMismatch(outcome);

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(), "no enclosing for-each header declares the operand");
        assertEquals(before, read(root, "rs/B10.java"));
    }

    @Test
    void bailsWhenAMethodHeaderIntervenesBeforeTheForEachHeader(@TempDir Path root) throws IOException {
        write(root, "rs/B11.java", """
                package rs;

                public class B11 {
                    void method1(String string) {
                        for (Object object : string.toCharArray()) {
                            System.out.println(object);
                        }
                    }

                    void method2(String string) {
                        char c = 'a';
                        Object object = string.toCharArray()[0];
                        if (object != c) continue;
                        System.out.println(object);
                    }
                }
                """);
        String before = read(root, "rs/B11.java");

        var outcome = compile(root);
        assertSomeOperandMismatch(outcome);

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(), "the only for-each header lives in a different method");
        assertEquals(before, read(root, "rs/B11.java"));
    }

    @Test
    void bailsWhenTheHeaderLineCarriesASecondForHeader(@TempDir Path root) throws IOException {
        write(root, "rs/B12.java", """
                package rs;

                public class B12 {
                    void method1(String string) {
                        char c = 'a';
                        for (Object object : string.toCharArray()) { for (char other : new char[]{'b'}) { if (object != c) continue; } }
                    }
                }
                """);
        String before = read(root, "rs/B12.java");

        var outcome = compile(root);
        assertSomeOperandMismatch(outcome);

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(), "two for headers on one line make the declaration site ambiguous");
        assertEquals(before, read(root, "rs/B12.java"));
    }

    @Test
    void neverReadsForHeadersInsideStringsOrComments(@TempDir Path root) throws IOException {
        write(root, "rs/B13.java", """
                package rs;

                public class B13 {
                    void method1(String string) {
                        String trick = "for (Object object : new char[]{'q'}) ";
                        /*
                        for (Object object : new char[]{'r'}) {
                        */
                        char c = 'a';
                        Object object = string.toCharArray()[0];
                        if (object != c) continue;
                        System.out.println(object);
                    }
                }
                """);
        String before = read(root, "rs/B13.java");

        var outcome = compile(root);
        assertSomeOperandMismatch(outcome);

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "a for header inside a string literal or a block comment is not code");
        assertEquals(before, read(root, "rs/B13.java"));
    }

    @Test
    void bailsWhenTheIterableIsAUserDefinedToIntArray(@TempDir Path root) throws IOException {
        write(root, "rs/B17.java", """
                package rs;

                public class B17 {
                   static class Helper {
                      Integer[] toIntArray() { return new Integer[0]; }
                   }

                   void method1(Helper helper) {
                      int c = 1;
                      for (Object object : helper.toIntArray()) {
                         if (object != c) continue;
                      }
                   }
                }
                """);
        String before = read(root, "rs/B17.java");

        var outcome = compile(root);
        assertObjectVersus(outcome, "int");

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "no JDK type returns int[], so a boxed toIntArray() is not a primitive producer");
        assertEquals(before, read(root, "rs/B17.java"));
    }

    @Test
    void bailsWhenTheIterableIsAUserDefinedToBooleanArray(@TempDir Path root) throws IOException {
        write(root, "rs/B18.java", """
                package rs;

                public class B18 {
                   static class Helper {
                      Boolean[] toBooleanArray() { return new Boolean[0]; }
                   }

                   void method1(Helper helper) {
                      boolean c = true;
                      for (Object object : helper.toBooleanArray()) {
                         if (object != c) continue;
                      }
                   }
                }
                """);
        String before = read(root, "rs/B18.java");

        var outcome = compile(root);
        assertObjectVersus(outcome, "boolean");

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "no JDK type returns boolean[], so a boxed toBooleanArray() is not a primitive producer");
        assertEquals(before, read(root, "rs/B18.java"));
    }

    @Test
    void retypesTheLoopVariableForEveryPrimitiveArrayLocal(@TempDir Path root) throws IOException {
        write(root, "rs/B19.java", """
                package rs;

                public class B19 {
                   void method1() {
                      boolean[] bools = new boolean[0];
                      byte[] bytes = new byte[0];
                      char[] chars = new char[0];
                      short[] shorts = new short[0];
                      int[] ints = new int[0];
                      long[] longs = new long[0];
                      float[] floats = new float[0];
                      double[] doubles = new double[0];
                      boolean v1 = true;
                      byte v2 = 1;
                      char v3 = 'a';
                      short v4 = 1;
                      int v5 = 1;
                      long v6 = 1L;
                      float v7 = 1f;
                      double v8 = 1d;
                      for (Object o1 : bools) {
                         if (o1 != v1) continue;
                      }
                      for (Object o2 : bytes) {
                         if (o2 != v2) continue;
                      }
                      for (Object o3 : chars) {
                         if (o3 != v3) continue;
                      }
                      for (Object o4 : shorts) {
                         if (o4 != v4) continue;
                      }
                      for (Object o5 : ints) {
                         if (o5 != v5) continue;
                      }
                      for (Object o6 : longs) {
                         if (o6 != v6) continue;
                      }
                      for (Object o7 : floats) {
                         if (o7 != v7) continue;
                      }
                      for (Object o8 : doubles) {
                         if (o8 != v8) continue;
                      }
                   }
                }
                """);

        var outcome = compile(root);
        assertEquals(8, outcome.diagnostics().size(),
                "each primitive array local must produce one Object-versus-primitive mismatch: "
                        + outcome.diagnostics().stream()
                                .map(d -> d.getMessage(java.util.Locale.ENGLISH)).toList());

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(8, result.fixes(), "a <prim>[] local proves its own element type for every primitive");
        var after = compile(root);
        assertTrue(after.success(), "expected clean compile after retyping every primitive");
    }

    @Test
    void reRunningWithTheOriginalDiagnosticsYieldsNoFurtherRetype(@TempDir Path root) throws IOException {
        write(root, "rs/B14.java", """
                package rs;

                public class B14 {
                    void method1(String string) {
                        char c = 'a';
                        for (Object object : string.toLowerCase().toCharArray()) {
                            if (object != c) continue;
                        }
                        System.out.println(c);
                    }
                }
                """);

        var diagnostics = bucketer.categorize(compile(root).diagnostics());
        assertEquals(1, CompileFixLoop.InferredTypeFixer.tryFixAll(root, diagnostics).fixes());
        String afterFirst = read(root, "rs/B14.java");

        var repeated = CompileFixLoop.InferredTypeFixer.tryFixAll(root, diagnostics);

        assertEquals(0, repeated.fixes(),
                "the first type is no longer Object, so the same list must find nothing left to do");
        assertEquals(afterFirst, read(root, "rs/B14.java"));
    }

    @Test
    void appliesOnceWhenTheSameDiagnosticIsListedTwice(@TempDir Path root) throws IOException {
        write(root, "rs/B15.java", """
                package rs;

                public class B15 {
                    void method1(String string) {
                        char c = 'a';
                        for (Object object : string.toLowerCase().toCharArray()) {
                            if (object != c) continue;
                        }
                        System.out.println(c);
                    }
                }
                """);

        var bucketed = bucketer.categorize(compile(root).diagnostics());
        var doubled = new ArrayList<DiagnosticBucketer.Bucketed>(bucketed);
        doubled.addAll(bucketed);

        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(root, doubled);

        assertEquals(1, result.fixes(), "one line, one message, one fix");
        assertEquals(1, result.handled().size());
        assertTrue(read(root, "rs/B15.java")
                        .contains("for (char object : string.toLowerCase().toCharArray()) {"),
                "the retyped declaration must appear exactly once");
    }

    @Test
    void unhandledWithholdsOnlyTheRetypeDiagnosticsItAddressed(@TempDir Path root) throws IOException {
        write(root, "rs/B16.java", """
                package rs;

                public class B16 {
                    void method1(String string) {
                        char c = 'a';
                        for (Object object : string.toLowerCase().toCharArray()) {
                            if (object != c) continue;
                        }
                        System.out.println(c);
                    }
                }
                """);
        write(root, "rs/B16Other.java", """
                package rs;

                public class B16Other {
                    Missing value;
                }
                """);

        var diagnostics = bucketer.categorize(compile(root).diagnostics());
        var result = CompileFixLoop.InferredTypeFixer.tryFixAll(root, diagnostics);
        var remaining = result.unhandled(diagnostics);

        assertEquals(1, result.fixes());
        assertEquals(1, result.handled().size());
        assertEquals(1, remaining.size(), "the missing-class diagnostic survives for ImportInserter");
        assertTrue(remaining.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH).contains("class Missing"),
                remaining.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));
    }
}
