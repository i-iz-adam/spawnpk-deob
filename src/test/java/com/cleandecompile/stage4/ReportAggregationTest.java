package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cleandecompile.PipelineConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each loop run (initial + every swap-round re-run) overwrites the report,
 * so the final file shows only the last run's iterations. Runs must append
 * instead: a "1 error" tail otherwise hides the hundreds of errors fixed
 * (or churned) before it.
 */
class ReportAggregationTest {

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private PipelineConfig config(Path out) {
        return new PipelineConfig(
                Path.of("dummy.jar"), out, List.of(), 15_000L, 8, null,
                false, "17", "", "", null, null);
    }

    @Test
    void sequentialRunsAppendIterationsInsteadOfOverwriting(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("out");
        Path src = out.resolve("src-generated/src/main/java");
        // CFR's leaked void temporary: fails the first compile, the artifact
        // fixer removes it, the recompile is clean.
        write(src, "rs/A.java", """
                package rs;

                public class A {
                    void m() {
                        // CFR uninferred temporary
                        void tmp;
                        System.out.println("hi");
                    }
                }
                """);

        var loop = new CompileFixLoop();
        var first = loop.run(config(out));
        int firstIters = first.iterations().size();
        assertTrue(firstIters >= 2, "expected fix + converge iterations, got " + firstIters);

        var second = loop.run(config(out));
        Path report = out.resolve("manifests/stage4-fix-loop-report.json");
        JsonNode json = new ObjectMapper().readTree(report.toFile());

        assertEquals(firstIters + second.iterations().size(),
                json.get("iterations").size(),
                "second run must append to, not replace, prior iterations");
        assertEquals(2, json.get("stage4Runs").asInt(),
                "report must count how many runs produced it");
    }
}
