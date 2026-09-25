package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LambdaRestoreFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    @Test
    void rewritesUniqueInScopeCaptureAndRecompilesCleanly(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package rs;

                public class A {
                    private void method2997(Class43 var1) {}

                    void run(Class43 var3) {
                        Runnable task = this::method2997;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var diagnostics = bucketer.categorize(outcome.diagnostics());
        int fixed = CompileFixLoop.LambdaRestoreFixer.tryFixAll(root, diagnostics).fixes();

        assertEquals(1, fixed);
        assertTrue(Files.readString(file).contains("Runnable task = () -> this.method2997(var3);"));
        assertTrue(javac.compile(root, root.resolveSibling("classes-after"), List.of(), "17").success(),
                Files.readString(file));
    }

    @Test
    void leavesAmbiguousSameTypeCapturesUntouched(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package rs;

                public class A {
                    private void method2997(Class43 var1) {}

                    void run(Class43 first, Class43 second) {
                        Runnable task = this::method2997;
                    }
                }
                """);
        String before = Files.readString(file);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        int fixed = CompileFixLoop.LambdaRestoreFixer.tryFixAll(root, bucketer.categorize(outcome.diagnostics())).fixes();

        assertEquals(0, fixed);
        assertEquals(before, Files.readString(file));
    }

    @Test
    void secondCallWithSameDiagnosticsIsIdempotent(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package rs;

                public class A {
                    private void method2997(Class43 var1) {}

                    void run(Class43 var3) {
                        Runnable task = this::method2997;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var diagnostics = bucketer.categorize(outcome.diagnostics());
        assertEquals(1, CompileFixLoop.LambdaRestoreFixer.tryFixAll(root, diagnostics).fixes());
        assertEquals(0, CompileFixLoop.LambdaRestoreFixer.tryFixAll(root, diagnostics).fixes());
        assertEquals(1, Files.readString(file).lines().filter(line -> line.contains("this.method2997(var3)")).count());
    }

    @Test
    void ignoresMethodReferenceTextInStringAndComment(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package rs;

                public class A {
                    private void method2997(Class43 var1) {}

                    void run(Class43 var3) {
                        String text = "this::method2997"; /* this::method2997 */ Runnable task = this::method2997;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var diagnostics = bucketer.categorize(outcome.diagnostics());
        int fixed = CompileFixLoop.LambdaRestoreFixer.tryFixAll(root, diagnostics).fixes();

        assertEquals(1, fixed);
        assertTrue(Files.readString(file).contains("Runnable task = () -> this.method2997(var3);"));
    }

    @Test
    void rejectsAllJavaMutationForms(@TempDir Path root) throws IOException {
        for (String mutation : List.of("++var3", "--var3", "var3 <<= 1", "var3 >>= 1", "var3 >>>= 1")) {
            Path caseRoot = Files.createTempDirectory(root, "mutation");
            Path file = write(caseRoot, """
                    package rs;

                    public class A {
                        private void method2997(int var1) {}

                        void run(int var3) {
                            Runnable task = this::method2997;
                            %s;
                        }
                    }
                    """.formatted(mutation));
            String before = Files.readString(file);
            var outcome = javac.compile(caseRoot, caseRoot.resolveSibling("classes"), List.of(), "17");
            int fixed = CompileFixLoop.LambdaRestoreFixer.tryFixAll(caseRoot,
                    bucketer.categorize(outcome.diagnostics())).fixes();

            assertEquals(0, fixed, mutation);
            assertEquals(before, Files.readString(file), mutation);
        }
    }

    @Test
    void rejectsVarargsTarget(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package rs;

                public class A {
                    private void method2997(Class43... var1) {}

                    void run(Class43 var3) {
                        Runnable task = this::method2997;
                    }
                }
                """);
        String before = Files.readString(file);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        int fixed = CompileFixLoop.LambdaRestoreFixer.tryFixAll(root,
                bucketer.categorize(outcome.diagnostics())).fixes();

        assertEquals(0, fixed);
        assertEquals(before, Files.readString(file));
    }

    @Test
    void handlesBracesInsideBlockComments(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package rs;

                public class A {
                    private void method2997(Class43 var1) {}

                    void run(Class43 var3) {
                        /* } */
                        Runnable task = this::method2997;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        int fixed = CompileFixLoop.LambdaRestoreFixer.tryFixAll(root,
                bucketer.categorize(outcome.diagnostics())).fixes();

        assertEquals(1, fixed);
        assertTrue(Files.readString(file).contains("Runnable task = () -> this.method2997(var3);"));
    }

    @Test
    void rejectsCaptureDeclaredInClosedBlock(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package rs;

                public class A {
                    private void method2997(Class43 var1) {}

                    void run() {
                        {
                            Class43 var3 = new Class43();
                        }
                        Runnable task = this::method2997;
                    }
                }
                """);
        String before = Files.readString(file);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        int fixed = CompileFixLoop.LambdaRestoreFixer.tryFixAll(root,
                bucketer.categorize(outcome.diagnostics())).fixes();

        assertEquals(0, fixed);
        assertEquals(before, Files.readString(file));
    }

    @Test
    void rejectsMultipleMethodReferencesOnOneLine(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package rs;

                public class A {
                    private void method2997(Class43 var1) {}

                    void run(Class43 var3) {
                        Runnable first = this::method2997; Runnable second = this::method2997;
                    }
                }
                """);
        String before = Files.readString(file);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        int fixed = CompileFixLoop.LambdaRestoreFixer.tryFixAll(root,
                bucketer.categorize(outcome.diagnostics())).fixes();

        assertEquals(0, fixed);
        assertEquals(before, Files.readString(file));
    }

    @Test
    void unhandledFeedsMixedDiagnosticsToRawCastFixerInOrder(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package rs;

                public class A {
                    private void method2997(Class43 var1) {}

                    void run(Class43 var3) {
                        Runnable task = this::method2997; Object text = "x"; String value = text;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var diagnostics = bucketer.categorize(outcome.diagnostics());
        var result = CompileFixLoop.LambdaRestoreFixer.tryFixAll(root, diagnostics);
        var remaining = result.unhandled(diagnostics);
        int rawFixes = CompileFixLoop.RawCastFixer.tryFixAll(root, remaining);
        String updated = Files.readString(file);

        assertEquals(1, result.fixes());
        assertEquals(1, remaining.size());
        assertEquals(1, rawFixes);
        assertTrue(updated.contains("Runnable task = () -> this.method2997(var3);"));
        assertTrue(updated.contains("String value = (java.lang.String) text;"), updated);
        assertTrue(updated.indexOf("() -> this.method2997(var3)")
                < updated.indexOf("(java.lang.String) text"));
    }

    @Test
    void ignoresMethodReferenceTextInTextBlock(@TempDir Path root) throws IOException {
        String source = String.join("\n",
                "package rs;",
                "",
                "public class A {",
                "    private void method2997(Class43 var1) {}",
                "",
                "    void run(Class43 var3) {",
                "        String text = \"\"\"",
                "        this::method2997 \" } \\\\",
                "        \"\"\";",
                "        Runnable task = this::method2997;",
                "    }",
                "}",
                "");
        Path file = write(root, source);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var diagnostics = bucketer.categorize(outcome.diagnostics());
        int fixed = CompileFixLoop.LambdaRestoreFixer.tryFixAll(root, diagnostics).fixes();

        assertEquals(1, fixed);
        assertTrue(Files.readString(file).contains("Runnable task = () -> this.method2997(var3);"));
    }

    @Test
    void sharedMethodEndIgnoresLiteralAndCommentBraces() {
        List<String> lines = List.of(
                "void run() {",
                "    String text = \"}\";",
                "    /* } */",
                "    int value = 1;",
                "}",
                "}");

        assertEquals(4, CompileFixLoop.methodEnd(lines, 0));
    }

    @Test
    void sharedMethodEndSeesCodeAfterATextBlockWhoseContentLineCarriesAQuoteRun() {
        List<String> lines = List.of(
                "void run() {",
                "    String text = \"\"\"",
                "    say \"\"\"\" and more",
                "    a\"b",
                "    \"\"\";",
                "    int value = 1;",
                "}");

        assertEquals(6, CompileFixLoop.methodEnd(lines, 0),
                "a content quote run is not a terminator; the code after the block must stay visible");
    }

    @Test
    void sharedMethodEndSeesCodeAfterATextBlockWhoseContentLineEscapesTheQuoteRun() {
        List<String> lines = List.of(
                "void run() {",
                "    String text = \"\"\"",
                "    say \\\"\\\"\\\" and } more",
                "    \"\"\";",
                "    int value = 1;",
                "}");

        assertEquals(5, CompileFixLoop.methodEnd(lines, 0));
    }

    @Test
    void unhandledPreservesTwoLaterDiagnosticsForRawCastFixer(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package rs;

                public class A {
                    private void method2997(Class43 var1) {}

                    void run(Class43 var3) {
                        Runnable task = this::method2997;
                        Object first = "x";
                        String firstValue = first;
                        Object second = "y";
                        String secondValue = second;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var diagnostics = bucketer.categorize(outcome.diagnostics());
        var result = CompileFixLoop.LambdaRestoreFixer.tryFixAll(root, diagnostics);
        long handledLine = diagnostics.stream()
                .filter(b -> b.diagnostic().getMessage(java.util.Locale.ENGLISH)
                        .contains("invalid method reference"))
                .findFirst().orElseThrow().diagnostic().getLineNumber();
        var remaining = result.unhandled(diagnostics);
        int rawFixes = CompileFixLoop.RawCastFixer.tryFixAll(root, remaining);
        String updated = Files.readString(file);

        assertEquals(1, result.fixes());
        assertEquals(2, remaining.size());
        assertTrue(remaining.get(0).diagnostic().getLineNumber()
                < remaining.get(1).diagnostic().getLineNumber());
        assertTrue(remaining.stream().noneMatch(b -> b.diagnostic().getLineNumber() == handledLine));
        assertEquals(2, rawFixes);
        assertTrue(updated.contains("String firstValue = (java.lang.String) first;"));
        assertTrue(updated.contains("String secondValue = (java.lang.String) second;"));
        assertTrue(updated.indexOf("String firstValue = (java.lang.String) first;")
                < updated.indexOf("String secondValue = (java.lang.String) second;"));
    }

    @Test
    void doesNotTouchDiagnosticFreeFile(@TempDir Path root) throws IOException {
        Path file = write(root, """
                package rs;

                public class A {
                    private void method2997(Class43 var1) {}

                    void run() {
                        Runnable task = () -> this.method2997(new Class43());
                    }
                }
                """);
        String before = Files.readString(file);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.success());
        assertEquals(0, CompileFixLoop.LambdaRestoreFixer.tryFixAll(root,
                bucketer.categorize(outcome.diagnostics())).fixes());
        assertEquals(before, Files.readString(file));
    }

    private static Path write(Path root, String source) throws IOException {
        Path file = root.resolve("rs/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(root.resolve("rs/Class43.java"), "package rs; public class Class43 {}\n");
        Files.writeString(file, source);
        return file;
    }
}
