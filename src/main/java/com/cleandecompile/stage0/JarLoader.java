package com.cleandecompile.stage0;

import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.scope.ScopeClassifier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Reads every entry out of the input jar once, splitting into classes
 * (scope-tagged) and everything else (resources, copied through verbatim
 * in Stage 3).
 */
public final class JarLoader {

    public record LoadResult(List<ClassInfo> classes, Map<String, byte[]> resources) {}

    public LoadResult load(Path jarPath, ScopeClassifier scopeClassifier) throws IOException {
        List<ClassInfo> classes = new ArrayList<>();
        Map<String, byte[]> resources = new LinkedHashMap<>();

        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            var entries = jarFile.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                count++;
                if (count % 5000 == 0) {
                    System.out.println("    ...still reading entries (" + count + " so far)");
                }
                if (entry.isDirectory()) continue;

                try (InputStream in = jarFile.getInputStream(entry)) {
                    byte[] bytes = readAll(in);
                    if (entry.getName().endsWith(".class")) {
                        String internalName = entry.getName().substring(0, entry.getName().length() - 6);
                        classes.add(new ClassInfo(internalName, bytes, scopeClassifier.isInScope(internalName)));
                    } else {
                        resources.put(entry.getName(), bytes);
                    }
                }
            }
        }
        return new LoadResult(classes, resources);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, in.available()));
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
