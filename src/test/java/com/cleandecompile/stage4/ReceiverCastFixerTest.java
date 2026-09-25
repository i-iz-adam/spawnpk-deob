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
 * Runs the fixer against REAL javac diagnostics: {@code contains} on an
 * Object-typed local is the dominant unhandled receiver shape in
 * {@code Client} (the demand-guesser refuses single-method Collection/String
 * ambiguities, so the curated receiver map must cover it).
 */
class ReceiverCastFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    @Test
    void castsObjectReceiverForContainsAndSplit(@TempDir Path root) throws IOException {
        Path file = root.resolve("rs/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                package rs;

                public class A {
                    String field806 = "";
                    void m(Object object) {
                        if (object.contains(">")) {
                            object = object.split("> ")[1];
                        }
                        this.field806 = object;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var bucketed = bucketer.categorize(outcome.diagnostics());

        int fixed = CompileFixLoop.ReceiverCastFixer.tryFixAll(root, bucketed);

        assertEquals(2, fixed, "expected contains + split receiver casts");
        String updated = Files.readString(file);
        assertTrue(updated.contains("((java.lang.String) object).contains("),
                "missing contains cast:\n" + updated);
        assertTrue(updated.contains("((java.lang.String) object).split("),
                "missing split cast:\n" + updated);
    }
}
