package com.cleandecompile.stage0;

import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gives every lambda that a method reference <i>cannot express</i> a
 * synthetic body method of its own, so decompilers print it as a lambda
 * instead of a broken method reference.
 *
 * <h2>The problem</h2>
 * A {@code LambdaMetafactory} call site is {@code invokedynamic} with a
 * <i>captured</i> argument list plus an implementation handle. When the
 * handle points at an ordinary method and the call site captures more values
 * than the handle's receiver, e.g.
 * <pre>
 *   invokedynamic get(Plugin, String)Runnable   impl = Plugin.method244(String)V
 * </pre>
 * the only source that means the same thing is {@code () -> this.method244(s)}.
 * An optimizer can produce this shape by pointing the call site straight at
 * the wrapped call and dropping javac's {@code lambda$x$0} shim. Vineflower
 * 1.10.1 prints such a site as {@code this::method244}, silently losing
 * {@code s}; javac then reports
 * <pre>
 *   invalid method reference: method method244 cannot be applied to given
 *   types; required: String, found: no arguments
 * </pre>
 * (CFR recovers some of these shapes and crashes on others.) Nothing in the
 * decompiled text says what the lost arguments were, which is why the
 * source-level {@code LambdaRestoreFixer} can only guess from same-typed
 * locals.
 *
 * <h2>The fix</h2>
 * Stage 0 already re-flags <i>lambda bodies</i> ({@link CompilerArtifactAnalysis})
 * because both decompilers inline a lambda only when its target is
 * {@code ACC_SYNTHETIC}. That analysis is deliberately conservative (a
 * method also called directly, or whose original short name+descriptor
 * collides with an unrelated virtual call, must stay visible), so it
 * declines some genuine lambda bodies, and it never sees handles that point
 * into another class. This step covers every such site exactly, without
 * touching the wrapped method: it adds a private static synthetic
 * {@code lambda$stage0$N} to the class containing the call site whose body is
 * nothing but "load every argument, perform the handle's call, return", and
 * re-points the call site at it. The decompiler then sees the shape javac
 * itself would have produced.
 *
 * <p>The bridge's parameters are the call site's captured types followed by
 * the handle's remaining parameter types, and it returns the handle's return
 * type, so {@code LambdaMetafactory}'s own adaptation between the interface
 * method and the implementation is unchanged: this step never has to
 * re-implement boxing, widening or casting. Sites that a method reference
 * <i>can</i> express (bound receiver only, static or unbound-receiver
 * references with nothing captured) are left alone, as are serializable
 * lambdas (their identity is the implementation's name), {@code invokespecial}
 * handles into another class ({@code super::m}), and sites whose target is
 * already an inlinable synthetic method.
 */
final class LambdaCaptureBridger {

    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    private static final String ALT_METAFACTORY = "altMetafactory";
    private static final int FLAG_SERIALIZABLE = 1;
    static final String BRIDGE_PREFIX = "lambda$stage0$";

    /** One bridge that was created, for the manifest. */
    record Bridge(String owner, String name, String descriptor, String wrapped) {
    }

    private LambdaCaptureBridger() {
    }

    /**
     * Bridges every inexpressible lambda call site in {@code cls} and returns
     * the bridges created (empty when none). Must run after the synthetic-flag
     * pass, because "already inlinable" is decided from the flags as they
     * stand then.
     *
     * @param repairs the lambda bodies {@link CompilerArtifactAnalysis} will
     *                re-flag; a site targeting one of them is already handled.
     */
    static List<Bridge> bridge(ClassNode cls, CompilerArtifactAnalysis.Result repairs) {
        if (cls.methods == null) return List.of();
        boolean isInterface = (cls.access & Opcodes.ACC_INTERFACE) != 0;

        Set<String> taken = new HashSet<>();
        Map<String, MethodNode> own = new HashMap<>();
        for (MethodNode m : cls.methods) {
            taken.add(m.name + m.desc);
            own.put(m.name + m.desc, m);
        }

        Map<String, Bridge> created = new HashMap<>();   // handle key -> bridge
        List<MethodNode> added = new ArrayList<>();
        List<Bridge> out = new ArrayList<>();
        int counter = 0;

        for (MethodNode m : cls.methods) {
            if (m.instructions == null) continue;
            for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
                Handle impl = inexpressibleTarget(indy);
                if (impl == null) continue;
                if (alreadyInlinable(cls, impl, own, repairs)) continue;

                String key = impl.getTag() + "|" + impl.getOwner() + "|" + impl.getName() + impl.getDesc()
                        + "|" + impl.isInterface() + "|" + indy.desc;
                Bridge bridge = created.get(key);
                if (bridge == null) {
                    String name;
                    String desc = bridgeDescriptor(impl, Type.getArgumentTypes(indy.desc));
                    do {
                        name = BRIDGE_PREFIX + counter++;
                    } while (taken.contains(name + desc));
                    taken.add(name + desc);
                    added.add(buildBridge(name, desc, impl));
                    bridge = new Bridge(cls.name, name, desc,
                            impl.getOwner() + "." + impl.getName() + impl.getDesc());
                    created.put(key, bridge);
                    out.add(bridge);
                }
                indy.bsmArgs[1] = new Handle(Opcodes.H_INVOKESTATIC, cls.name, bridge.name(),
                        bridge.descriptor(), isInterface);
            }
        }
        cls.methods.addAll(added);
        return out;
    }

    /**
     * The implementation handle when this call site is a lambda that a method
     * reference cannot express and that this step knows how to bridge;
     * {@code null} for everything else.
     */
    private static Handle inexpressibleTarget(InvokeDynamicInsnNode indy) {
        if (indy.bsm == null || !LAMBDA_METAFACTORY.equals(indy.bsm.getOwner())) return null;
        Object[] args = indy.bsmArgs;
        if (args == null || args.length < 3 || !(args[1] instanceof Handle impl)) return null;
        if (ALT_METAFACTORY.equals(indy.bsm.getName())
                && args.length > 3 && args[3] instanceof Integer flags
                && (flags & FLAG_SERIALIZABLE) != 0) {
            return null;                                   // identity is the implementation's name
        }

        int captured = Type.getArgumentTypes(indy.desc).length;
        int tag = impl.getTag();
        switch (tag) {
            case Opcodes.H_INVOKESTATIC, Opcodes.H_NEWINVOKESPECIAL -> {
                if (captured == 0) return null;            // Class::m / Class::new
            }
            case Opcodes.H_INVOKEVIRTUAL, Opcodes.H_INVOKEINTERFACE, Opcodes.H_INVOKESPECIAL -> {
                if (captured <= 1) return null;            // this::m / obj::m / Class::m (unbound)
            }
            default -> {
                return null;                               // field handles are not valid here
            }
        }
        return impl;
    }

    /** Whether decompilers already print this site correctly: the target is a
     *  synthetic method of this very class (javac's own shape). */
    private static boolean alreadyInlinable(ClassNode cls, Handle impl, Map<String, MethodNode> own,
                                            CompilerArtifactAnalysis.Result repairs) {
        if (impl.getTag() == Opcodes.H_INVOKESPECIAL && !impl.getOwner().equals(cls.name)) {
            return true;                                   // super::m -- not bridgeable, leave as is
        }
        if (!impl.getOwner().equals(cls.name)) return false;
        if (repairs.isLambdaBody(cls.name, impl.getName(), impl.getDesc())) return true;
        MethodNode target = own.get(impl.getName() + impl.getDesc());
        return target != null && (target.access & Opcodes.ACC_SYNTHETIC) != 0;
    }

    /** Captured types first (they must reach the call unchanged), then the
     *  handle's remaining parameters; the receiver is the first parameter of
     *  an instance handle. */
    private static String bridgeDescriptor(Handle impl, Type[] captured) {
        List<Type> implParams = new ArrayList<>();
        boolean instance = isInstance(impl.getTag());
        if (instance) implParams.add(Type.getObjectType(impl.getOwner()));
        implParams.addAll(List.of(Type.getArgumentTypes(impl.getDesc())));

        List<Type> params = new ArrayList<>(implParams.size());
        for (int i = 0; i < implParams.size(); i++) {
            params.add(i < captured.length ? captured[i] : implParams.get(i));
        }
        Type ret = impl.getTag() == Opcodes.H_NEWINVOKESPECIAL
                ? Type.getObjectType(impl.getOwner())
                : Type.getReturnType(impl.getDesc());
        return Type.getMethodDescriptor(ret, params.toArray(new Type[0]));
    }

    private static boolean isInstance(int tag) {
        return tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE
                || tag == Opcodes.H_INVOKESPECIAL;
    }

    private static MethodNode buildBridge(String name, String desc, Handle impl) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                name, desc, null, null);
        Type[] params = Type.getArgumentTypes(desc);
        Type ret = Type.getReturnType(desc);

        boolean construct = impl.getTag() == Opcodes.H_NEWINVOKESPECIAL;
        if (construct) {
            mn.instructions.add(new TypeInsnNode(Opcodes.NEW, impl.getOwner()));
            mn.instructions.add(new InsnNode(Opcodes.DUP));
        }
        int slot = 0;
        for (Type p : params) {
            mn.instructions.add(new VarInsnNode(p.getOpcode(Opcodes.ILOAD), slot));
            slot += p.getSize();
        }
        int opcode = switch (impl.getTag()) {
            case Opcodes.H_INVOKESTATIC -> Opcodes.INVOKESTATIC;
            case Opcodes.H_INVOKEVIRTUAL -> Opcodes.INVOKEVIRTUAL;
            case Opcodes.H_INVOKEINTERFACE -> Opcodes.INVOKEINTERFACE;
            default -> Opcodes.INVOKESPECIAL;              // H_INVOKESPECIAL and constructors
        };
        mn.instructions.add(new MethodInsnNode(opcode, impl.getOwner(), impl.getName(), impl.getDesc(),
                impl.isInterface()));
        mn.instructions.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        return mn;
    }
}
