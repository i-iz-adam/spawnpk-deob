package com.cleandecompile.stage1;

import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves platform classes ({@code java.*}, {@code javax.*}, {@code jdk.*})
 * from the running JDK's {@code jrt:/} image for backends whose input is
 * otherwise limited to the jar under analysis. Without this, decompilers
 * treat every JDK type as unknown: resolvable output still emerges (both
 * backends tolerate it), but with degraded generics and spurious
 * {@code Object}s. Results are cached; non-platform names never touch the
 * filesystem.
 */
final class JdkClasses {

    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
    private static volatile List<String> modules;

    private JdkClasses() {
    }

    /** @param internalName slash-form, e.g. {@code java/lang/Object}. */
    static byte[] get(String internalName) {
        if (!internalName.startsWith("java/")
                && !internalName.startsWith("javax/")
                && !internalName.startsWith("jdk/")) {
            return null;
        }
        return CACHE.computeIfAbsent(internalName, JdkClasses::load);
    }

    private static byte[] load(String internalName) {
        try {
            FileSystem jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
            int slash = internalName.lastIndexOf('/');
            if (slash > 0) {
                // Fast path: /packages/<pkg> lists the modules providing it.
                Path pkgLink = jrt.getPath("/packages", internalName.substring(0, slash));
                if (Files.isDirectory(pkgLink)) {
                    try (var stream = Files.list(pkgLink)) {
                        for (Path mod : (Iterable<Path>) stream::iterator) {
                            byte[] hit = readModule(jrt, mod.getFileName().toString(), internalName);
                            if (hit != null) return hit;
                        }
                    }
                    return null;
                }
            }
            for (String module : moduleList(jrt)) {
                byte[] hit = readModule(jrt, module, internalName);
                if (hit != null) return hit;
            }
        } catch (Exception ignored) {
            // No jrt image (JRE layout without modules?) -- callers treat
            // these types as unknown, exactly as before.
        }
        return null;
    }

    private static List<String> moduleList(FileSystem jrt) throws Exception {
        List<String> seen = modules;
        if (seen != null) return seen;
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(jrt.getPath("/modules"))) {
            for (Path p : (Iterable<Path>) stream::iterator) names.add(p.getFileName().toString());
        }
        modules = names;
        return names;
    }

    private static byte[] readModule(FileSystem jrt, String module, String internalName) {
        try {
            Path p = jrt.getPath("/modules", module, internalName + ".class");
            if (Files.exists(p)) return Files.readAllBytes(p);
        } catch (Exception ignored) {
        }
        return null;
    }
}
