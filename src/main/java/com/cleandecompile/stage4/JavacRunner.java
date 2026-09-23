package com.cleandecompile.stage4;

import javax.tools.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs {@code javac} in-process (via {@link JavaCompiler}, not a
 * subprocess) against the generated source tree and returns structured
 * diagnostics for {@link DiagnosticBucketer} to categorize.
 */
public final class JavacRunner {

    public record CompileOutcome(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {}

    public CompileOutcome compile(Path sourceRoot, Path classOutputDir) throws IOException {
        return compile(sourceRoot, classOutputDir, List.of(), "17");
    }

    /** @param classpath extra jars for symbol resolution -- Stage 3's
     *  vendored-library jar, so references to out-of-scope (bundled)
     *  classes resolve instead of erroring as missing packages. */
    public CompileOutcome compile(Path sourceRoot, Path classOutputDir, List<Path> classpath) throws IOException {
        return compile(sourceRoot, classOutputDir, classpath, "17");
    }

    /**
     * @param classpath   extra jars for symbol resolution (see above).
     * @param releaseLevel {@code javac --release} level, e.g. "11" so an
     *                     applet-era client compiles against a platform that
     *                     still has {@code java.applet}. Must be supported by
     *                     the running JDK.
     */
    public CompileOutcome compile(Path sourceRoot, Path classOutputDir, List<Path> classpath,
                                  String releaseLevel) throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException(
                    "No system Java compiler available -- run this on a JDK (not a JRE), Java 17+.");
        }

        Files.createDirectories(classOutputDir);
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();

        try (StandardJavaFileManager fm = compiler.getStandardFileManager(collector, null, null)) {
            fm.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classOutputDir));
            if (!classpath.isEmpty()) {
                fm.setLocationFromPaths(StandardLocation.CLASS_PATH,
                        classpath.stream().filter(Files::exists).toList());
            }

            List<Path> sourceFiles;
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                sourceFiles = walk.filter(p -> p.toString().endsWith(".java")).toList();
            }
            if (sourceFiles.isEmpty()) {
                return new CompileOutcome(false, List.of());
            }

            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(sourceFiles);

            List<String> options = List.of(
                    "-nowarn",
                    "-proc:none",
                    "--release", releaseLevel
            );

            JavaCompiler.CompilationTask task =
                    compiler.getTask(null, fm, collector, options, null, units);
            boolean success = task.call();

            List<Diagnostic<? extends JavaFileObject>> errors = new ArrayList<>();
            for (var d : collector.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR) errors.add(d);
            }
            return new CompileOutcome(success, errors);
        }
    }
}
