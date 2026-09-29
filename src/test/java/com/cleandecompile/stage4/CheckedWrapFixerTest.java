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
    void rethrowsUncheckedInValueReturningMethods(@TempDir Path root) throws IOException {
        // The catch path of a value-returning method has no value to hand back, so swallowing the
        // exception would trade the unreported-exception error for a missing-return one. Rethrowing
        // (unchecked) keeps the method's contract and the failure loud.
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

        assertEquals(1, fixed);
        String updated = Files.readString(root.resolve("rs/E.java"));
        assertTrue(updated.contains("throw new java.io.UncheckedIOException("), "missing rethrow:\n" + updated);
        assertTrue(!updated.contains("catch (IOException ignored)"), "must not swallow here:\n" + updated);
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean compile after wrap: " + after.diagnostics());
    }

    @Test
    void rethrowsNonIoCheckedExceptionsAsRuntime(@TempDir Path root) throws IOException {
        write(root, "rs/F.java", """
                package rs;

                public class F {
                    private String name(Class<?> type) {
                        Object o = type.getDeclaredConstructor().newInstance();
                        return String.valueOf(o);
                    }
                }
                """);
        // Several checked exceptions in one expression surface one per round; the loop keeps going.
        int total = 0;
        for (int round = 0; round < 6; round++) {
            var outcome = javac.compile(root, root.resolveSibling("classes" + round), List.of(), "17");
            if (outcome.success()) break;
            int fixed = CompileFixLoop.CheckedWrapFixer.tryFixAll(
                    root, bucketer.categorize(outcome.diagnostics()));
            if (fixed == 0) break;
            total += fixed;
        }
        assertTrue(total >= 1);
        String updated = Files.readString(root.resolve("rs/F.java"));
        assertTrue(updated.contains("throw new RuntimeException(stage4Checked);"), updated);
        assertTrue(javac.compile(root, root.resolveSibling("classesFinal"), List.of(), "17").success(), updated);
    }

    @Test
    void sameExceptionTwiceInOneMethodWrapsOnce(@TempDir Path root) throws IOException {
        write(root, "rs/G.java", """
                package rs;

                import java.io.RandomAccessFile;

                public class G {
                    private int both(RandomAccessFile file) {
                        file.seek(0L);
                        file.seek(8L);
                        return 1;
                    }
                }
                """);
        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertEquals(2, outcome.diagnostics().size());
        CompileFixLoop.CheckedWrapFixer.tryFixAll(root, bucketer.categorize(outcome.diagnostics()));
        String updated = Files.readString(root.resolve("rs/G.java"));
        assertEquals(1, updated.split("catch \\(IOException", -1).length - 1, updated);
        assertTrue(javac.compile(root, root.resolveSibling("classes2"), List.of(), "17").success(), updated);
    }

    @Test
    void wrapsInsideMethodsWhoseHeaderStartsWithSynchronized(@TempDir Path root) throws IOException {
        // A package-private synchronized method: the header begins with the keyword, which the
        // block-form guard ("synchronized (lock) {") must not mistake for a statement.
        write(root, "rs/H.java", """
                package rs;

                import java.io.RandomAccessFile;

                public class H {
                    private RandomAccessFile file;

                    synchronized int read(int pos) {
                        this.file.seek((long) pos);
                        return this.file.read();
                    }
                }
                """);
        int total = 0;
        for (int round = 0; round < 4; round++) {
            var outcome = javac.compile(root, root.resolveSibling("classes" + round), List.of(), "17");
            if (outcome.success()) break;
            int fixed = CompileFixLoop.CheckedWrapFixer.tryFixAll(
                    root, bucketer.categorize(outcome.diagnostics()));
            if (fixed == 0) break;
            total += fixed;
        }
        assertTrue(total >= 1);
        assertTrue(javac.compile(root, root.resolveSibling("final"), List.of(), "17").success(),
                Files.readString(root.resolve("rs/H.java")));
    }

    @Test
    void neverTreatsASynchronizedBlockAsAMethodHeader(@TempDir Path root) throws IOException {
        write(root, "rs/I.java", """
                package rs;

                import java.io.RandomAccessFile;

                public class I {
                    private final Object lock = new Object();

                    void run(RandomAccessFile file) {
                        synchronized (this.lock) {
                            file.seek(0L);
                        }
                    }
                }
                """);
        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        CompileFixLoop.CheckedWrapFixer.tryFixAll(root, bucketer.categorize(outcome.diagnostics()));
        String updated = Files.readString(root.resolve("rs/I.java"));
        int tryAt = updated.indexOf("try {");
        int syncAt = updated.indexOf("synchronized (this.lock)");
        assertTrue(tryAt >= 0 && tryAt < syncAt, "wrap must enclose the whole `run` body, not the block:\n" + updated);
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
