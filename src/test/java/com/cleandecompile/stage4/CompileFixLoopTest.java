package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

class CompileFixLoopTest {

    private static JavaFileObject sourceAt(Path path) {
        return new SimpleJavaFileObject(path.toUri(), JavaFileObject.Kind.SOURCE) {
        };
    }

    private static Diagnostic<? extends JavaFileObject> diagnosticAt(JavaFileObject source, long line) {
        return new Diagnostic<>() {
            @Override public Kind getKind() { return Kind.ERROR; }
            @Override public JavaFileObject getSource() { return source; }
            @Override public long getPosition() { return -1; }
            @Override public long getStartPosition() { return -1; }
            @Override public long getEndPosition() { return -1; }
            @Override public long getLineNumber() { return line; }
            @Override public long getColumnNumber() { return -1; }
            @Override public String getCode() { return "test.code"; }
            @Override public String getMessage(Locale locale) { return "test message"; }
        };
    }

    @Test
    void swapRounds(@TempDir Path temp) throws IOException {
        // Failing files resolve to slash-form internal names; duplicates
        // collapse and outside-tree sources are dropped. (The relative-root
        // production path additionally relies on CWD anchoring, which only
        // holds inside a real pipeline run.)
        Path root = temp.resolve("out/src-generated/src/main/java");
        Files.createDirectories(root.resolve("rs/pkg"));
        JavaFileObject a = sourceAt(root.resolve("rs/pkg/Client.java"));
        JavaFileObject b = sourceAt(root.resolve("rs/pkg/Client.java"));
        JavaFileObject outside = sourceAt(temp.resolve("elsewhere/Other.java"));
        var loop = new CompileFixLoop();
        var method = getFailingFilesMethod();
        @SuppressWarnings("unchecked")
        List<String> failing = (List<String>) invoke(method, loop, root,
                List.of(diagnosticAt(a, 10), diagnosticAt(b, 20), diagnosticAt(outside, 1)));
        assertEquals(List.of("rs/pkg/Client"), failing);
    }

    private static java.lang.reflect.Method getFailingFilesMethod() {
        try {
            var m = CompileFixLoop.class.getDeclaredMethod("failingFiles", Path.class, List.class);
            m.setAccessible(true);
            return m;
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Object invoke(java.lang.reflect.Method m, Object target, Object... args) {
        try {
            return m.invoke(target, args);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
