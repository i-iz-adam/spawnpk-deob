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
 * When one slot holds Strings in one region and arrays in another, retyping
 * the declaration is unsound. Splitting the String region into a fresh
 * {@code String} local fixes the cluster without touching the array uses.
 */
class VarSplitFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void splitsStringRegionOutOfReusedArraySlot(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    void m(String string, int mode) {
                        String[] stringArray;
                        if (mode == 2) {
                            stringArray = (String[]) string.toLowerCase();
                            if (stringArray.startsWith("::x")) {
                                System.out.println(stringArray.replace("::x", ""));
                            }
                        }
                        for (String s : stringArray = new String[]{"a", "b"}) {
                            System.out.println(s);
                        }
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().size() >= 2, "expected String/String[] cluster");

        var result = CompileFixLoop.VarSplitFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/A.java"));
        assertTrue(updated.contains("String stringArrayStr = string.toLowerCase();"),
                "missing split declaration:\n" + updated);
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean compile after split");
    }

    @Test
    void leavesGenuineArraysAlone(@TempDir Path root) throws IOException {
        write(root, "rs/B.java", """
                package rs;

                public class B {
                    int m(String string) {
                        String[] parts = string.split(",");
                        return parts.length;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.success(), "fixture must compile cleanly");

        var result = CompileFixLoop.VarSplitFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes());
    }
}
