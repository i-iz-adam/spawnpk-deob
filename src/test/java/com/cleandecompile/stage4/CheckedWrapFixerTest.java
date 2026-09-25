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
 * A lone throwing call in a method that declares no {@code throws} (adding
 * {@code throws} would cascade into every caller) is wrapped in
 * try/catch -- the same idiom the decompiled sources already use next door.
 */
class CheckedWrapFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void wrapsThrowingCallInTryCatch(@TempDir Path root) throws IOException {
        write(root, "rs/Cache.java", """
                package rs;

                import java.io.RandomAccessFile;

                public class Cache {
                    private synchronized void seek(RandomAccessFile file, int pos) {
                        file.seek((long) pos);
                    }

                    public synchronized byte[] read(int pos) {
                        this.seek(null, pos * 6);
                        return new byte[0];
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().size() > 0, "expected unreported-exception error");

        int fixed = CompileFixLoop.CheckedWrapFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, fixed);
        String updated = Files.readString(root.resolve("rs/Cache.java"));
        assertTrue(updated.contains("catch (IOException"), "missing catch:\n" + updated);
        assertTrue(updated.contains("import java.io.IOException;"), "missing import:\n" + updated);
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean compile after wrap");
    }

    @Test
    void leavesValueReturningMethodsAlone(@TempDir Path root) throws IOException {
        // Wrapping the whole body would leave the catch path without a
        // return value -- a missing-return error replacing the current one.
        write(root, "rs/E.java", """
                package rs;

                import java.io.RandomAccessFile;

                public class E {
                    private synchronized int position(RandomAccessFile file) {
                        file.seek(0L);
                        return 1;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().size() > 0, "expected unreported-exception error");

        int fixed = CompileFixLoop.CheckedWrapFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, fixed);
    }

    @Test
    void leavesDeclaringMethodsAlone(@TempDir Path root) throws IOException {
        write(root, "rs/D.java", """
                package rs;

                import java.io.IOException;
                import java.io.RandomAccessFile;

                public class D {
                    void seek(RandomAccessFile file, int pos) throws IOException {
                        file.seek((long) pos);
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.success(), "fixture must compile cleanly");

        int fixed = CompileFixLoop.CheckedWrapFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, fixed);
    }
}
