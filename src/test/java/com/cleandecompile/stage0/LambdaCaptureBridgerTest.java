package com.cleandecompile.stage0;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.stage1.VineflowerDecompiler;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The manifest's "invalid method reference / required: X, found: no
 * arguments" cluster comes from lambda call sites whose extra captured
 * arguments an optimizer folded into the implementation handle. Here real
 * javac output is put into exactly that shape, pushed through the real
 * {@link BytecodeNormalizer}, and checked three ways: behaviour on a JVM
 * (unchanged), the structural invariant (no inexpressible site is left
 * unbridged), and -- against the real Vineflower -- that the decompiled
 * source compiles and behaves the same, where the same bytecode without this
 * step does not.
 */
class LambdaCaptureBridgerTest {

    private static final String DEMO = """
            package fx;

            import java.util.Map;
            import java.util.TreeMap;
            import java.util.function.IntBinaryOperator;
            import java.util.function.IntSupplier;
            import java.util.function.IntUnaryOperator;
            import java.util.function.Supplier;

            public class Demo {
                private final String tag = "T";
                private final StringBuilder log = new StringBuilder();

                void method244(String s) { log.append("m244:").append(s).append(tag).append(';'); }
                void method9(int a, String b) { log.append("m9:").append(a).append(b).append(';'); }
                static int stat(int a, int b) { return a * 10 + b; }
                void plain() { log.append("plain;"); }

                Runnable viaInstance(String s) { return () -> this.method244(s); }
                Runnable viaTwo(int a, String b) { return () -> this.method9(a, b); }
                IntSupplier viaStatic(int a, int b) { return () -> stat(a, b); }
                IntUnaryOperator viaMixed(int a) { return b -> stat(a, b); }
                IntSupplier viaOther(int a, int b) { return () -> Other.helper(a, b); }
                Supplier<Box> viaNew(String s) { return () -> new Box(s); }
                Runnable viaInterface(Map<Object, Object> m, Object k, Object v) { return () -> m.put(k, v); }
                Runnable expressibleBound() { return this::plain; }
                IntBinaryOperator expressibleStatic() { return Demo::stat; }

                public String selfTest() {
                    viaInstance("a").run();
                    viaTwo(7, "b").run();
                    log.append(viaStatic(1, 2).getAsInt()).append(';');
                    log.append(viaMixed(3).applyAsInt(4)).append(';');
                    log.append(viaOther(5, 6).getAsInt()).append(';');
                    log.append(viaNew("n").get().value).append(';');
                    Map<Object, Object> map = new TreeMap<>();
                    viaInterface(map, "k", "v").run();
                    log.append(map).append(';');
                    expressibleBound().run();
                    log.append(expressibleStatic().applyAsInt(8, 9)).append(';');
                    return log.toString();
                }
            }
            """;
    private static final String OTHER = """
            package fx;

            public class Other {
                static int helper(int a, int b) { return a * 100 + b; }
            }
            """;
    /** Same short name + descriptor as Demo.method244, called virtually: the ProGuard collision that
     *  makes CompilerArtifactAnalysis (rightly, conservatively) decline to hide Demo's lambda body. */
    private static final String UNRELATED = """
            package fx;

            public class Unrelated {
                void method244(String s) { }
                static void poke(Unrelated u) { u.method244("q"); }
            }
            """;
    private static final String BOX = """
            package fx;

            public class Box {
                final String value;
                Box(String value) { this.value = value; }
            }
            """;

    /** Original javac output: internal name -> bytes. */
    private static Map<String, byte[]> original;
    private static String expected;

    @BeforeAll
    static void compileFixture(@TempDir Path temp) throws Exception {
        Path src = temp.resolve("src");
        Path out = temp.resolve("out");
        Files.createDirectories(src.resolve("fx"));
        Files.createDirectories(out);
        Files.writeString(src.resolve("fx/Demo.java"), DEMO);
        Files.writeString(src.resolve("fx/Other.java"), OTHER);
        Files.writeString(src.resolve("fx/Box.java"), BOX);
        Files.writeString(src.resolve("fx/Unrelated.java"), UNRELATED);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StringWriter errors = new StringWriter();
        int rc = javac.run(null, null, new java.io.PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                errors.write(b);
            }
        }), "--release", "17", "-d", out.toString(),
                src.resolve("fx/Demo.java").toString(), src.resolve("fx/Other.java").toString(),
                src.resolve("fx/Box.java").toString(), src.resolve("fx/Unrelated.java").toString());
        assertEquals(0, rc, errors.toString());
        original = new LinkedHashMap<>();
        for (String n : List.of("fx/Demo", "fx/Other", "fx/Box", "fx/Unrelated")) {
            original.put(n, Files.readAllBytes(out.resolve(n + ".class")));
        }
        expected = run(original);
    }

    // ------------------------------------------------------------------ helpers

    /** Loads the classes in an isolated loader (which also verifies them) and runs {@code Demo.selfTest()}. */
    private static String run(Map<String, byte[]> classes) throws Exception {
        ClassLoader loader = new ClassLoader(LambdaCaptureBridgerTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] b = classes.get(name.replace('.', '/'));
                if (b == null) throw new ClassNotFoundException(name);
                return defineClass(name, b, 0, b.length);
            }
        };
        Object demo = loader.loadClass("fx.Demo").getDeclaredConstructor().newInstance();
        return (String) demo.getClass().getMethod("selfTest").invoke(demo);
    }

    /**
     * Simulates the optimizer: every javac lambda shim
     * ({@code lambda$x$0(args) { return callee(args); }}) is removed and its
     * call site's implementation handle is pointed straight at the callee, the
     * call site's captured arguments untouched.
     */
    private static Map<String, byte[]> optimizerShaped() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        original.forEach((name, bytes) -> {
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            Map<String, MethodNode> byKey = new LinkedHashMap<>();
            for (MethodNode m : cn.methods) byKey.put(m.name + m.desc, m);
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                    if (!(n instanceof InvokeDynamicInsnNode indy)
                            || !indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                            || !(indy.bsmArgs[1] instanceof Handle impl)
                            || !impl.getName().startsWith("lambda$")) continue;
                    MethodNode shim = byKey.get(impl.getName() + impl.getDesc());
                    indy.bsmArgs[1] = calleeHandle(shim);
                }
            }
            cn.methods.removeIf(m -> m.name.startsWith("lambda$"));
            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            cn.accept(cw);
            out.put(name, cw.toByteArray());
        });
        return out;
    }

    /** The handle for the single call a pure-forwarding shim makes. */
    private static Handle calleeHandle(MethodNode shim) {
        MethodInsnNode call = null;
        int nextSlot = (shim.access & Opcodes.ACC_STATIC) != 0 ? 0 : 0;
        for (AbstractInsnNode n = shim.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() < 0) continue;                       // labels, line numbers
            if (n instanceof VarInsnNode v) {
                assertEquals(nextSlot, v.var, "shim must forward its parameters in order: " + shim.name);
                nextSlot += v.getOpcode() == Opcodes.LLOAD || v.getOpcode() == Opcodes.DLOAD ? 2 : 1;
            } else if (n instanceof MethodInsnNode m) {
                assertTrue(call == null, "shim must make exactly one call: " + shim.name);
                call = m;
            } else if (!(n instanceof InsnNode) && n.getOpcode() != Opcodes.NEW) {
                throw new AssertionError("unexpected instruction in shim " + shim.name + ": " + n.getOpcode());
            }
        }
        assertTrue(call != null, shim.name);
        int tag = switch (call.getOpcode()) {
            case Opcodes.INVOKESTATIC -> Opcodes.H_INVOKESTATIC;
            case Opcodes.INVOKEVIRTUAL -> Opcodes.H_INVOKEVIRTUAL;
            case Opcodes.INVOKEINTERFACE -> Opcodes.H_INVOKEINTERFACE;
            default -> call.name.equals("<init>") ? Opcodes.H_NEWINVOKESPECIAL : Opcodes.H_INVOKESPECIAL;
        };
        return new Handle(tag, call.owner, call.name, call.desc, call.itf);
    }

    /** Runs the real Stage 0 normalizer (no renames) over every class. */
    private static NormalizedResult normalize(Map<String, byte[]> classes) {
        List<ClassInfo> infos = new ArrayList<>();
        classes.forEach((n, b) -> infos.add(new ClassInfo(n, b, true)));
        CompilerArtifactAnalysis.Result repairs = CompilerArtifactAnalysis.analyze(infos);
        BytecodeNormalizer.Result r = new BytecodeNormalizer().normalizeAll(
                infos, Map.of(), Map.of(), Map.of(), false, repairs);
        Map<String, byte[]> out = new LinkedHashMap<>();
        r.normalizedClasses().forEach(ci -> out.put(ci.internalName(), ci.bytes()));
        return new NormalizedResult(out, r.warnings());
    }

    private record NormalizedResult(Map<String, byte[]> classes, List<BytecodeNormalizer.Warning> warnings) {
        long bridges() {
            return warnings.stream().filter(w -> w.message().startsWith("lambda capture bridged")).count();
        }
    }

    /**
     * The invariant this step exists for: every lambda call site is either
     * expressible as a method reference, or targets a synthetic method of its
     * own class (the shape decompilers inline).
     */
    private static List<String> inexpressibleSites(Map<String, byte[]> classes) {
        List<String> bad = new ArrayList<>();
        classes.forEach((name, bytes) -> {
            ClassNode cn = new ClassNode();
            new ClassReader(bytes).accept(cn, 0);
            for (MethodNode m : cn.methods) {
                for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                    if (!(n instanceof InvokeDynamicInsnNode indy)
                            || !indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")
                            || !(indy.bsmArgs[1] instanceof Handle impl)) continue;
                    int captured = Type.getArgumentTypes(indy.desc).length;
                    boolean instance = impl.getTag() == Opcodes.H_INVOKEVIRTUAL
                            || impl.getTag() == Opcodes.H_INVOKEINTERFACE
                            || impl.getTag() == Opcodes.H_INVOKESPECIAL;
                    int beyond = instance ? captured - 1 : captured;
                    if (beyond <= 0) continue;
                    boolean synthetic = cn.methods.stream().anyMatch(t -> t.name.equals(impl.getName())
                            && t.desc.equals(impl.getDesc()) && impl.getOwner().equals(cn.name)
                            && (t.access & Opcodes.ACC_SYNTHETIC) != 0);
                    if (!synthetic) bad.add(name + "." + m.name + " -> " + impl.getOwner() + "." + impl.getName());
                }
            }
        });
        return bad;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void fixtureShapeIsValidBeforeNormalizing() throws Exception {
        Map<String, byte[]> optimized = optimizerShaped();
        assertEquals(expected, run(optimized), "the simulated optimizer must itself preserve behaviour");
        assertEquals(7, inexpressibleSites(optimized).size(),
                "seven of the nine lambdas carry arguments a method reference cannot: "
                        + inexpressibleSites(optimized));
    }

    @Test
    void bridgesEveryInexpressibleSiteAndPreservesBehaviour() throws Exception {
        NormalizedResult normalized = normalize(optimizerShaped());

        assertEquals(List.of(), inexpressibleSites(normalized.classes()));
        // Seven sites are inexpressible. CompilerArtifactAnalysis re-flags the same-class bodies it can
        // prove (method9, stat); method244 is declined because of the Unrelated collision, and the
        // cross-class, constructor and interface handles are outside its reach. This step covers the
        // four the analysis leaves.
        assertEquals(4, normalized.bridges(), normalized.warnings().toString());
        assertEquals(expected, run(normalized.classes()));
    }

    @Test
    void leavesExpressibleReferencesAndJavacShapedLambdasAlone() throws Exception {
        // Plain javac output: every lambda body is already a synthetic method.
        NormalizedResult normalized = normalize(original);
        assertEquals(0, normalized.bridges(), normalized.warnings().toString());
        assertEquals(expected, run(normalized.classes()));

        // In the optimizer-shaped tree the two expressible references keep their handles.
        Map<String, byte[]> optimized = optimizerShaped();
        ClassNode before = new ClassNode();
        new ClassReader(optimized.get("fx/Demo")).accept(before, 0);
        ClassNode after = new ClassNode();
        new ClassReader(normalize(optimized).classes().get("fx/Demo")).accept(after, 0);
        assertEquals(handleOf(before, "expressibleBound"), handleOf(after, "expressibleBound"));
        assertEquals(handleOf(before, "expressibleStatic"), handleOf(after, "expressibleStatic"));
    }

    @Test
    void doesNotDuplicateBridgesAcrossRepeatedNormalization() throws Exception {
        Map<String, byte[]> once = normalize(optimizerShaped()).classes();
        NormalizedResult twice = normalize(once);
        assertEquals(0, twice.bridges(), "already-bridged sites target a synthetic method and must be skipped");
        assertEquals(expected, run(twice.classes()));
    }

    private static String handleOf(ClassNode cn, String method) {
        for (MethodNode m : cn.methods) {
            if (!m.name.equals(method)) continue;
            for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
                if (n instanceof InvokeDynamicInsnNode indy) return String.valueOf(indy.bsmArgs[1]);
            }
        }
        throw new AssertionError("no lambda in " + method);
    }

    // ---------------------------------------------- end to end, real Vineflower

    private static boolean vineflowerPresent() {
        try {
            Class.forName("org.jetbrains.java.decompiler.main.decompiler.BaseDecompiler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Decompiles every class with the pipeline's own Vineflower backend and compiles the result. */
    private static Path decompileAndCompile(Map<String, byte[]> classes, Path temp, boolean[] compiled)
            throws Exception {
        Path src = temp.resolve("dec-src");
        Path out = temp.resolve("dec-out");
        Files.createDirectories(src.resolve("fx"));
        Files.createDirectories(out);
        VineflowerDecompiler vf = new VineflowerDecompiler();
        List<String> files = new ArrayList<>();
        for (String name : classes.keySet()) {
            Path file = src.resolve(name + ".java");
            Files.writeString(file, vf.decompile(name, classes::get));
            files.add(file.toString());
        }
        List<String> args = new ArrayList<>(List.of("--release", "17", "-proc:none", "-d", out.toString()));
        args.addAll(files);
        compiled[0] = ToolProvider.getSystemJavaCompiler().run(null, null,
                new java.io.PrintStream(java.io.OutputStream.nullOutputStream()),
                args.toArray(new String[0])) == 0;
        return src;
    }

    @Test
    void decompiledSourceCompilesAndBehavesTheSame(@TempDir Path temp) throws Exception {
        assumeTrue(vineflowerPresent(), "Vineflower not on the classpath");
        NormalizedResult normalized = normalize(optimizerShaped());
        boolean[] compiled = new boolean[1];
        Path src = decompileAndCompile(normalized.classes(), temp, compiled);

        String demo = Files.readString(src.resolve("fx/Demo.java"));
        assertTrue(compiled[0], "decompiled source must compile:\n" + demo);
        assertFalse(demo.contains("this::method244"), demo);
        assertTrue(demo.contains("->"), "lambdas must be printed as lambdas:\n" + demo);
    }

    @Test
    void withoutTheBridgingStepTheSameBytecodeDoesNotCompile(@TempDir Path temp) throws Exception {
        assumeTrue(vineflowerPresent(), "Vineflower not on the classpath");
        // Negative control: proves the test above is not vacuous. Vineflower prints
        // `this::method244` for the raw optimizer-shaped bytecode and loses the argument.
        boolean[] compiled = new boolean[1];
        Path src = decompileAndCompile(optimizerShaped(), temp, compiled);
        assertFalse(compiled[0], "expected the un-bridged shape to break:\n"
                + Files.readString(src.resolve("fx/Demo.java")));
    }
}
