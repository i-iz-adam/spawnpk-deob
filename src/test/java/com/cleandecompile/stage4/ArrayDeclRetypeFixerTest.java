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
 * Slot reuse leaves {@code String[] v} declarations holding Strings: every
 * assignment is a String and every use demands String methods. Retyping the
 * declaration clears the whole cluster at once (the now-bogus casts are
 * stripped by the existing cast fixer on the next round).
 */
class ArrayDeclRetypeFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void retypesStringArrayDeclarationWithUnanimousStringEvidence(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    void m(String string) {
                        String[] stringArray;
                        stringArray = string.toLowerCase();
                        if (stringArray.startsWith("::x")) {
                            System.out.println(stringArray.replace("::x", ""));
                        }
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().size() >= 2, "expected cluster of String/String[] errors");

        var result = CompileFixLoop.ArrayDeclRetypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/A.java"));
        assertTrue(updated.contains("String stringArray;"),
                "missing retyped declaration:\n" + updated);
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean compile after retype");
    }

    @Test
    void findsDeclarationAcrossControlFlowBlocks(@TempDir Path root) throws IOException {
        // The declaration sits before an if block, the uses inside it: an
        // if header must never count as the method boundary.
        write(root, "rs/C.java", """
                package rs;

                public class C {
                    void m(String string, int mode) {
                        String[] stringArray;
                        if (mode == 2) {
                            stringArray = string.toLowerCase();
                            if (stringArray.startsWith("::x")) {
                                System.out.println(stringArray.replace("::x", ""));
                            }
                        }
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().size() >= 2, "expected cluster of String/String[] errors");

        var result = CompileFixLoop.ArrayDeclRetypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean compile after retype");
    }

    @Test
    void leavesGenuineArraysAlone(@TempDir Path root) throws IOException {
        write(root, "rs/B.java", """
                package rs;

                public class B {
                    int m(String string) {
                        String[] parts = string.split(",");
                        int n = parts.length;
                        for (String s : parts) {
                            System.out.println(s);
                        }
                        return n;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.success(), "fixture must compile cleanly");

        var result = CompileFixLoop.ArrayDeclRetypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes());
    }

    @Test
    void ignoresMethodHeadersAndDeclarationsInsideBlockComments(@TempDir Path root) throws IOException {
        write(root, "rs/F.java", """
                package rs;

                public class F {
                    void m(String string) {
                        /*
                        String[] stringArray = null;
                        */
                        String[] stringArray;
                        /*
                        void method1234() {
                        */
                        stringArray = string.toLowerCase();
                        if (stringArray.startsWith("::x")) {
                            System.out.println(stringArray.replace("::x", ""));
                        }
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().size() >= 2, "expected cluster of String/String[] errors");

        var result = CompileFixLoop.ArrayDeclRetypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes(),
                "comment text is not code: a commented header or declaration must not steer the scans");
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean compile after retype");
    }

    @Test
    void braceMatchingIgnoresBracesInsideBlockComments(@TempDir Path root) throws IOException {
        write(root, "rs/E.java", """
                package rs;

                public class E {
                    void m(String string, int other) {
                        String[] stringArray;
                        stringArray = string.toLowerCase();
                        /* } } } the method continues past this comment,
                           and it opens an if down there: { */
                        int n = stringArray.length;
                        if (stringArray[0].isEmpty()) {
                            System.out.println(n);
                        }
                    }
                }
                """);
        String before = Files.readString(root.resolve("rs/E.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().size() >= 1, "expected a String/String[] mismatch");

        var result = CompileFixLoop.ArrayDeclRetypeFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "a brace inside a block comment must not truncate the method range");
        assertEquals(before, Files.readString(root.resolve("rs/E.java")));
    }
}
