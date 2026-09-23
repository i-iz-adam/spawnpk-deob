package com.cleandecompile.stage1;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Decompiles exactly one class with a hard wall-clock budget, falling
 * through a priority-ordered list of {@link Decompiler} backends, and — if
 * every backend fails or times out — emits a compiling stub recovered
 * directly from bytecode via ASM rather than leaving the class missing.
 *
 * <p>One pathological method (giant synthetic switch table, deeply inlined
 * lambda chain) must never hang the whole batch, so every attempt runs on
 * its own thread and is simply abandoned (not joined) on timeout. That
 * leaks a stuck thread per timeout, which is an acceptable trade for
 * "the batch always finishes" — Stage 1's manifest records every timeout so
 * chronically slow classes are visible and can be pre-filtered on a rerun.
 */
public final class DecompileWorker {

    private final List<Decompiler> decompilersInPriorityOrder;
    private final long baseTimeoutMs;
    private final ExecutorService pool;

    public DecompileWorker(List<Decompiler> decompilersInPriorityOrder, long baseTimeoutMs) {
        this.decompilersInPriorityOrder = decompilersInPriorityOrder;
        this.baseTimeoutMs = baseTimeoutMs;
        this.pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "decompile-worker");
            t.setDaemon(true);
            return t;
        });
    }

    /** One backend's output for a class: null source means it failed,
     *  timed out, or produced nothing usable (see its log entry). */
    public record BackendOutput(String decompilerName, String source,
                                DecompileResult.AttemptLogEntry logEntry) {
    }

    public List<BackendOutput> decompileAll(String internalName, byte[] classBytes,
                                            Function<String, byte[]> classBytesProvider) {
        List<BackendOutput> outputs = new ArrayList<>();
        long timeoutMs = adaptiveTimeout(classBytes.length);

        for (Decompiler decompiler : decompilersInPriorityOrder) {
            Future<String> future = pool.submit(() -> decompiler.decompile(internalName, classBytesProvider));
            try {
                String source = future.get(timeoutMs, TimeUnit.MILLISECONDS);
                if (source == null || source.isBlank()) {
                    throw new IllegalStateException(decompiler.name() + " returned empty output");
                }
                outputs.add(new BackendOutput(decompiler.name(), source,
                        new DecompileResult.AttemptLogEntry(
                                decompiler.name(), DecompileResult.Outcome.SUCCESS, null)));
            } catch (TimeoutException te) {
                future.cancel(true);
                outputs.add(new BackendOutput(decompiler.name(), null,
                        new DecompileResult.AttemptLogEntry(
                                decompiler.name(), DecompileResult.Outcome.TIMED_OUT, timeoutMs + "ms")));
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                outputs.add(new BackendOutput(decompiler.name(), null,
                        new DecompileResult.AttemptLogEntry(
                                decompiler.name(), DecompileResult.Outcome.FAILED, String.valueOf(cause))));
            }
        }
        return outputs;
    }

    public void shutdown() {
        pool.shutdownNow();
    }

    /**
     * Bigger classes get proportionally more time before we give up on
     * them -- a flat timeout either wastes time waiting on trivial classes
     * or gives up too early on large, legitimately-slow ones.
     */
    private long adaptiveTimeout(int classByteLength) {
        long bonus = classByteLength / 200L; // ~5ms per 1KB of bytecode
        return Math.min(baseTimeoutMs + bonus, baseTimeoutMs * 8);
    }

    /** Recovers a compiling Java stub (correct signature, fields, method
     *  headers) directly from bytecode via ASM when no decompiler succeeded. */
    static final class StubGenerator {
        private StubGenerator() {}

        static String generate(String internalName, byte[] classBytes,
                               Function<String, byte[]> classBytesProvider) {
            ClassNode node = new ClassNode();
            try {
                new ClassReader(classBytes).accept(node, ClassReader.SKIP_FRAMES);
            } catch (Exception e) {
                return "// STUB GENERATION FAILED for " + internalName + ": " + e
                        + "\n// This class could not even be parsed by ASM; check the raw bytes.\n";
            }

            StringBuilder sb = new StringBuilder();
            String pkg = internalName.contains("/")
                    ? internalName.substring(0, internalName.lastIndexOf('/')).replace('/', '.')
                    : null;
            String simpleName = internalName.contains("/")
                    ? internalName.substring(internalName.lastIndexOf('/') + 1)
                    : internalName;

            if (pkg != null) sb.append("package ").append(pkg).append(";\n\n");
            sb.append("// AUTO-GENERATED STUB -- every decompiler backend failed or timed out on this class.\n");
            sb.append("// Signature and fields are recovered from bytecode and should be accurate.\n");
            sb.append("// Method bodies are NOT implemented -- see the bytecode dump per method below.\n");
            sb.append("// TODO: manually port these method bodies (or rerun decompilation with a longer timeout).\n\n");

            boolean isInterface = (node.access & Opcodes.ACC_INTERFACE) != 0;
            boolean isEnum = (node.access & Opcodes.ACC_ENUM) != 0;
            sb.append(classModifiers(node.access, isInterface || isEnum))
                    .append(isInterface ? "interface " : isEnum ? "enum " : "class ").append(simpleName);
            if (!isInterface && !isEnum && node.superName != null && !node.superName.equals("java/lang/Object")) {
                sb.append(" extends ").append(sourceTypeName(node.superName));
            }
            if (node.interfaces != null && !node.interfaces.isEmpty()) {
                sb.append(isInterface ? " extends " : " implements ");
                sb.append(String.join(", ", node.interfaces.stream().map(DecompileWorker.StubGenerator::sourceTypeName).toList()));
            }
            sb.append(" {\n\n");

            String ownDescriptor = "L" + node.name + ";";
            List<String> enumConstants = new ArrayList<>();
            if (isEnum) {
                for (Object fObj : node.fields) {
                    FieldNode f = (FieldNode) fObj;
                    if ((f.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
                                    == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
                            && f.desc.equals(ownDescriptor)) {
                        enumConstants.add(f.name);
                    }
                }
                if (!enumConstants.isEmpty()) {
                    sb.append("    ").append(String.join(", ", enumConstants)).append(";\n\n");
                }
            }

            for (Object fObj : node.fields) {
                FieldNode f = (FieldNode) fObj;
                if (isEnum && enumConstants.contains(f.name) && f.desc.equals(ownDescriptor)) continue;
                sb.append("    ").append(modifiers(f.access))
                        .append(sourceTypeName(Type.getType(f.desc))).append(' ').append(f.name)
                        .append(" = ").append(defaultValue(Type.getType(f.desc))).append(";\n");
            }
            sb.append('\n');

            for (Object mObj : node.methods) {
                MethodNode m = (MethodNode) mObj;
                if ("<clinit>".equals(m.name)) continue;
                if (isEnum && (m.access & Opcodes.ACC_STATIC) != 0
                        && (("values".equals(m.name) && "()".equals(stripReturn(m.desc)))
                                || ("valueOf".equals(m.name) && "(Ljava/lang/String;)".equals(stripReturn(m.desc))))) {
                    continue; // compiler-generated (this jar's obfuscator also strips
                              // the SYNTHETIC flag, so match by shape instead);
                              // the enum header redeclares them implicitly
                }

                Type methodType = Type.getMethodType(m.desc);
                boolean isCtor = "<init>".equals(m.name);
                Type[] args = methodType.getArgumentTypes();
                int firstVisibleArg = 0;
                // Non-static inner-class constructors take a synthetic outer
                // instance as their first bytecode parameter; source callers
                // pass it implicitly (outer.new Inner() / no-args from
                // inside), so printing it produces a stub no caller matches.
                if (isCtor && args.length > 0 && isNonStaticInner(node)
                        && args[0].getSort() == Type.OBJECT
                        && args[0].getInternalName().equals(outerNameOf(node))) {
                    firstVisibleArg = 1;
                }
                // Interface methods are implicitly abstract: only static and
                // default (public, non-abstract instance) methods may carry
                // a body. Everything else is a bodyless declaration, or
                // javac fails with "interface abstract methods cannot have
                // body". (Abstract CLASS methods keep their throwing body
                // and stay concrete -- compilable, semantics for later.)
                boolean emitBody = !isInterface
                        || (m.access & Opcodes.ACC_STATIC) != 0
                        || ((m.access & Opcodes.ACC_PUBLIC) != 0
                                && (m.access & Opcodes.ACC_ABSTRACT) == 0);
                String dump = indent(textify(m));
                if (!emitBody) {
                    sb.append("        /* original bytecode:\n").append(dump).append("        */\n");
                }
                sb.append("    ").append(interfaceMemberModifiers(m.access, isInterface));
                // Source-level default methods need the explicit keyword:
                // a public non-abstract instance method in an interface is
                // only legal with a body when marked default.
                boolean isDefault = isInterface && !isCtor
                        && (m.access & Opcodes.ACC_PUBLIC) != 0
                        && (m.access & Opcodes.ACC_ABSTRACT) == 0
                        && (m.access & Opcodes.ACC_STATIC) == 0
                        && (m.access & Opcodes.ACC_PRIVATE) == 0;
                if (isDefault) sb.append("default ");
                if (!isCtor) sb.append(sourceTypeName(methodType.getReturnType())).append(' ');
                sb.append(isCtor ? simpleName : m.name).append('(');
                for (int i = firstVisibleArg; i < args.length; i++) {
                    if (i > firstVisibleArg) sb.append(", ");
                    sb.append(sourceTypeName(args[i])).append(" arg").append(i - firstVisibleArg);
                }
                if (!emitBody) {
                    sb.append(");\n\n");
                    continue;
                }
                sb.append(") {\n");
                if (isCtor && !isInterface && !isEnum
                        && node.superName != null && !node.superName.equals("java/lang/Object")) {
                    // A throwing body without an explicit super(...) call
                    // gets an implicit super(), which fails when the
                    // superclass has no no-args constructor. Chain with
                    // default literals matched to a real super constructor.
                    sb.append("        super(").append(superCallArgs(node, classBytesProvider)).append(");\n");
                }
                sb.append("        throw new UnsupportedOperationException(")
                        .append("\"stub: decompilation failed, see bytecode dump below\");\n");
                sb.append("        /* original bytecode:\n");
                sb.append(dump);
                sb.append("        */\n");
                sb.append("    }\n\n");
            }
            if (isEnum && !enumConstants.isEmpty() && !hasNoArgsCtor(node)) {
                // No-args enum constants need a no-args constructor; the
                // bytecode ones always take at least (String, int).
                sb.append("    private ").append(simpleName).append("() {\n");
                sb.append("        throw new UnsupportedOperationException(")
                        .append("\"stub: decompilation failed, see bytecode dump below\");\n");
                sb.append("    }\n\n");
            }

            sb.append("}\n");
            return sb.toString();
        }

        private static boolean hasNoArgsCtor(ClassNode node) {
            for (Object mObj : node.methods) {
                MethodNode m = (MethodNode) mObj;
                if ("<init>".equals(m.name) && "()V".equals(m.desc)) return true;
            }
            return false;
        }

        /** Descriptor minus return type, for shape-matching compiler-
         *  generated members (see the enum values()/valueOf() skip). */
        private static String stripReturn(String methodDescriptor) {
            return methodDescriptor.substring(0, methodDescriptor.indexOf(')') + 1);
        }

        private static boolean isNonStaticInner(ClassNode node) {
            if (node.innerClasses == null) return false;
            for (Object icObj : node.innerClasses) {
                org.objectweb.asm.tree.InnerClassNode ic =
                        (org.objectweb.asm.tree.InnerClassNode) icObj;
                if (node.name.equals(ic.name) && ic.outerName != null
                        && (ic.access & Opcodes.ACC_STATIC) == 0) {
                    return true;
                }
            }
            return false;
        }

        private static String outerNameOf(ClassNode node) {
            if (node.innerClasses == null) return null;
            for (Object icObj : node.innerClasses) {
                org.objectweb.asm.tree.InnerClassNode ic =
                        (org.objectweb.asm.tree.InnerClassNode) icObj;
                if (node.name.equals(ic.name)) return ic.outerName;
            }
            return null;
        }

        /** Default-literal argument list for an explicit {@code super(...)}
         *  chain, resolved against the superclass's real constructors. Falls
         *  back to empty (implicit {@code super()}) when the superclass
         *  bytes aren't available. */
        private static String superCallArgs(ClassNode node, Function<String, byte[]> classBytesProvider) {
            try {
                byte[] superBytes = classBytesProvider.apply(node.superName);
                if (superBytes == null) return reflectiveSuperArgs(node.superName);
                ClassNode superNode = new ClassNode();
                new ClassReader(superBytes).accept(superNode,
                        ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                String fallback = null;
                for (Object mObj : superNode.methods) {
                    MethodNode m = (MethodNode) mObj;
                    if (!"<init>".equals(m.name)) continue;
                    if ("()V".equals(m.desc)) return "";
                    if (fallback == null) fallback = m.desc;
                }
                if (fallback == null) return "";
                Type[] args = Type.getArgumentTypes(fallback);
                List<String> lits = new ArrayList<>();
                for (Type t : args) lits.add(defaultValue(t));
                return String.join(", ", lits);
            } catch (Exception e) {
                return "";
            }
        }

        /** Same as above for a superclass that lives on our own runtime
         *  classpath (a real JDK type) instead of in the jar. */
        private static String reflectiveSuperArgs(String superInternalName) {
            try {
                Class<?> c = Class.forName(superInternalName.replace('/', '.'),
                        false, StubGenerator.class.getClassLoader());
                java.lang.reflect.Constructor<?> picked = null;
                for (var ctor : c.getDeclaredConstructors()) {
                    if (ctor.getParameterCount() == 0) return "";
                    if (picked == null) picked = ctor;
                }
                if (picked == null) return "";
                List<String> lits = new ArrayList<>();
                for (Class<?> p : picked.getParameterTypes()) {
                    lits.add(defaultValue(Type.getType(p)));
                }
                return String.join(", ", lits);
            } catch (Throwable t) {
                return "";
            }
        }

        /** Top-level type modifiers for the stub header. Without these a
         *  public obfuscated class degrades to a package-private stub and
         *  every cross-package reference fails with "X is not public".
         *  Abstract is intentionally NOT shared with {@link #modifiers}:
         *  stub methods always carry a (throwing) body, which an abstract
         *  modifier would make illegal. Interfaces and enums take public
         *  only -- the obfuscator sets junk flags (final/abstract) the JVM
         *  ignores but javac rejects ({@code final enum} does not compile).
         *  @param typeKind {@code true} for interfaces and enums. */
        private static String classModifiers(int access, boolean typeKind) {
            StringBuilder m = new StringBuilder();
            if ((access & Opcodes.ACC_PUBLIC) != 0) m.append("public ");
            if (!typeKind && (access & Opcodes.ACC_ABSTRACT) != 0) m.append("abstract ");
            if (!typeKind && (access & Opcodes.ACC_FINAL) != 0) m.append("final ");
            return m.toString();
        }

        /** Source-legal spelling of a type: {@code Type.getClassName()}
         *  renders inner classes with a bytecode {@code $} separator
         *  ({@code java.awt.TrayIcon$MessageType}), which javac rejects --
         *  source nests with {@code .} instead. */
        private static String sourceTypeName(Type type) {
            return type.getClassName().replace('$', '.');
        }

        private static String sourceTypeName(String internalName) {
            return internalName.replace('/', '.').replace('$', '.');
        }

        /** Method modifiers for stub bodies. Interface members additionally
         *  drop {@code protected} (must be public or private) and
         *  {@code final} (never allowed) -- obfuscators set both freely
         *  since the JVM doesn't care. */
        private static String interfaceMemberModifiers(int access, boolean isInterface) {
            String mods = modifiers(access);
            if (isInterface) {
                mods = mods.replace("protected ", "").replace("final ", "");
            }
            return mods;
        }

        private static String modifiers(int access) {            StringBuilder m = new StringBuilder();
            if ((access & Opcodes.ACC_PUBLIC) != 0) m.append("public ");
            else if ((access & Opcodes.ACC_PROTECTED) != 0) m.append("protected ");
            else if ((access & Opcodes.ACC_PRIVATE) != 0) m.append("private ");
            if ((access & Opcodes.ACC_STATIC) != 0) m.append("static ");
            if ((access & Opcodes.ACC_FINAL) != 0) m.append("final ");
            return m.toString();
        }

        private static String defaultValue(Type type) {
            return switch (type.getSort()) {
                case Type.BOOLEAN -> "false";
                case Type.CHAR -> "'\\0'";
                case Type.BYTE, Type.SHORT, Type.INT -> "0";
                case Type.LONG -> "0L";
                case Type.FLOAT -> "0f";
                case Type.DOUBLE -> "0d";
                default -> "null";
            };
        }

        private static String textify(MethodNode m) {
            Textifier textifier = new Textifier();
            m.accept(new TraceMethodVisitor(textifier));
            StringWriter sw = new StringWriter();
            textifier.print(new PrintWriter(sw));
            return sw.toString();
        }

        private static String indent(String text) {
            return text.lines().map(l -> "        " + l).reduce("", (a, b) -> a + b + "\n");
        }
    }
}
