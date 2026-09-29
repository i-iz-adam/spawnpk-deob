package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Type-argument mismatches between the same raw type: the EventBus
 * ({@code Consumer<T>} to {@code Consumer<Object>}) and PluginManager
 * ({@code bind(Class<capture of ? extends Plugin>)}) remainder shapes.
 */
class GenericCastFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private SpanFixers.Result fix(Path root) throws IOException {
        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().size() > 0, "fixture must fail to compile");
        return SpanFixers.run(root, bucketer.categorize(outcome.diagnostics()), List.of(new GenericCastFixer()));
    }

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void relabelsTypeArgumentsThroughTheRawType(@TempDir Path root) throws IOException {
        // EventBus:93 shape -- a generic method's Consumer<T> flowing into Consumer<Object>.
        write(root, "rs/EventBus.java", """
                package rs;

                import java.util.ArrayList;
                import java.util.List;
                import java.util.function.Consumer;

                public class EventBus {
                    private final List<Consumer<Object>> handlers = new ArrayList<>();

                    public <T> void register(Consumer<T> handler) {
                        Consumer<Object> stored = handler;
                        this.handlers.add(stored);
                    }
                }
                """);
        var result = fix(root);
        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/EventBus.java"));
        assertTrue(updated.contains(
                "Consumer<Object> stored = (java.util.function.Consumer<java.lang.Object>) (java.util.function.Consumer) handler;"),
                updated);
        assertTrue(javac.compile(root, root.resolveSibling("c2"), List.of(), "17").success(), updated);
    }

    @Test
    void castsTheArgumentJavacBlamesInNoSuitableMethod(@TempDir Path root) throws IOException {
        // PluginManager:320 shape -- explicit type witness plus a wildcard-capture argument.
        write(root, "rs/PluginManager.java", """
                package rs;

                public class PluginManager {
                    interface Key<T> { }
                    interface Binder {
                        <T> Object bind(Key<T> key);
                        <T> Object bind(Class<T> type);
                    }
                    interface Plugin { }

                    void install(Binder binder, Class<? extends Plugin> type) {
                        binder.<Plugin>bind(type);
                    }
                }
                """);
        var result = fix(root);
        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/PluginManager.java"));
        assertTrue(updated.contains(
                "binder.<Plugin>bind((java.lang.Class<rs.PluginManager.Plugin>) (java.lang.Class) type);"),
                updated);
        assertTrue(javac.compile(root, root.resolveSibling("c2"), List.of(), "17").success(), updated);
    }

    @Test
    void refusesTargetsThatMentionTypeVariables(@TempDir Path root) throws IOException {
        // javac prints an in-scope type variable bare; emitting it into a cast could name something
        // that is not visible at the flagged expression.
        write(root, "rs/Box.java", """
                package rs;

                import java.util.List;

                public class Box {
                    <T> List<T> make(List<String> strings) {
                        return strings;
                    }
                }
                """);
        assertEquals(0, fix(root).fixes());
    }

    @Test
    void refusesDifferentRawTypes(@TempDir Path root) throws IOException {
        write(root, "rs/Raw.java", """
                package rs;

                import java.util.List;
                import java.util.Set;

                public class Raw {
                    Set<Object> m(List<String> strings) {
                        return strings;
                    }
                }
                """);
        assertEquals(0, fix(root).fixes(), "List -> Set is a real type error, not an argument mismatch");
    }

    @Test
    void refusesAmbiguousArguments(@TempDir Path root) throws IOException {
        write(root, "rs/Amb.java", """
                package rs;

                public class Amb {
                    interface Plugin { }
                    interface Binder { <T> Object bind(Class<T> a, Class<T> b); }

                    void install(Binder binder, Class<? extends Plugin> x, Class<? extends Plugin> y) {
                        binder.<Plugin>bind(x, y);
                    }
                }
                """);
        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var result = SpanFixers.run(root, bucketer.categorize(outcome.diagnostics()),
                List.of(new GenericCastFixer()));
        assertFalse(result.fixes() > 1, "two identically typed arguments: which to cast would be a guess");
    }

    @Test
    void idempotentOnceCastIsPresent(@TempDir Path root) throws IOException {
        write(root, "rs/Once.java", """
                package rs;

                import java.util.function.Consumer;

                public class Once {
                    <T> Consumer<Object> m(Consumer<T> handler) {
                        return handler;
                    }
                }
                """);
        assertEquals(1, fix(root).fixes());
        assertTrue(javac.compile(root, root.resolveSibling("c2"), List.of(), "17").success());
    }
}
