package com.cleandecompile.stage0;

import com.cleandecompile.model.ClassInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structurally recovers the two kinds of compiler-generated methods that
 * decompilers only handle correctly when they are still <i>recognisable as
 * such</i>, and that an obfuscator (or Stage 0's own flag stripping) makes
 * unrecognisable:
 *
 * <h2>1. Lambda bodies</h2>
 * javac compiles {@code x -> foo(a, b, x)} into a private synthetic method
 * {@code lambda$m$0(A a, B b, X x)} plus an {@code invokedynamic} that
 * captures {@code a} and {@code b}. Vineflower inlines the body back into a
 * lambda <i>only if the method carries {@code ACC_SYNTHETIC}</i> (verified
 * against 1.10.1: the name does not matter, the flag does). Without the flag
 * it prints {@code Class::lambda$m$0} and silently drops the captured
 * arguments -- source that cannot compile.
 *
 * <p>A method is reported as a lambda body when an
 * {@code invokedynamic LambdaMetafactory} in its own class targets it,
 * nothing else in the in-scope code references it, and it does not
 * override anything. To avoid hiding a genuine method that merely is the
 * target of a method reference ({@code this::handle}), a candidate must
 * also be <i>strongly</i> lambda-shaped: it takes captured arguments beyond
 * the receiver (a real method reference cannot), or it is named
 * {@code lambda$...}, or it is private. Everything else already prints as a
 * valid method reference, so leaving it alone cannot cause a compile error.
 *
 * <h2>2. Bridge methods</h2>
 * For {@code class C extends Loader<K,V> { V load(K) }} javac also emits the
 * bridge {@code Object load(Object) { return load((K) o); }}. Decompilers
 * hide it only when it is flagged {@code ACC_BRIDGE}; the obfuscated jar
 * carries no such flag on in-scope classes. A method is reported as a
 * bridge when its body is exactly "load {@code this}, load and (where the
 * type differs) checkcast each argument, invoke one different method of the
 * same class, return" <i>and</i> it overrides something -- the second
 * condition keeps a hand-written delegate from being hidden.
 *
 * <p>The bridge's target is returned too, because in this jar the bridge
 * kept its (library-mandated) name while the real implementation was
 * renamed independently; {@link MemberRenamePlanner} uses the pairs to
 * force both to share one name, the only shape source code can express.
 *
 * <p>All keys are {@link MemberKeyParts#methodKey} over the ORIGINAL
 * (pre-rename) owner/name/descriptor, which is what Stage 0 works in until
 * the final remap.
 */
final class CompilerArtifactAnalysis {

    /**
     * @param lambdaBodies  method keys to (re)flag {@code ACC_SYNTHETIC}.
     * @param bridgeToTarget bridge method key -> the method it forwards to.
     * @param notes         human-readable observations for the manifest.
     */
    record Result(Set<String> lambdaBodies, Map<String, String> bridgeToTarget, List<String> notes) {

        static Result empty() {
            return new Result(Set.of(), Map.of(), List.of());
        }

        boolean isLambdaBody(String owner, String name, String descriptor) {
            return lambdaBodies.contains(MemberKeyParts.methodKey(owner, name, descriptor));
        }

        boolean isBridge(String owner, String name, String descriptor) {
            return bridgeToTarget.containsKey(MemberKeyParts.methodKey(owner, name, descriptor));
        }
    }

    private static final String LAMBDA_METAFACTORY = "java/lang/invoke/LambdaMetafactory";

    private CompilerArtifactAnalysis() {
    }

    /** Convenience entry point that indexes headers itself. */
    static Result analyze(List<ClassInfo> allClasses) {
        return analyze(allClasses, RawClassHeader.indexAll(allClasses));
    }

    /** Only in-scope classes are analysed: they are the only ones Stage 0
     *  strips flags from and the only ones that get decompiled. */
    static Result analyze(List<ClassInfo> allClasses, Map<String, RawClassHeader> headers) {
        List<ClassNode> nodes = new ArrayList<>();
        for (ClassInfo ci : allClasses) {
            if (!ci.inScope()) continue;
            try {
                ClassNode node = new ClassNode();
                new ClassReader(ci.bytes()).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
                nodes.add(node);
            } catch (Exception unparsable) {
                // BytecodeNormalizer flags unparsable classes on its own.
            }
        }

        List<String> notes = new ArrayList<>();
        Set<String> lambdaBodies = findLambdaBodies(nodes, headers, notes);
        Map<String, String> bridges = findBridges(nodes, headers);
        // A lambda body is never a bridge (and vice versa): whichever
        // claim is stronger wins, and lambda targets are the stricter one.
        bridges.keySet().removeAll(lambdaBodies);
        return new Result(Collections.unmodifiableSet(lambdaBodies), Collections.unmodifiableMap(bridges),
                Collections.unmodifiableList(notes));
    }

    // ---------------------------------------------------------------- lambdas

    private record Candidate(String key, String owner, String name, String descriptor,
                             boolean strong) {
    }

    private static Set<String> findLambdaBodies(List<ClassNode> nodes, Map<String, RawClassHeader> headers,
                                                List<String> notes) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        Set<String> referenced = new HashSet<>();          // exact owner.name+desc
        Set<String> referencedNameDesc = new HashSet<>();  // virtual calls may name a subtype as owner

        for (ClassNode cls : nodes) {
            for (MethodNode m : cls.methods) {
                if (m.instructions == null) continue;
                for (AbstractInsnNode insn : m.instructions) {
                    if (insn instanceof MethodInsnNode call) {
                        referenced.add(MemberKeyParts.methodKey(call.owner, call.name, call.desc));
                        if (call.getOpcode() == Opcodes.INVOKEVIRTUAL
                                || call.getOpcode() == Opcodes.INVOKEINTERFACE) {
                            referencedNameDesc.add(call.name + call.desc);
                        }
                    } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Handle h) {
                        markReferenced(h, referenced, referencedNameDesc);
                    } else if (insn instanceof InvokeDynamicInsnNode indy) {
                        boolean lambdaFactory = indy.bsm != null && LAMBDA_METAFACTORY.equals(indy.bsm.getOwner())
                                && indy.bsmArgs != null && indy.bsmArgs.length >= 3
                                && indy.bsmArgs[1] instanceof Handle;
                        for (int i = 0; indy.bsmArgs != null && i < indy.bsmArgs.length; i++) {
                            if (!(indy.bsmArgs[i] instanceof Handle h)) continue;
                            if (lambdaFactory && i == 1) {
                                considerLambdaTarget(cls, indy, h, candidates);
                            } else {
                                markReferenced(h, referenced, referencedNameDesc);
                            }
                        }
                    }
                }
            }
        }

        Set<String> bodies = new LinkedHashSet<>();
        int weak = 0;
        for (Candidate c : candidates.values()) {
            RawClassHeader hdr = headers.get(c.owner());
            RawClassHeader.Member member = hdr == null ? null : findMember(hdr, c.name(), c.descriptor());
            if (member == null) continue;
            if ((member.access() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) continue;
            if (MemberIdentifierPolicy.isHardProtectedMethod(member.name(), member.descriptor(), member.access())) {
                continue;
            }
            boolean instance = (member.access() & Opcodes.ACC_STATIC) == 0;
            if (referenced.contains(c.key())) continue;
            if (instance && referencedNameDesc.contains(c.name() + c.descriptor())) continue;
            if (MethodOverrideGroups.overridesSomething(hdr, member, headers)) continue;
            if (!c.strong()) {
                weak++;
                continue;
            }
            bodies.add(c.key());
        }
        if (weak > 0) {
            notes.add("lambda analysis: " + weak + " method-reference-shaped target(s) left unflagged "
                    + "(they print as valid method references)");
        }
        return bodies;
    }

    private static void considerLambdaTarget(ClassNode containing, InvokeDynamicInsnNode indy, Handle impl,
                                             Map<String, Candidate> candidates) {
        int tag = impl.getTag();
        boolean isStatic = tag == Opcodes.H_INVOKESTATIC;
        boolean isInstance = tag == Opcodes.H_INVOKESPECIAL || tag == Opcodes.H_INVOKEVIRTUAL
                || tag == Opcodes.H_INVOKEINTERFACE;
        if (!isStatic && !isInstance) return;                    // constructor refs, field handles
        if (impl.getName().startsWith("<")) return;
        if (!impl.getOwner().equals(containing.name)) return;    // javac keeps lambda bodies in the same class

        int captured = Type.getArgumentTypes(indy.desc).length;
        int capturedBeyondReceiver = isStatic ? captured : captured - 1;

        MethodNode target = null;
        for (MethodNode m : containing.methods) {
            if (m.name.equals(impl.getName()) && m.desc.equals(impl.getDesc())) {
                target = m;
                break;
            }
        }
        if (target == null) return;

        boolean strong = capturedBeyondReceiver > 0
                || impl.getName().startsWith("lambda$")
                || (target.access & Opcodes.ACC_PRIVATE) != 0;
        String key = MemberKeyParts.methodKey(containing.name, impl.getName(), impl.getDesc());
        Candidate previous = candidates.get(key);
        if (previous == null || (strong && !previous.strong())) {
            candidates.put(key, new Candidate(key, containing.name, impl.getName(), impl.getDesc(), strong));
        }
    }

    private static void markReferenced(Handle h, Set<String> referenced, Set<String> referencedNameDesc) {
        referenced.add(MemberKeyParts.methodKey(h.getOwner(), h.getName(), h.getDesc()));
        int tag = h.getTag();
        if (tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKEINTERFACE) {
            referencedNameDesc.add(h.getName() + h.getDesc());
        }
    }

    private static RawClassHeader.Member findMember(RawClassHeader hdr, String name, String descriptor) {
        for (RawClassHeader.Member m : hdr.methods) {
            if (m.name().equals(name) && m.descriptor().equals(descriptor)) return m;
        }
        return null;
    }

    // ---------------------------------------------------------------- bridges

    private static Map<String, String> findBridges(List<ClassNode> nodes, Map<String, RawClassHeader> headers) {
        Map<String, String> bridges = new LinkedHashMap<>();
        for (ClassNode cls : nodes) {
            RawClassHeader hdr = headers.get(cls.name);
            if (hdr == null || hdr.isInterface) continue;
            for (MethodNode m : cls.methods) {
                RawClassHeader.Member member = findMember(hdr, m.name, m.desc);
                if (member == null) continue;
                String target = bridgeTarget(cls, m, hdr);
                if (target == null) continue;
                if (!MethodOverrideGroups.overridesSomething(hdr, member, headers)) continue;
                bridges.put(MemberKeyParts.methodKey(cls.name, m.name, m.desc),
                        MemberKeyParts.methodKey(cls.name, target.substring(0, target.indexOf('(')),
                                target.substring(target.indexOf('('))));
            }
        }
        return bridges;
    }

    /**
     * @return {@code name + descriptor} of the forwarded-to method when
     *         {@code bridge}'s body is exactly the javac bridge shape, else
     *         null.
     */
    private static String bridgeTarget(ClassNode cls, MethodNode bridge, RawClassHeader hdr) {
        if ((bridge.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_ABSTRACT
                | Opcodes.ACC_NATIVE)) != 0) {
            return null;
        }
        if (bridge.name.startsWith("<") || bridge.instructions == null) return null;

        List<AbstractInsnNode> code = new ArrayList<>();
        for (AbstractInsnNode insn : bridge.instructions) {
            int type = insn.getType();
            if (type == AbstractInsnNode.LABEL || type == AbstractInsnNode.LINE
                    || type == AbstractInsnNode.FRAME) {
                continue;
            }
            code.add(insn);
        }
        if (bridge.tryCatchBlocks != null && !bridge.tryCatchBlocks.isEmpty()) return null;

        Type[] bridgeArgs = Type.getArgumentTypes(bridge.desc);
        Type bridgeReturn = Type.getReturnType(bridge.desc);
        int i = 0;
        if (code.size() < 3 || !isLoad(code.get(i++), Opcodes.ALOAD, 0)) return null;

        Type[] casts = new Type[bridgeArgs.length];
        int slot = 1;
        for (int p = 0; p < bridgeArgs.length; p++) {
            if (i >= code.size() || !isLoad(code.get(i), bridgeArgs[p].getOpcode(Opcodes.ILOAD), slot)) {
                return null;
            }
            i++;
            slot += bridgeArgs[p].getSize();
            if (i < code.size() && code.get(i).getOpcode() == Opcodes.CHECKCAST) {
                casts[p] = Type.getObjectType(((TypeInsnNode) code.get(i)).desc);
                i++;
            }
        }
        if (i >= code.size() || !(code.get(i) instanceof MethodInsnNode call)) return null;
        if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !call.owner.equals(cls.name)) return null;
        i++;
        if (i != code.size() - 1) return null;
        if (code.get(i).getOpcode() != returnOpcode(bridgeReturn)) return null;

        if (call.name.equals(bridge.name) && call.desc.equals(bridge.desc)) return null; // self-recursion
        Type[] targetArgs = Type.getArgumentTypes(call.desc);
        if (targetArgs.length != bridgeArgs.length) return null;
        for (int p = 0; p < targetArgs.length; p++) {
            Type expected = casts[p] != null ? casts[p] : bridgeArgs[p];
            if (!expected.equals(targetArgs[p])) return null;
        }
        if (!compatibleReturn(bridgeReturn, Type.getReturnType(call.desc))) return null;

        RawClassHeader.Member target = findMember(hdr, call.name, call.desc);
        if (target == null || (target.access() & Opcodes.ACC_STATIC) != 0) return null;
        return call.name + call.desc;
    }

    private static boolean isLoad(AbstractInsnNode insn, int opcode, int slot) {
        return insn instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == slot;
    }

    private static int returnOpcode(Type t) {
        return t.getSort() == Type.VOID ? Opcodes.RETURN : t.getOpcode(Opcodes.IRETURN);
    }

    private static boolean compatibleReturn(Type bridge, Type target) {
        if (bridge.equals(target)) return true;
        boolean bridgeRef = bridge.getSort() == Type.OBJECT || bridge.getSort() == Type.ARRAY;
        boolean targetRef = target.getSort() == Type.OBJECT || target.getSort() == Type.ARRAY;
        return bridgeRef && targetRef;
    }
}
