package com.cleandecompile;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "clean-decompile", mixinStandardHelpOptions = true, version = "0.1.0",
        description = "Turns an obfuscated jar into the highest-percentage-compilable, "
                + "least-manually-patched Java source tree possible.")
public final class Main implements Callable<Integer> {

    @Option(names = {"-j", "--jar"}, required = true, description = "Input obfuscated jar.")
    Path inputJar;

    @Option(names = {"-o", "--output"}, required = true, description = "Output directory for everything the pipeline produces.")
    Path outputDir;

    @Option(names = {"-p", "--own-package"},
            description = "Dotted package prefix that is the target application's own code, e.g. com.naxos. "
                    + "Repeatable. Anything NOT under one of these is treated as a bundled library and left "
                    + "un-renamed/un-decompiled (see Stage 3). Omit entirely to treat the whole jar as owned code.")
    List<String> ownPackages = List.of();

    @Option(names = {"--decompile-timeout-ms"}, description = "Per-class decompiler timeout before falling through "
            + "to the next backend (scales up automatically for larger classes). Default: ${DEFAULT-VALUE}.")
    long decompileTimeoutMs = PipelineConfig.DEFAULT_TIMEOUT_MS;

    @Option(names = {"--max-fix-iterations"}, description = "Hard cap on Stage 4's compile-fix loop iterations. "
            + "Default: ${DEFAULT-VALUE}.")
    int maxFixIterations = PipelineConfig.DEFAULT_MAX_FIX_ITERATIONS;

    @Option(names = {"--custom-names"}, description = "Path to a JSON file of known names -- package prefixes, "
            + "full class names, fields, and methods -- to use instead of Stage 0's auto-generated ones (e.g. "
            + "\"this obfuscated package is actually the plugins package\"). See stage0/CustomNameOverrides.java "
            + "for the file format. Omit to auto-name everything.")
    Path customNamesFile;

    @Option(names = {"--decompile-libraries"}, description = "Also decompile out-of-scope (bundled library) "
            + "classes to source, kept at their original names. Default: libraries are only vendored as a "
            + "bytecode jar under src-generated/libs/.")
    boolean decompileLibraries = false;

    @Option(names = {"--release-level"}, description = "javac --release level for Stage 4 and the generated "
            + "Gradle build, e.g. 11 for applet-era clients. Default: ${DEFAULT-VALUE}.")
    String releaseLevel = "17";

    @Option(names = {"--main-class"}, description = "Dotted main-class name for the generated run script, "
            + "e.g. rs.Client. Omit to skip writing a run script.")
    String mainClass = "";

    @Option(names = {"--jre-home"}, description = "JDK/JRE home whose bin/java the generated run script "
            + "launches with. Omit to use plain java from PATH.")
    String jreHome = "";

    @Option(names = {"--lombok-jar"}, description = "Path to a Lombok jar, added to Stage 4's javac classpath "
            + "so decompiled sources using lombok annotations (e.g. @NonNull) resolve. The generated Gradle "
            + "build declares Lombok from Maven Central when any source imports it. Omit if unused.")
    Path lombokJar;

    @Option(names = {"--extra-sources"}, description = "Directory of hand-written .java shims copied into the "
            + "generated source tree (relative paths preserved), e.g. stubs for platform APIs absent from "
            + "both the jar and the JDK. Omit if unused.")
    Path extraSources;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws Exception {
        PipelineConfig config = new PipelineConfig(
                inputJar, outputDir, ownPackages, decompileTimeoutMs, maxFixIterations, customNamesFile,
                decompileLibraries, releaseLevel, mainClass, jreHome, lombokJar, extraSources);
        new PipelineOrchestrator().runFull(config);
        return 0;
    }
}
