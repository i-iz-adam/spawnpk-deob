package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the fixer against REAL javac diagnostics (not hand-built ones): the
 * message shape, the file it is reported in and the container it names are
 * exactly what Stage 4 sees on a decompiled tree.
 */
class RepeatableContainerFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    // ---- helpers ----

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static String read(Path root, String rel) throws IOException {
        return Files.readString(root.resolve(rel));
    }

    private JavacRunner.CompileOutcome compile(Path root) throws IOException {
        return javac.compile(root, root.resolveSibling("classes-" + root.getFileName()), List.of(), "17");
    }

    private CompileFixLoop.RepeatableContainerFixer.Result fix(Path root) throws IOException {
        var outcome = compile(root);
        return CompileFixLoop.RepeatableContainerFixer.tryFixAll(root, bucketer.categorize(outcome.diagnostics()));
    }

    private static final String PLUGIN = """
            package rs.plugins;

            public interface Plugin {
            }
            """;

    private static final String REPEATABLE = """
            package rs.plugins;

            import java.lang.annotation.Documented;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Repeatable;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import rs.plugins.Plugin;
            import rs.plugins.PluginDependencies;

            @Retention(value=RetentionPolicy.RUNTIME)
            @Target(value={ElementType.TYPE})
            @Documented
            @Repeatable(value=PluginDependencies.class)
            public @interface PluginDependency {
                public Class<? extends Plugin> a();
            }
            """;

    private static final String CONTAINER = """
            package rs.plugins;

            import java.lang.annotation.Documented;
            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE})
            @Documented
            public @interface PluginDependencies {
               PluginDependency[] a();
            }
            """;

    // ---- the JLS-mandated element name ----

    @Test
    void renamesTheContainerElementJavacNamesAsTheOnlyValidName(@TempDir Path root) throws IOException {
        write(root, "rs/plugins/Plugin.java", PLUGIN);
        write(root, "rs/plugins/PluginDependency.java", REPEATABLE);
        write(root, "rs/plugins/PluginDependencies.java", CONTAINER);

        var before = compile(root).diagnostics();
        assertTrue(before.stream().anyMatch(d -> d.getMessage(Locale.ENGLISH)
                .contains("rs.plugins.PluginDependencies is not a valid @Repeatable")), () -> "fixture should "
                + "reproduce the diagnostic: " + before);

        var result = fix(root);
        String out = read(root, "rs/plugins/PluginDependencies.java");
        assertTrue(out.contains("PluginDependency[] value();"), out);
        assertEquals(1, result.fixes());
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    @Test
    void leavesTheRepeatableItselfAlone(@TempDir Path root) throws IOException {
        write(root, "rs/plugins/Plugin.java", PLUGIN);
        write(root, "rs/plugins/PluginDependency.java", REPEATABLE);
        write(root, "rs/plugins/PluginDependencies.java", CONTAINER);
        String before = read(root, "rs/plugins/PluginDependency.java");

        fix(root);

        assertEquals(before, read(root, "rs/plugins/PluginDependency.java"),
                "only the container is a valid @Repeatable target; a() there is a real element");
    }

    @Test
    void refusesWhenTheContainerHasNoSingleArrayElement(@TempDir Path root) throws IOException {
        write(root, "rs/plugins/Plugin.java", PLUGIN);
        write(root, "rs/plugins/PluginDependency.java", REPEATABLE);
        write(root, "rs/plugins/PluginDependencies.java", """
                package rs.plugins;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE})
                @Documented
                public @interface PluginDependencies {
                   PluginDependency[] a();
                   Class<?>[] b();
                }
                """);
        String before = read(root, "rs/plugins/PluginDependencies.java");

        assertEquals(0, fix(root).fixes(), "two array elements: renaming either could be wrong");
        assertEquals(before, read(root, "rs/plugins/PluginDependencies.java"));
    }

    @Test
    void refusesWhenTheArrayElementIsNotTheRepeatableType(@TempDir Path root) throws IOException {
        write(root, "rs/plugins/Plugin.java", PLUGIN);
        write(root, "rs/plugins/PluginDependency.java", REPEATABLE);
        write(root, "rs/plugins/PluginDependencies.java", """
                package rs.plugins;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE})
                @Documented
                public @interface PluginDependencies {
                   String[] a();
                }
                """);
        String before = read(root, "rs/plugins/PluginDependencies.java");

        assertEquals(0, fix(root).fixes(), "value() must return PluginDependency[], not String[]");
        assertEquals(before, read(root, "rs/plugins/PluginDependencies.java"));
    }

    @Test
    void refusesWhenAnotherClassInTheFileHoldsTheOnlyArrayElement(@TempDir Path root) throws IOException {
        write(root, "rs/plugins/Plugin.java", PLUGIN);
        write(root, "rs/plugins/PluginDependency.java", REPEATABLE);
        write(root, "rs/plugins/PluginDependencies.java", """
                package rs.plugins;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE})
                @Documented
                public @interface PluginDependencies {
                   PluginDependency[]
                   a();
                }

                class Helper {
                   PluginDependency[] a();
                }
                """);
        String before = read(root, "rs/plugins/PluginDependencies.java");

        assertEquals(0, fix(root).fixes(),
                "Helper's element is not the container's; renaming it would keep the error and add a bogus value()");
        assertEquals(before, read(root, "rs/plugins/PluginDependencies.java"),
                "the container's own element and the sibling must both be left alone");
    }

    @Test
    void refusesWhenTheElementNameIsReferencedElsewhereInTheFile(@TempDir Path root) throws IOException {
        write(root, "rs/plugins/Plugin.java", PLUGIN);
        write(root, "rs/plugins/PluginDependency.java", REPEATABLE);
        write(root, "rs/plugins/PluginDependencies.java", """
                package rs.plugins;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE})
                @Documented
                public @interface PluginDependencies {
                   PluginDependency[] a();
                }

                class Helper {
                   static PluginDependency[] read(PluginDependencies d) {
                      return d.a();
                   }
                }
                """);
        String before = read(root, "rs/plugins/PluginDependencies.java");

        assertEquals(0, fix(root).fixes(), "renaming would break the caller's d.a()");
        assertEquals(before, read(root, "rs/plugins/PluginDependencies.java"));
    }

    @Test
    void refusesWhenTheContainerAlreadyDeclaresAnElementNamedValue(@TempDir Path root) throws IOException {
        write(root, "rs/plugins/Plugin.java", PLUGIN);
        write(root, "rs/plugins/PluginDependency.java", REPEATABLE);
        write(root, "rs/plugins/PluginDependencies.java", """
                package rs.plugins;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE})
                @Documented
                public @interface PluginDependencies {
                   PluginDependency[] value();
                   Class<?> a();
                }
                """);
        String before = read(root, "rs/plugins/PluginDependencies.java");

        assertEquals(0, repeatableFor(root, "rs/plugins/PluginDependency.java",
                "rs.plugins.PluginDependencies is not a valid @Repeatable, no value element method declared").fixes(),
                "the container already has a value element");
        assertEquals(before, read(root, "rs/plugins/PluginDependencies.java"));
    }

    @Test
    void refusesWhenTheElementNameIsUsedOnItsOwnDeclarationLine(@TempDir Path root) throws IOException {
        write(root, "rs/plugins/Plugin.java", PLUGIN);
        write(root, "rs/plugins/Marker.java", """
                package rs.plugins;

                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                @Retention(RetentionPolicy.RUNTIME)
                public @interface Marker {
                    int a() default 0;
                }
                """);
        write(root, "rs/plugins/PluginDependency.java", REPEATABLE);
        write(root, "rs/plugins/PluginDependencies.java", """
                package rs.plugins;

                import java.lang.annotation.Documented;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE})
                @Documented
                public @interface PluginDependencies {
                   @Marker(a = 1) PluginDependency[] a();
                }
                """);
        String before = read(root, "rs/plugins/PluginDependencies.java");

        assertEquals(0, fix(root).fixes(),
                "@Marker(a = 1) names the element: renaming the declaration would break the annotation");
        assertEquals(before, read(root, "rs/plugins/PluginDependencies.java"));
        var remaining = compile(root).diagnostics();
        assertEquals(1, remaining.size(), "only the @Repeatable error should remain: " + remaining);
    }

    // ---- loop-level behaviour ----

    @Test
    void isIdempotentAndWithholdsHandledDiagnosticsFromLaterFixers(@TempDir Path root) throws IOException {
        write(root, "rs/plugins/Plugin.java", PLUGIN);
        write(root, "rs/plugins/PluginDependency.java", REPEATABLE);
        write(root, "rs/plugins/PluginDependencies.java", CONTAINER);
        var bucketed = bucketer.categorize(compile(root).diagnostics());

        var first = CompileFixLoop.RepeatableContainerFixer.tryFixAll(root, bucketed);
        assertTrue(first.fixes() > 0);
        assertTrue(first.unhandled(bucketed).isEmpty(), "every diagnostic here was addressed");

        assertTrue(compile(root).success());
        assertEquals(0, fix(root).fixes(), "second pass over a fixed tree changes nothing");
    }

    // ---- helpers for the one case javac cannot produce ----

    private static CompileFixLoop.RepeatableContainerFixer.Result repeatableFor(Path root, String rel,
                                                                              String message) throws IOException {
        return CompileFixLoop.RepeatableContainerFixer.tryFixAll(root, List.of(
                new DiagnosticBucketer.Bucketed(DiagnosticBucketer.Category.OTHER, diagnosticAt(root, rel, message))));
    }

    private static JavaFileObject sourceAt(Path path) {
        return new SimpleJavaFileObject(path.toUri(), JavaFileObject.Kind.SOURCE) {
        };
    }

    private static Diagnostic<? extends JavaFileObject> diagnosticAt(Path root, String rel, String message) {
        JavaFileObject source = sourceAt(root.resolve(rel));
        return new Diagnostic<>() {
            @Override public Kind getKind() { return Kind.ERROR; }
            @Override public JavaFileObject getSource() { return source; }
            @Override public long getPosition() { return -1; }
            @Override public long getStartPosition() { return -1; }
            @Override public long getEndPosition() { return -1; }
            @Override public long getLineNumber() { return 1; }
            @Override public long getColumnNumber() { return -1; }
            @Override public String getCode() { return "test.code"; }
            @Override public String getMessage(Locale locale) { return message; }
        };
    }
}
