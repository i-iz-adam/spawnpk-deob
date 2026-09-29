package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cleandecompile.PipelineConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end through {@link CompileFixLoop#run}: one tree carrying the error
 * shapes that survived the previous Stage 4 run (see the manifest), all of
 * which must now converge to a clean compile with the fixers wired in their
 * real order. Guards the wiring (ordering, "handled" withholding, the
 * transactional revert) that the per-fixer tests cannot see.
 */
class RemainderShapesLoopTest {

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void manifestRemainderShapesConvergeTogether(@TempDir Path temp) throws IOException {
        Path out = temp.resolve("out");
        Path src = out.resolve("src-generated/src/main/java");
        write(src, "rs/Client.java", """
                package rs;

                public class Client {
                    int field1;
                    boolean flag;
                    int scratch;

                    int a() { return this.flag; }
                    int b() { return this.scratch & this.flag; }
                    boolean c() { return this.field1; }
                    void d(int v) { if (v) { this.scratch = 1; } }
                }
                """);
        write(src, "rs/eventbus/EventBus.java", """
                package rs.eventbus;

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
        write(src, "rs/plugins/PluginManager.java", """
                package rs.plugins;

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
        write(src, "rs/ui/Class286.java", """
                package rs.ui;

                import java.awt.Component;
                import java.awt.Container;
                import java.awt.Dimension;
                import java.awt.LayoutManager2;

                public class Class286 implements LayoutManager2 {
                    public void addLayoutComponent(Component c, Object o) { }
                    public void addLayoutComponent(String s, Component c) { }
                    public void removeLayoutComponent(Component c) { }
                    public Dimension preferredLayoutSize(Container c) { return new Dimension(1, 1); }
                    public Dimension minimumLayoutSize(Container c) { return new Dimension(1, 1); }
                    public void layoutContainer(Container c) { }
                    public float getLayoutAlignmentX(Container c) { return 0; }
                    public float getLayoutAlignmentY(Container c) { return 0; }
                    public void invalidateLayout(Container c) { }
                }
                """);
        write(src, "rs/cache/Class65.java", """
                package rs.cache;

                import java.io.RandomAccessFile;

                public class Class65 {
                    private RandomAccessFile file;

                    synchronized int method100(int pos) {
                        this.file.seek((long) pos);
                        return this.file.read();
                    }
                }
                """);

        PipelineConfig config = new PipelineConfig(temp.resolve("in.jar"), out, List.of("rs"),
                PipelineConfig.DEFAULT_TIMEOUT_MS, 30, null, false, "17", null, null, null, null);
        CompileFixLoop.LoopReport report = new CompileFixLoop().run(config);

        assertTrue(report.converged(), "remaining: " + report.remainingErrorSummaries());
        assertEquals(List.of(), report.remainingErrorSummaries());
        String client = Files.readString(src.resolve("rs/Client.java"));
        assertTrue(client.contains("this.flag ? 1 : 0"), client);
        assertTrue(client.contains("this.field1 != 0"), client);
        assertTrue(Files.readString(src.resolve("rs/ui/Class286.java")).contains("TODO(stage4)"));
    }

    @Test
    void unfixableErrorsStillReportInsteadOfBeingHidden(@TempDir Path temp) throws IOException {
        Path out = temp.resolve("out");
        Path src = out.resolve("src-generated/src/main/java");
        // Wrong-arity call: no evidence of the intended method exists, so nothing may "fix" it.
        write(src, "rs/Client.java", """
                package rs;

                public class Client {
                    void b(int x) { }
                    void m() { this.b(1, 2, 3, 4, 5); }
                }
                """);
        PipelineConfig config = new PipelineConfig(temp.resolve("in.jar"), out, List.of("rs"),
                PipelineConfig.DEFAULT_TIMEOUT_MS, 8, null, false, "17", null, null, null, null);
        CompileFixLoop.LoopReport report = new CompileFixLoop().run(config);
        assertTrue(!report.converged());
        assertEquals(1, report.remainingErrorSummaries().size());
        assertTrue(Files.readString(src.resolve("rs/Client.java")).contains("this.b(1, 2, 3, 4, 5)"));
    }
}
