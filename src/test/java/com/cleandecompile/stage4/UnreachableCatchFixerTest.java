package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code exception X is never thrown in body of corresponding try statement}
 * (the Class735 shape): the obfuscator dropped the callee's {@code throws}
 * clause but the caller's exception table still names the exception. The
 * handler must survive -- and still fire when the callee really throws.
 */
class UnreachableCatchFixerTest {

    private static final String RELEASE = "11";
    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    /** Runs the planner until javac stops reporting the diagnostic (one fix per try per round). */
    private int fixToFixpoint(Path root) throws IOException {
        int total = 0;
        for (int round = 0; round < 5; round++) {
            var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), RELEASE);
            if (outcome.success()) break;
            var result = SpanFixers.run(root, bucketer.categorize(outcome.diagnostics()),
                    List.of(new UnreachableCatchFixer()));
            if (result.fixes() == 0) break;
            total += result.fixes();
        }
        return total;
    }

    private String invoke(Path root, String className) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{root.resolveSibling("classes").toUri().toURL()})) {
            return (String) loader.loadClass(className).getMethod("run").invoke(null);
        }
    }

    @Test
    void keepsTheHandlerAndItStillFiresWhenTheCalleeReallyThrows(@TempDir Path root) throws Exception {
        write(root, "rs/Reader.java", """
                package rs;

                import java.io.FileNotFoundException;

                public class Reader {
                    // The real method threw FileNotFoundException; its throws clause is what got stripped.
                    static void open() { Reader.<RuntimeException>sneak(new FileNotFoundException("x")); }

                    @SuppressWarnings("unchecked")
                    static <T extends Throwable> void sneak(Throwable t) throws T { throw (T) t; }

                    public static String run() {
                        try {
                            open();
                            return "no";
                        } catch (FileNotFoundException e) {
                            return "caught";
                        }
                    }
                }
                """);
        String before = Files.readString(root.resolve("rs/Reader.java"));

        assertEquals(1, fixToFixpoint(root));

        String after = Files.readString(root.resolve("rs/Reader.java"));
        assertEquals(before.lines().count(), after.lines().count(), "line-neutral:\n" + after);
        assertTrue(UnreachableCatchFixer.hasMarker(after, "FileNotFoundException"), after);
        assertTrue(after.contains("catch (FileNotFoundException e)"), "handler must stay:\n" + after);
        assertEquals("caught", invoke(root, "rs.Reader"));
    }

    @Test
    void handlesMultiCatchAlternativesAndSeveralCatchesOnOneTry(@TempDir Path root) throws Exception {
        write(root, "rs/Multi.java", """
                package rs;

                import java.io.FileNotFoundException;
                import java.net.MalformedURLException;

                public class Multi {
                    static void nothing() { }

                    public static String run() {
                        try {
                            nothing();
                        } catch (FileNotFoundException | MalformedURLException e) {
                            return "multi";
                        }
                        try {
                            nothing();
                        } catch (FileNotFoundException e) {
                            return "a";
                        } catch (java.util.zip.ZipException e) {
                            return "b";
                        }
                        return "none";
                    }
                }
                """);

        int fixes = fixToFixpoint(root);

        assertEquals(4, fixes);
        assertEquals("none", invoke(root, "rs.Multi"));
        String after = Files.readString(root.resolve("rs/Multi.java"));
        assertTrue(UnreachableCatchFixer.hasMarker(after, "MalformedURLException"), after);
        assertTrue(UnreachableCatchFixer.hasMarker(after, "java.util.zip.ZipException"), after);
    }

    @Test
    void worksInsideTryWithResourcesAndLeavesGenuinelyThrownExceptionsAlone(@TempDir Path root) throws Exception {
        write(root, "rs/Res.java", """
                package rs;

                import java.io.ByteArrayInputStream;
                import java.io.IOException;
                import java.io.InputStream;

                public class Res {
                    static void nothing() { }

                    public static String run() throws Exception {
                        try (InputStream in = new ByteArrayInputStream(new byte[0])) {
                            nothing();
                            in.read();
                        } catch (IOException e) {
                            return "io";       // genuinely thrown by read(): must not get a marker
                        }
                        // StringReader.close() throws nothing, so this handler is dead as far as javac can see.
                        try (java.io.StringReader r = new java.io.StringReader("")) {
                            nothing();
                        } catch (java.io.FileNotFoundException e) {
                            return "fnf";
                        }
                        return "ok";
                    }
                }
                """);

        assertEquals(1, fixToFixpoint(root));

        String after = Files.readString(root.resolve("rs/Res.java"));
        assertTrue(UnreachableCatchFixer.hasMarker(after, "java.io.FileNotFoundException"), after);
        assertEquals(1, after.split("if \\(false\\)", -1).length - 1, "only the dead catch gets a marker:\n" + after);
    }
}
