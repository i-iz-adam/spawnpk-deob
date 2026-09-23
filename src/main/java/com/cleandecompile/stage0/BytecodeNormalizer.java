package com.cleandecompile.stage0;

import com.cleandecompile.model.ClassInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.ParameterNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites every class (in-scope AND out-of-scope) through ASM's
 * {@code ClassRemapper} with a {@link QualifiedRemapper} built from Stage
 * 0's class, method, and field rename maps. Because the maps only contain
 * entries for in-scope classes/members, out-of-scope classes come out
 * byte-for-byte equivalent except for cross-references that point at
 * something renamed on the in-scope side -- exactly the "leave bundled
 * libraries alone" behavior we want.
 *
 * <p>While every class is already being walked, this also fixes the other
 * Stage 0 targets from the project plan:
 * <ul>
 *   <li><b>Malformed StackMapTable/LocalVariableTable entries</b> -- rather
 *       than trying to patch these obfuscator-bug artifacts field by
 *       field, we read with the debug/frames info ASM can't make sense of
 *       skipped, then ask {@code ClassWriter} to recompute stack map
 *       frames from scratch ({@code COMPUTE_FRAMES}).</li>
 *   <li><b>Synthetic bridge/access methods</b> -- not stripped here, but
 *       every one is logged to the warnings list so Stage 4's
 *       duplicate-bridge-method fixer knows where to look first.</li>
 *   <li><b>Local variable and parameter names</b> -- where debug info
 *       survives, {@code ClassRemapper} doesn't touch these on its own (it
 *       only remaps TYPE references, not the variable's own name string),
 *       so they're renamed directly on the {@code ClassNode} before the
 *       remap. Most obfuscated jars have already stripped this attribute
 *       entirely -- see the debug-discard fallback below -- so in practice
 *       this only fires for the subset of classes that kept it.</li>
 * </ul>
 *
 * <h2>Why there's a custom {@code ClassWriter}</h2>
 * {@code COMPUTE_FRAMES} has to resolve, at every branch-merge point, the
 * nearest common supertype of two stack/local types. ASM's default
 * implementation of that ({@code ClassWriter.getCommonSuperClass}) does it
 * via {@code Class.forName(...)} against the JVM's actual classpath. For an
 * obfuscated jar being processed in-memory, essentially none of its own
 * classes are on that classpath -- so the default implementation fails
 * reflection lookups (and eats the exception cost) at nearly every merge
 * point, in nearly every method, of nearly every class. On a jar with a few
 * thousand classes that turns Stage 0 from a few seconds into something
 * that looks hung.
 *
 * <p>{@link HierarchyAwareClassWriter} fixes this by answering common-
 * superclass queries from an in-memory map of the jar's own type hierarchy
 * (built once up front, keyed by each class's FINAL/post-rename name, since
 * that's the name space {@code COMPUTE_FRAMES} actually queries against
 * during the remap). Reflection is only used as a last resort at the
 * boundary -- a type genuinely not bundled in the jar (a real JDK/library
 * class) -- where it's rare and cheap instead of the common case.
 */
public final class BytecodeNormalizer {

    public record Warning(String internalName, String message) {}

    public record Result(List<ClassInfo> normalizedClasses, List<Warning> warnings) {}

    /** superName/interfaces are already translated to their FINAL (post-rename) names. */
    private record TypeInfo(String superName, List<String> interfaces, boolean isInterface) {}

    public Result normalizeAll(List<ClassInfo> allClasses, Map<String, String> classRenameMap,
                                Map<String, String> methodRenameMap, Map<String, String> fieldRenameMap) {
        Map<String, TypeInfo> typeHierarchy = buildTypeHierarchy(allClasses, classRenameMap);
        QualifiedRemapper remapper = new QualifiedRemapper(classRenameMap, methodRenameMap, fieldRenameMap);
        List<ClassInfo> out = new ArrayList<>(allClasses.size());
        List<Warning> warnings = new ArrayList<>();
        int total = allClasses.size();
        int processed = 0;
        long startedAt = System.currentTimeMillis();

        for (ClassInfo ci : allClasses) {
            processed++;
            if (processed == 1 || processed % 500 == 0 || processed == total) {
                double secs = (System.currentTimeMillis() - startedAt) / 1000.0;
                System.out.printf("  normalizing %d/%d (%.1fs elapsed)%n", processed, total, secs);
            }
            try {
                String newName = classRenameMap.getOrDefault(ci.internalName(), ci.internalName());
                byte[] normalized = normalizeOne(ci, remapper, typeHierarchy, warnings, false);
                // The bytes now declare the RENAMED class; the model must
                // follow, or every downstream stage (stage0 jar entries,
                // Stage 1 output paths/stub headers, Stage 3 vendoring)
                // addresses the class by a stale name that matches neither
                // its bytes nor anything else on disk. Out-of-scope classes
                // have no map entry and keep their original name.
                out.add(new ClassInfo(newName, normalized, ci.inScope()));
            } catch (Exception primaryFailure) {
                // Fallback: strip debug info entirely and retry. Most
                // obfuscator-bug corruption lives in LVT/LineNumber
                // attributes; dropping them and recomputing frames from
                // the raw instruction stream is usually enough.
                try {
                    String newName = classRenameMap.getOrDefault(ci.internalName(), ci.internalName());
                    byte[] normalized = normalizeOne(ci, remapper, typeHierarchy, warnings, true);
                    out.add(new ClassInfo(newName, normalized, ci.inScope()));
                    warnings.add(new Warning(ci.internalName(),
                            "recovered by discarding debug info after: " + primaryFailure));
                } catch (Exception secondaryFailure) {
                    // Leave this class's bytes untouched. Stage 1's
                    // per-class isolation will hit the same corruption and
                    // fall through its own decompiler chain to the
                    // bytecode-only stub -- this class is flagged now so
                    // that's not a surprise later.
                    out.add(ci);
                    warnings.add(new Warning(ci.internalName(),
                            "normalization failed, left unmodified: " + secondaryFailure));
                }
            }
        }
        return new Result(out, warnings);
    }

    /** Cheap header-only pass (no full ClassNode parse) over every class in
     *  the jar, building the in-memory hierarchy map keyed by final names. */
    private Map<String, TypeInfo> buildTypeHierarchy(List<ClassInfo> allClasses, Map<String, String> classRenameMap) {
        Map<String, TypeInfo> map = new HashMap<>(allClasses.size() * 2);
        for (ClassInfo ci : allClasses) {
            ClassReader r;
            try {
                r = new ClassReader(ci.bytes());
            } catch (Exception e) {
                continue; // unparsable class; falls through to Object at query time
            }
            String finalName = classRenameMap.getOrDefault(r.getClassName(), r.getClassName());
            String rawSuper = r.getSuperName();
            String finalSuper = rawSuper == null ? null : classRenameMap.getOrDefault(rawSuper, rawSuper);

            List<String> finalInterfaces = new ArrayList<>();
            for (String itf : r.getInterfaces()) {
                finalInterfaces.add(classRenameMap.getOrDefault(itf, itf));
            }
            boolean isInterface = (r.getAccess() & Opcodes.ACC_INTERFACE) != 0;
            map.put(finalName, new TypeInfo(finalSuper, finalInterfaces, isInterface));
        }
        return map;
    }

    private byte[] normalizeOne(ClassInfo ci, QualifiedRemapper remapper, Map<String, TypeInfo> typeHierarchy,
                                 List<Warning> warnings, boolean skipDebug) {
        int readFlags = skipDebug ? ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES
                                   : ClassReader.SKIP_FRAMES;

        ClassReader reader = new ClassReader(ci.bytes());
        ClassNode node = new ClassNode();
        reader.accept(node, readFlags);

        for (Object mObj : node.methods) {
            MethodNode m = (MethodNode) mObj;
            if (!skipDebug) {
                if ((m.access & Opcodes.ACC_BRIDGE) != 0 || (m.access & Opcodes.ACC_SYNTHETIC) != 0) {
                    warnings.add(new Warning(ci.internalName(),
                            "synthetic/bridge method retained: " + m.name + m.desc));
                }
                renameLocalsAndParameters(m);
            }
        }

        ClassWriter writer = new HierarchyAwareClassWriter(
                ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS, typeHierarchy);
        ClassRemapper remapVisitor = new ClassRemapper(writer, remapper);
        node.accept(remapVisitor);
        return writer.toByteArray();
    }

    /** Best-effort local variable / parameter renaming for the (usually
     *  small) subset of classes that still carry debug info. {@code
     *  ClassRemapper} never touches these on its own -- it only remaps the
     *  TYPE half of a {@code LocalVariableNode}/{@code ParameterNode}, not
     *  the name string -- so it's done directly here, before the remap.
     *  Scoped per-method (locals only need to be unique within their own
     *  method), unlike fields/methods which get a jar-wide unique name. */
    private void renameLocalsAndParameters(MethodNode m) {
        if (m.localVariables != null) {
            int counter = 0;
            for (Object lvObj : m.localVariables) {
                LocalVariableNode lv = (LocalVariableNode) lvObj;
                if (lv.name.equals("this")) continue; // keep -- decompilers rely on this exact name
                lv.name = "local" + counter++;
            }
        }
        if (m.parameters != null) {
            for (int i = 0; i < m.parameters.size(); i++) {
                ParameterNode p = (ParameterNode) m.parameters.get(i);
                if (p == null) continue;
                if ((p.access & (Opcodes.ACC_SYNTHETIC | Opcodes.ACC_MANDATED)) != 0) continue;
                p.name = "arg" + i;
            }
        }
    }

    /**
     * Resolves {@code COMPUTE_FRAMES}'s common-superclass queries from an
     * in-memory map of the jar's own classes first, falling back to
     * reflection only for types not bundled in the jar. See the class-level
     * javadoc above for why this matters.
     */
    private static final class HierarchyAwareClassWriter extends ClassWriter {
        private final Map<String, TypeInfo> typeHierarchy;

        HierarchyAwareClassWriter(int flags, Map<String, TypeInfo> typeHierarchy) {
            super(flags);
            this.typeHierarchy = typeHierarchy;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            try {
                if (type1.equals(type2)) return type1;
                if (isAssignableFrom(type1, type2, new HashSet<>())) return type1;
                if (isAssignableFrom(type2, type1, new HashSet<>())) return type2;
                if (isInterface(type1) || isInterface(type2)) return "java/lang/Object";

                String current = type1;
                int guard = 0;
                while (current != null && !current.equals("java/lang/Object") && guard++ < 2000) {
                    current = superOf(current);
                    if (current != null && isAssignableFrom(current, type2, new HashSet<>())) {
                        return current;
                    }
                }
                return "java/lang/Object";
            } catch (Exception e) {
                // Never let a single ambiguous merge point take the whole
                // class down -- Object is always a legal (if imprecise)
                // common supertype for verification purposes.
                return "java/lang/Object";
            }
        }

        private boolean isAssignableFrom(String superType, String subType, Set<String> seen) {
            if (superType.equals(subType) || superType.equals("java/lang/Object")) return true;
            if (!seen.add(subType)) return false; // cycle guard

            TypeInfo info = typeHierarchy.get(subType);
            if (info != null) {
                for (String itf : info.interfaces()) {
                    if (isAssignableFrom(superType, itf, seen)) return true;
                }
                return info.superName() != null && isAssignableFrom(superType, info.superName(), seen);
            }
            // Boundary type not bundled in the jar (a real JDK/library
            // class) -- this is the only place reflection is used, and
            // it's rare once the jar's own classes are all in the map.
            return reflectiveIsAssignableFrom(superType, subType);
        }

        private boolean isInterface(String type) {
            TypeInfo info = typeHierarchy.get(type);
            if (info != null) return info.isInterface();
            try {
                return Class.forName(type.replace('/', '.'), false, getClass().getClassLoader()).isInterface();
            } catch (Throwable t) {
                return false;
            }
        }

        private String superOf(String type) {
            TypeInfo info = typeHierarchy.get(type);
            if (info != null) return info.superName();
            try {
                Class<?> c = Class.forName(type.replace('/', '.'), false, getClass().getClassLoader());
                Class<?> s = c.getSuperclass();
                return s == null ? null : s.getName().replace('.', '/');
            } catch (Throwable t) {
                return null;
            }
        }

        private boolean reflectiveIsAssignableFrom(String superType, String subType) {
            try {
                Class<?> superClass = Class.forName(superType.replace('/', '.'), false, getClass().getClassLoader());
                Class<?> subClass = Class.forName(subType.replace('/', '.'), false, getClass().getClassLoader());
                return superClass.isAssignableFrom(subClass);
            } catch (Throwable t) {
                return false;
            }
        }
    }
}
