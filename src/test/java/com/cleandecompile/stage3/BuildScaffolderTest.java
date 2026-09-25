package com.cleandecompile.stage3;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cleandecompile.PipelineConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generated Gradle build must reproduce Stage 4's compile settings,
 * otherwise {@code compile.bat} and the fix-loop report disagree about the
 * error set (uncapped vs 100-cap, platform classes vs --release).
 */
class BuildScaffolderTest {

    @Test
    void buildGradleMirrorsStage4CompilerFlags(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("out");
        var config = new PipelineConfig(
                Path.of("dummy.jar"), out, List.of(), 15_000L, 8, null,
                false, "11", "", "", null, null);

        new BuildScaffolder().scaffold(config, Map.of(), List.of(),
                new DependencyFingerprinter.FingerprintResult(Map.of(), List.of()));

        String gradle = Files.readString(out.resolve("src-generated/build.gradle"));
        assertTrue(gradle.contains("--release"), "missing --release flag:\n" + gradle);
        assertTrue(gradle.contains("11"), "missing release level:\n" + gradle);
        assertTrue(gradle.contains("Xmaxerrs"), "missing -Xmaxerrs flag:\n" + gradle);
        assertTrue(gradle.contains("5000"), "missing 5000 error cap:\n" + gradle);
    }
}
