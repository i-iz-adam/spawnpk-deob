package com.cleandecompile.stage3;

import com.cleandecompile.PipelineConfig;
import com.cleandecompile.model.ClassInfo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Stage 3: turns Stage 1's loose {@code .java} tree into an actually
 * buildable Gradle project -- non-class resources copied into
 * {@code src/main/resources}, identified bundled libraries declared as
 * normal Maven dependencies, and everything else that's out of scope
 * vendored into a local jar so the build stays green even when a bundled
 * library couldn't be identified.
 */
public final class BuildScaffolder {

    public void scaffold(PipelineConfig config,
                          Map<String, byte[]> resources,
                          List<ClassInfo> outOfScopeClasses,
                          DependencyFingerprinter.FingerprintResult fingerprints) throws IOException {
        writeResources(config, resources);
        Path vendoredJar = writeVendoredLibrary(config, outOfScopeClasses, fingerprints.unidentifiedInternalNames());
        writeBuildGradle(config, fingerprints, vendoredJar);
        writeSettingsGradle(config);
        writeRunScript(config);
    }

    private void writeResources(PipelineConfig config, Map<String, byte[]> resources) throws IOException {
        Path resourceRoot = config.projectDir().resolve("src/main/resources");
        for (var entry : resources.entrySet()) {
            // META-INF/MANIFEST.jar signing entries are meaningless once the
            // jar is rebuilt from source and will just cause a mismatched-
            // signature failure at runtime -- drop them.
            if (entry.getKey().startsWith("META-INF/") &&
                    (entry.getKey().endsWith(".SF") || entry.getKey().endsWith(".RSA") || entry.getKey().endsWith(".DSA"))) {
                continue;
            }
            Path out = resourceRoot.resolve(entry.getKey());
            Files.createDirectories(out.getParent());
            Files.write(out, entry.getValue());
        }
    }

    /** Bundles every out-of-scope class we couldn't identify against a real
     *  artifact into one local jar, referenced directly from build.gradle. */
    private Path writeVendoredLibrary(PipelineConfig config, List<ClassInfo> outOfScopeClasses,
                                       List<String> unidentifiedNames) throws IOException {
        Files.createDirectories(config.vendoredLibsDir());
        Path jarPath = config.vendoredLibsDir().resolve("vendored-unidentified.jar");
        var unidentifiedSet = new TreeSet<>(unidentifiedNames);

        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (ClassInfo ci : outOfScopeClasses) {
                if (!unidentifiedSet.contains(ci.internalName())) continue; // identified ones become real deps instead
                jos.putNextEntry(new JarEntry(ci.internalName() + ".class"));
                jos.write(ci.bytes());
                jos.closeEntry();
            }
        }
        return jarPath;
    }

    private void writeBuildGradle(PipelineConfig config,
                                   DependencyFingerprinter.FingerprintResult fingerprints,
                                   Path vendoredJar) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("plugins {\n    id 'java'\n}\n\n");
        sb.append("repositories {\n    mavenCentral()\n}\n\n");
        sb.append("dependencies {\n");

        var seen = new TreeSet<String>();
        for (var artifact : fingerprints.identifiedByInternalName().values()) {
            String coordinate = artifact.groupId() + ":" + artifact.artifactId() + ":" + artifact.version();
            if (seen.add(coordinate)) {
                sb.append("    implementation '").append(coordinate).append("'\n");
            }
        }

        Path relativeVendored = config.projectDir().relativize(vendoredJar);
        sb.append("    implementation files('").append(relativeVendored.toString().replace('\\', '/')).append("')\n");
        sb.append("}\n\n");
        sb.append("java {\n    sourceCompatibility = JavaVersion.").append(javaVersionLiteral(config.releaseLevel()))
                .append("\n    targetCompatibility = JavaVersion.").append(javaVersionLiteral(config.releaseLevel()))
                .append("\n}\n");

        Files.createDirectories(config.projectDir());
        Files.writeString(config.projectDir().resolve("build.gradle"), sb.toString());
    }

    private static String javaVersionLiteral(String releaseLevel) {
        return releaseLevel.equals("8") ? "VERSION_1_8" : "VERSION_" + releaseLevel;
    }

    private void writeSettingsGradle(PipelineConfig config) throws IOException {
        Files.writeString(config.projectDir().resolve("settings.gradle"),
                "rootProject.name = 'decompiled-app'\n");
    }

    /** Writes {@code run.bat} next to the Gradle build when a main class was
     *  given: builds are reproducible via Gradle, launches go through the
     *  configured JRE. Nothing here compiles -- fix Stage 4's remaining
     *  reported errors first, then {@code gradle build} here, then this. */
    private void writeRunScript(PipelineConfig config) throws IOException {
        if (config.mainClass() == null || config.mainClass().isBlank()) return;
        String javaExe = (config.jreHome() == null || config.jreHome().isBlank())
                ? "java"
                : config.jreHome().replace('/', '\\') + "\\bin\\java.exe";
        String script = "@echo off\r\n"
                + "REM Generated by clean-decompile. Run from any folder; paths are relative to this script.\r\n"
                + "REM Build first: gradle build  (after clearing Stage 4's remaining errors)\r\n"
                + "\"" + javaExe + "\" -cp \"%~dp0build\\classes\\java\\main;%~dp0build\\resources\\main;%~dp0libs\\*\" "
                + config.mainClass() + " %*\r\n";
        Files.writeString(config.projectDir().resolve("run.bat"), script);
    }
}
