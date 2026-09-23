package com.cleandecompile.stage1;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler;
import org.jetbrains.java.decompiler.main.extern.IContextSource;
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger;
import org.jetbrains.java.decompiler.main.extern.IResultSaver;

/**
 * Primary decompiler backend — Vineflower (actively maintained Fernflower fork),
 * used in-process via its embedding API so {@link DecompileWorker} keeps
 * per-class isolation with a hard timeout and no process-spawn overhead.
 *
 * <p>Each call builds a fresh {@link BaseDecompiler} over two context
 * sources, both fully in memory except for the library jar on disk:
 * <ul>
 *   <li><b>source</b> — exactly the one requested class, served from the
 *       normalized jar's in-memory class table through {@code classBytesProvider}.</li>
 *   <li><b>library</b> — the whole normalized jar ({@code stage0-normalized.jar},
 *       registered once per run via {@link #setLibraryJar}), so type
 *       resolution sees in-scope renames and out-of-scope bundled classes
 *       alike. Absent (or unset) it degrades to single-class mode.</li>
 * </ul>
 *
 * <p>Decompiled text is captured through a custom {@link IContextSource.IOutputSink}
 * instead of touching disk; options are Vineflower's own defaults (an empty map
 * is sufficient — verified empirically against 1.10.1).
 */
public final class VineflowerDecompiler implements Decompiler {

    /** Normalized jar used as Vineflower's type-resolution library, set once
     *  per {@link Stage1Runner} run and cleared afterwards. Volatile because
     *  worker threads read it; each call otherwise shares no mutable state. */
    private static volatile Path libraryJar;

    public static void setLibraryJar(Path jar) {
        libraryJar = jar;
    }

    public static void clearLibraryJar() {
        libraryJar = null;
    }

    @Override
    public String name() {
        return "vineflower";
    }

    @Override
    public String decompile(String internalName, Function<String, byte[]> classBytesProvider) throws Exception {
        byte[] bytes = classBytesProvider.apply(internalName);
        if (bytes == null) {
            throw new IllegalArgumentException("no bytes available for " + internalName);
        }

        Map<String, String> captured = new HashMap<>();
        IResultSaver saver = new CaptureSaver(captured);
        BaseDecompiler decompiler = new BaseDecompiler(saver, new HashMap<>(), IFernflowerLogger.NO_OP);

        Path lib = libraryJar;
        if (lib != null && Files.exists(lib)) {
            decompiler.addLibrary(lib.toFile());
        }
        decompiler.addSource(new SingleClassSource(internalName, bytes));
        decompiler.decompileContext();

        String direct = captured.get(internalName);
        if (direct != null) {
            return direct;
        }
        if (captured.size() == 1) {
            return captured.values().iterator().next();
        }
        throw new IllegalStateException(
                "vineflower produced no output for " + internalName + " (captured: " + captured.keySet() + ")");
    }

    /** One class, served from memory. Entry naming follows Fernflower's
     *  directory convention ({@code rs/Client.class}). */
    private record SingleClassSource(String internalName, byte[] bytes) implements IContextSource {

        @Override
        public String getName() {
            return "stage1:" + internalName;
        }

        @Override
        public Entries getEntries() {
            return new Entries(List.of(Entry.parse(internalName + ".class")), List.of(), List.of());
        }

        @Override
        public InputStream getInputStream(String resourceName) {
            return new ByteArrayInputStream(bytes);
        }

        @Override
        public IOutputSink createOutputSink(IResultSaver saver) {
            return new IOutputSink() {
                @Override
                public void begin() {
                }

                @Override
                public void acceptClass(String entryPath, String filePath, String content, int[] mapping) {
                    if (saver instanceof CaptureSaver cap) {
                        cap.captured.put(entryPath, content);
                    }
                }

                @Override
                public void acceptDirectory(String dir) {
                }

                @Override
                public void acceptOther(String path) {
                }

                @Override
                public void close() throws IOException {
                }
            };
        }
    }

    /** Placeholder — output flows through the source's own sink; this only
     *  satisfies the {@link BaseDecompiler} constructor. */
    private record CaptureSaver(Map<String, String> captured) implements IResultSaver {

        @Override
        public void saveFolder(String path) {
        }

        @Override
        public void copyFile(String source, String path, String entryName) {
        }

        @Override
        public void saveClassFile(String path, String qualifiedName, String entryName, String content, int[] mapping) {
            captured.put(entryName, content);
        }

        @Override
        public void createArchive(String path, String archiveName, java.util.jar.Manifest manifest) {
        }

        @Override
        public void saveDirEntry(String path, String archiveName, String entryName) {
        }

        @Override
        public void copyEntry(String source, String path, String archiveName, String entryName) {
        }

        @Override
        public void saveClassEntry(String path, String archiveName, String qualifiedName, String entryName,
                                   String content) {
        }

        @Override
        public void closeArchive(String path, String archiveName) {
        }
    }
}
