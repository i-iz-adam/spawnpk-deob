package com.cleandecompile.stage1;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;

/**
 * Secondary decompiler backend — CFR, used in-process via its embedding API
 * ({@link CfrDriver.Builder}) so {@link DecompileWorker} keeps per-class
 * isolation with a hard timeout and no process-spawn overhead.
 *
 * <p>Each call builds a fresh driver over two in-memory adapters: a {@link
 * ClassFileSource} serving the requested class (plus every other class CFR
 * asks for, straight from the normalized jar's class table, so both
 * backends see an identical view) and an {@link OutputSinkFactory} capturing
 * the decompiled text instead of writing files. Options are CFR's own
 * defaults. Class names are tried dotted first, then slash-form.
 */
public final class CfrDecompiler implements Decompiler {

    @Override
    public String name() {
        return "cfr";
    }

    @Override
    public String decompile(String internalName, Function<String, byte[]> classBytesProvider) throws Exception {
        byte[] bytes = classBytesProvider.apply(internalName);
        if (bytes == null) {
            throw new IllegalArgumentException("no bytes available for " + internalName);
        }

        StringBuilder captured = new StringBuilder();
        ClassFileSource source = new InMemorySource(classBytesProvider);
        OutputSinkFactory sinks = new CaptureSinks(captured);

        String dotted = internalName.replace('/', '.');
        decompileOne(source, sinks, dotted);
        if (captured.isEmpty() && !dotted.equals(internalName)) {
            decompileOne(new InMemorySource(classBytesProvider), new CaptureSinks(captured), internalName);
        }
        if (captured.isEmpty()) {
            throw new IllegalStateException("cfr produced no output for " + internalName);
        }
        return captured.toString();
    }

    private void decompileOne(ClassFileSource source, OutputSinkFactory sinks, String className) {
        CfrDriver driver = new CfrDriver.Builder()
                .withClassFileSource(source)
                .withOutputSink(sinks)
                .withOptions(Map.of())
                .build();
        driver.analyse(List.of(className));
    }

    /** Serves every class CFR asks for from the in-memory table, accepting
     *  dotted, slash-form, and {@code .class}-suffixed spellings. */
    private record InMemorySource(Function<String, byte[]> provider) implements ClassFileSource {

        @Override
        public void informAnalysisRelativePathDetail(String useAsPath, String classFilePath) {
        }

        @Override
        public Collection<String> addJar(String jarPath) {
            return List.of();
        }

        @Override
        public String getPossiblyRenamedPath(String path) {
            return path;
        }

        @Override
        public Pair<byte[], String> getClassFileContent(String path) throws IOException {
            String key = path;
            if (key.endsWith(".class")) key = key.substring(0, key.length() - ".class".length());
            byte[] bytes = provider.apply(key);
            if (bytes == null && key.contains(".")) bytes = provider.apply(key.replace('.', '/'));
            if (bytes == null && key.contains("/")) bytes = provider.apply(key.replace('/', '.'));
            if (bytes == null) throw new IOException("unknown class: " + path);
            return Pair.make(bytes, path);
        }
    }

    /** Captures {@code DECOMPILED} output (CFR actually emits
     *  {@code DECOMPILED_MULTIVER}, a subinterface, even for
     *  single-version classes); every other sink is a no-op. */
    private record CaptureSinks(StringBuilder captured) implements OutputSinkFactory {

        @Override
        @SuppressWarnings("unchecked")
        public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
            if (sinkType == SinkType.JAVA && (sinkClass == SinkClass.DECOMPILED
                    || sinkClass == SinkClass.DECOMPILED_MULTIVER)) {
                return (Sink<T>) (Sink<SinkReturns.Decompiled>) d -> captured.append(d.getJava());
            }
            return t -> {
            };
        }

        @Override
        public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
            return List.copyOf(available);
        }
    }
}
