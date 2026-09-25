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
 * A raw {@code new TreeMap(...)} assigned to a parameterized target leaves
 * lambda parameters (and everything downstream) Object-typed. Adding the
 * diamond restores inference; it is semantics-preserving.
 */
class DiamondFixerTest {

    private final JavacRunner javac = new JavacRunner();

    @Test
    void addsDiamondForParameterizedTarget(@TempDir Path root) throws IOException {
        Path file = root.resolve("rs/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package rs;

                import java.util.Map;
                import java.util.TreeMap;

                public class A {
                    static class K {
                        int method2209() {
                            return 1;
                        }
                    }

                    private final Map<K, String> m = new TreeMap(
                            (var0, var1) -> Integer.compare(var0.method2209(), var1.method2209()));
                }
                """);

        var before = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(before.diagnostics().size() > 0, "expected lambda-param Object errors before fix");

        int fixed = CompileFixLoop.DiamondFixer.tryFixAll(root);

        assertEquals(1, fixed);
        assertTrue(Files.readString(file).contains("new TreeMap<>("),
                "missing diamond:\n" + Files.readString(file));
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean compile after diamond fix");
    }

    @Test
    void touchesOnlyFilesCarryingDiagnostics(@TempDir Path root) throws IOException {
        Path clean = root.resolve("rs/Clean.java");
        Files.createDirectories(clean.getParent());
        String cleanSource = """
                package rs;

                import java.util.Map;
                import java.util.TreeMap;

                public class Clean {
                    Map<String, String> m = new TreeMap();
                }
                """;
        Files.writeString(clean, cleanSource);
        Path broken = root.resolve("rs/Broken.java");
        Files.writeString(broken, """
                package rs;

                public class Broken {
                    void m() {
                        Object o = null;
                        o.foo();
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var bucketed = new DiagnosticBucketer().categorize(outcome.diagnostics());

        int fixed = CompileFixLoop.DiamondFixer.tryFixAll(root, bucketed);

        assertEquals(0, fixed, "clean file must be left alone");
        assertEquals(cleanSource, Files.readString(clean));
    }

    @Test
    void leavesRawTargetsAndAnonymousClassesAlone(@TempDir Path root) throws IOException {
        Path file = root.resolve("rs/B.java");
        Files.createDirectories(file.getParent());
        String original = """
                package rs;

                import java.util.Map;
                import java.util.TreeMap;

                public class B {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    Map raw = new TreeMap();
                    Map<String, String> explicit = new TreeMap<String, String>();
                }
                """;
        Files.writeString(file, original);

        int fixed = CompileFixLoop.DiamondFixer.tryFixAll(root);

        assertEquals(0, fixed);
        assertEquals(original, Files.readString(file));
    }
}
