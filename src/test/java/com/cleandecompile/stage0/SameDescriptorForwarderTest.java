package com.cleandecompile.stage0;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cleandecompile.model.ClassInfo;
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
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * A javac bridge exists to bridge an <b>erased</b> signature to a specific
 * one, so a real bridge's descriptor always DIFFERS from the descriptor of
 * the method it forwards to. A forwarder with the <i>same</i> descriptor is
 * a hand-written delegate, and javac would reject two methods in one class
 * sharing a name and descriptor -- so it can never be the compiler artifact
 * this pass exists to recover.
 *
 * <p>{@code CompilerArtifactAnalysis} used to accept it anyway, and
 * {@link BytecodeNormalizer} then re-flagged it {@code ACC_BRIDGE}, which
 * makes both decompilers drop the declaration while keeping its call sites:
 * "no suitable method found for m(int,int,int,int,int)". This fixture is
 * the real jar's shape ({@code rs.Client.b(IIIII)V -> a(IIIII)V}) rebuilt in
 * miniature, and the fixture asserts the declaration survives.
 */
class SameDescriptorForwarderTest {

    /**
     * A base class whose method the subclass "must" override, so the
     * forwarder is a plausible bridge candidate at all. Declared in a
     * separate class because two methods in one class may not share a
     * name+descriptor.
     */
    private static final String BASE = """
            package fx;

            public abstract class Base {
                public abstract void b(int a, int b, int c, int d, int e);
            }
            """;

    /**
     * {@code Sub.b(IIIII)V} forwards verbatim to {@code Sub.a(IIIII)V}.
     * Same descriptor, so this is a delegate, not a bridge. Real code calls
     * {@code b}, and the call must keep resolving after Stage 0.
     */
    private static final String SUB = """
            package fx;

            public class Sub extends Base {
                static int sink;

                public void a(int a, int b, int c, int d, int e) {
                    sink = a + b + c + d + e;
                }

                @Override
                public void b(int a, int b, int c, int d, int e) {
                    this.a(a, b, c, d, e);
                }

                public int callIt() {
                    this.b(1, 2, 3, 4, 5);
                    return sink;
                }
            }
            """;

    /**
     * A genuine bridge. The interface is generic and {@code Real} pins it to
     * {@code String}, so javac must emit the erased forwarder
     * {@code load(Object)} alongside the real {@code load(String)} -- the
     * exact shape of the real jar's {@code rs/A/b.load(Object) ->
     * a(rs/A/a$a)}. The descriptors differ, and this one MUST still be
     * recognised and hidden -- otherwise the decompiler prints a bogus
     * second override.
     */
    private static final String IFACE = """
            package fx;

            public interface Iface<T> {
                String load(T key);
            }
            """;

    private static final String REAL = """
            package fx;

            public class Real implements Iface<String> {
                @Override
                public String load(String key) {
                    return key;
                }
            }
            """;

    private static Map<String, byte[]> original;

    @BeforeAll
    static void compileFixture(@TempDir Path temp) throws Exception {
        Path src = temp.resolve("src");
        Path out = temp.resolve("out");
        Files.createDirectories(src.resolve("fx"));
        Files.createDirectories(out);
        for (String[] pair : new String[][]{{"Base", BASE}, {"Sub", SUB}, {"Iface", IFACE}, {"Real", REAL}}) {
            Files.writeString(src.resolve("fx/" + pair[0] + ".java"), pair[1]);
        }
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        StringWriter errors = new StringWriter();
        int rc = javac.run(null, null, new java.io.PrintStream(new java.io.OutputStream() {
            @Override
            public void write(int b) {
                errors.write(b);
            }
        }), "--release", "17", "-d", out.toString(),
                src.resolve("fx/Base.java").toString(), src.resolve("fx/Sub.java").toString(),
                src.resolve("fx/Iface.java").toString(), src.resolve("fx/Real.java").toString());
        assertEquals(0, rc, errors.toString());

        original = new LinkedHashMap<>();
        for (String n : List.of("fx/Base", "fx/Sub", "fx/Iface", "fx/Real")) {
            original.put(n, Files.readAllBytes(out.resolve(n + ".class")));
        }
    }

    private static List<ClassInfo> inScope() {
        List<ClassInfo> out = new ArrayList<>();
        original.forEach((name, bytes) -> out.add(new ClassInfo(name, bytes, true)));
        return out;
    }

    @Test
    void sameDescriptorForwarderIsNotTreatedAsACompilerBridge() {
        var repairs = CompilerArtifactAnalysis.analyze(inScope());
        assertFalse(repairs.isBridge("fx/Sub", "b", "(IIIII)V"),
                "a forwarder with the SAME descriptor as its target is a hand-written delegate, "
                        + "not a javac bridge; hiding it deletes a declaration real code calls");
        assertTrue(repairs.isBridge("fx/Real", "load", "(Ljava/lang/Object;)Ljava/lang/String;"),
                "a real bridge (erased load(Object) -> load(String)) must still be recognised");
    }

    @Test
    void sameDescriptorForwarderKeepsItsDeclarationAfterNormalization() {
        var classes = inScope();
        var repairs = CompilerArtifactAnalysis.analyze(classes);
        var renames = new RenameMapBuilder().build(classes, CustomNameOverrides.none());
        var plan = new MemberRenamePlanner().plan(classes, CustomNameOverrides.none(), repairs);
        var result = new BytecodeNormalizer().normalizeAll(classes, renames.renameMap(),
                plan.methodRenameMap(), plan.fieldRenameMap(), true, repairs);

        MethodNode forwarder = null;
        for (ClassInfo ci : result.normalizedClasses()) {
            // Stage 0 also relocates the package, so match on the simple name.
            if (!ci.internalName().endsWith("/Sub")) continue;
            ClassNode node = new ClassNode();
            new ClassReader(ci.bytes()).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
            // The name may legitimately have been renamed (Stage 0 renames
            // short names); what must not happen is the method DISAPPEARING
            // or being flagged in a way that hides it from a decompiler.
            for (MethodNode m : node.methods) {
                if (m.desc.equals("(IIIII)V") && (m.access & Opcodes.ACC_ABSTRACT) == 0
                        && m.instructions != null && m.instructions.size() > 0) {
                    boolean forwards = false;
                    for (var insn : m.instructions) {
                        if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
                                && call.desc.equals("(IIIII)V")) {
                            forwards = true;
                        }
                    }
                    if (forwards) forwarder = m;
                }
            }
        }
        assertTrue(forwarder != null, "fx/Sub's (IIIII)V forwarder must survive Stage 0");
        assertEquals(0, forwarder.access & Opcodes.ACC_BRIDGE,
                "ACC_BRIDGE makes the decompiler drop the declaration while keeping call sites");
        assertEquals(0, forwarder.access & Opcodes.ACC_SYNTHETIC,
                "ACC_SYNTHETIC makes the decompiler drop the declaration while keeping call sites");
    }
}
