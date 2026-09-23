package com.cleandecompile.stage0;

import com.cleandecompile.PipelineConfig;
import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.model.MemberRenameEntry;
import com.cleandecompile.model.RenameEntry;
import com.cleandecompile.scope.ScopeClassifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * Stage 0 entry point: obfuscated jar in, behaviorally-identical jar with
 * source-legal, globally-unique in-scope names out, plus a manifest of
 * every rename decision and every normalization warning.
 */
public final class Stage0Runner {

    public record Stage0Output(
            List<ClassInfo> normalizedClasses,
            Map<String, byte[]> resources,
            List<RenameEntry> renames,
            List<MemberRenameEntry> memberRenames,
            List<BytecodeNormalizer.Warning> warnings
    ) {
        public long inScopeCount() {
            return normalizedClasses.stream().filter(ClassInfo::inScope).count();
        }
    }

    public Stage0Output run(PipelineConfig config) throws IOException {
        Files.createDirectories(config.outputDir());
        Files.createDirectories(config.stage0ManifestPath().getParent());

        if (config.ownedPackagePrefixes().isEmpty()) {
            System.out.println("  WARNING: no --own-package given -- treating the ENTIRE jar as owned code."
                    + " Bundled libraries will be renamed and later decompiled too. Pass --own-package"
                    + " to scope this down if that's not what you want.");
        }

        CustomNameOverrides overrides = (config.customNamesFile() == null
                ? CustomNameOverrides.none()
                : CustomNameOverrides.loadFromJson(config.customNamesFile()))
                .withImpliedPackagePins(config.ownedPackagePrefixes());

        long t0 = System.currentTimeMillis();
        System.out.println("  loading jar...");
        ScopeClassifier scopeClassifier = new ScopeClassifier(config.ownedPackagePrefixes());
        JarLoader.LoadResult loaded = new JarLoader().load(config.inputJar(), scopeClassifier);
        long inScope = loaded.classes().stream().filter(ClassInfo::inScope).count();
        System.out.printf("  loaded %d classes (%d in scope), %d resources in %.1fs%n",
                loaded.classes().size(), inScope, loaded.resources().size(), elapsedSec(t0));

        long t1 = System.currentTimeMillis();
        System.out.println("  building class/package rename map...");
        RenameMapBuilder.Result classRenameResult = new RenameMapBuilder().build(loaded.classes(), overrides);
        System.out.printf("  %d class/package renames computed in %.1fs%n",
                classRenameResult.manifestEntries().size(), elapsedSec(t1));

        long t2 = System.currentTimeMillis();
        System.out.println("  building field/method rename map...");
        MemberRenamePlanner.Result memberRenameResult = new MemberRenamePlanner().plan(loaded.classes(), overrides);
        long memberRenamedCount = memberRenameResult.manifestEntries().stream().filter(e -> !e.kept()).count();
        System.out.printf("  %d field/method renames computed (%d left unrenamed on purpose) in %.1fs%n",
                memberRenamedCount, memberRenameResult.manifestEntries().size() - memberRenamedCount, elapsedSec(t2));

        long t3 = System.currentTimeMillis();
        System.out.println("  normalizing bytecode (this is the slow step on large jars)...");
        BytecodeNormalizer.Result normResult = new BytecodeNormalizer().normalizeAll(
                loaded.classes(), classRenameResult.renameMap(),
                memberRenameResult.methodRenameMap(), memberRenameResult.fieldRenameMap());
        System.out.printf("  normalization done in %.1fs%n", elapsedSec(t3));

        writeJar(config, normResult.normalizedClasses(), loaded.resources());
        writeManifest(config, classRenameResult.manifestEntries(), memberRenameResult.manifestEntries(),
                normResult.warnings());

        return new Stage0Output(
                normResult.normalizedClasses(),
                loaded.resources(),
                classRenameResult.manifestEntries(),
                memberRenameResult.manifestEntries(),
                normResult.warnings());
    }

    private double elapsedSec(long startMillis) {
        return (System.currentTimeMillis() - startMillis) / 1000.0;
    }

    private void writeJar(PipelineConfig config, List<ClassInfo> classes, Map<String, byte[]> resources)
            throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(config.normalizedJarPath()))) {
            for (ClassInfo ci : classes) {
                jos.putNextEntry(new JarEntry(ci.internalName() + ".class"));
                jos.write(ci.bytes());
                jos.closeEntry();
            }
            for (var entry : resources.entrySet()) {
                jos.putNextEntry(new JarEntry(entry.getKey()));
                jos.write(entry.getValue());
                jos.closeEntry();
            }
        }
    }

    private void writeManifest(PipelineConfig config, List<RenameEntry> classRenames,
                               List<MemberRenameEntry> memberRenames,
                               List<BytecodeNormalizer.Warning> warnings) throws IOException {
        var mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        var doc = Map.of(
                "classRenameCount", classRenames.size(),
                "memberRenameCount", memberRenames.stream().filter(e -> !e.kept()).count(),
                "memberKeptCount", memberRenames.stream().filter(MemberRenameEntry::kept).count(),
                "warningCount", warnings.size(),
                "classRenames", classRenames,
                "memberRenames", memberRenames,
                "warnings", warnings
        );
        mapper.writeValue(config.stage0ManifestPath().toFile(), doc);
    }
}