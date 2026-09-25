package com.cleandecompile.stage0;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groups every non-constructor, non-static-initializer method across the
 * WHOLE jar (both scopes) into "override families": sets of methods that
 * either directly override/implement one another, or -- for methods that
 * can't participate in virtual dispatch at all (static, private) -- a
 * family of exactly one. Members of the same family must share a single
 * new name if renamed at all; renaming them independently would silently
 * break dynamic dispatch (a call resolved against the supertype's method
 * would stop reaching the subclass's implementation).
 *
 * <p>A family is <b>poisoned</b> -- kept at its original name, never
 * renamed -- if any member:
 * <ul>
 *   <li>belongs to an out-of-scope (bundled library) class,</li>
 *   <li>is {@link MemberIdentifierPolicy#isHardProtectedMethod hard-protected}, or</li>
 *   <li>overrides/implements (directly or transitively) a method declared
 *       on a type that isn't present anywhere in the jar -- a genuine
 *       JDK/library type we have zero visibility into (e.g.
 *       {@code java.lang.Object}, {@code Runnable}, an un-bundled
 *       interface). We can't rule out that name being load-bearing for a
 *       contract we can't see, so the safe default is to leave it alone.</li>
 * </ul>
 *
 * <p>Every method ever unioned into the same family shares the identical
 * original {@code (name, descriptor)} pair, by construction: a union only
 * ever links a method to an ancestor declaring that exact same signature.
 * That's what lets {@link MemberRenamePlanner} pick any single member to
 * stand in for the whole family when checking policy/custom overrides.
 *
 * <p><b>Known narrow limitation:</b> a poisoned family keeps its ORIGINAL
 * bytecode name verbatim, including if that name happens to be illegal as
 * Java source (obfuscators target JVM-legal names, which are far looser
 * than source-legal ones -- see {@link IdentifierSanitizer}). This is
 * deliberately not patched: for every poison cause except a lone
 * {@code native} method, the original name is guaranteed already
 * source-legal (it's a real JDK/library method name, or one of the fixed
 * Serializable/{@code main} names), and for the out-of-scope/external
 * causes specifically, sanitizing would require also renaming the
 * untouchable ancestor to match -- unsafe by definition. A {@code native}
 * method with a genuinely illegal obfuscated name is the one case this
 * can't fix; it's rare enough in practice, and out of the general-obfuscation
 * scope the project plan targets, that Stage 4's compile-fix loop (or a
 * human) is the right place to catch it instead.
 */
final class MethodOverrideGroups {

    /** A family blocked from renaming for a HARD reason (out-of-scope
     *  member, native/serializable/main, or -- via chained unions -- any
     *  family containing such a member) can never be renamed, not even by
     *  an explicit custom override: the name is load-bearing for a
     *  contract Stage 0 cannot see. A family blocked ONLY because its
     *  hierarchy walk touched a type missing from the jar
     *  ({@code externalRoots}) is a heuristic guess, not a known contract:
     *  automatic renames still avoid it, but an explicit custom-names entry
     *  -- the caller stating "I know what this is" -- wins. See
     *  {@link MemberRenamePlanner}. */
    record Result(Map<String, Set<String>> groups, Map<String, Boolean> poisonedRoots,
                  Map<String, Boolean> externalRoots) {}

    private final Map<String, String> parent = new HashMap<>();
    private final Map<String, Boolean> poisonedAt = new HashMap<>(); // valid only when key is currently a root
    private final Map<String, Boolean> externalAt = new HashMap<>(); // same: heuristic-only external touch

    static Result build(Map<String, RawClassHeader> byName) {
        MethodOverrideGroups uf = new MethodOverrideGroups();

        for (RawClassHeader hdr : byName.values()) {
            for (RawClassHeader.Member m : hdr.methods) {
                if (m.name().equals("<init>") || m.name().equals("<clinit>")) continue;

                String selfKey = MemberKeyParts.methodKey(hdr.internalName, m.name(), m.descriptor());
                boolean selfPoison = !hdr.inScope
                        || MemberIdentifierPolicy.isHardProtectedMethod(m.name(), m.descriptor(), m.access());
                uf.ensure(selfKey, selfPoison);

                boolean canOverride = (m.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0;
                if (!canOverride) continue; // standalone -- can't participate in virtual dispatch

                Set<String> visited = new HashSet<>();
                boolean[] touchesExternal = {false};
                List<String> declaringAncestors = new ArrayList<>();
                if (!hdr.isInterface && hdr.superName != null) {
                    walk(hdr.superName, m.name(), m.descriptor(), byName, visited, declaringAncestors, touchesExternal);
                }
                for (String itf : hdr.interfaces) {
                    walk(itf, m.name(), m.descriptor(), byName, visited, declaringAncestors, touchesExternal);
                }

                if (touchesExternal[0]) {
                    uf.markExternal(selfKey);
                }
                for (String ancestorOwner : declaringAncestors) {
                    String ancestorKey = MemberKeyParts.methodKey(ancestorOwner, m.name(), m.descriptor());
                    uf.ensure(ancestorKey, false);
                    uf.union(selfKey, ancestorKey);
                }
            }
        }

        Map<String, Set<String>> groups = new HashMap<>();
        for (String key : uf.parent.keySet()) {
            groups.computeIfAbsent(uf.find(key), r -> new HashSet<>()).add(key);
        }
        Map<String, Boolean> poison = new HashMap<>();
        Map<String, Boolean> external = new HashMap<>();
        for (String root : groups.keySet()) {
            poison.put(root, uf.poisonedAt.getOrDefault(root, false));
            external.put(root, uf.externalAt.getOrDefault(root, false));
        }
        return new Result(groups, poison, external);
    }

    /**
     * True when {@code m} (declared by {@code hdr}) overrides or implements
     * a method of some supertype -- a type in the jar that declares the
     * same name+descriptor, or a JDK/library type that does. Uses exactly
     * the same hierarchy walk as {@link #build}, so "overrides" means the
     * same thing here as it does for family grouping.
     */
    static boolean overridesSomething(RawClassHeader hdr, RawClassHeader.Member m,
                                      Map<String, RawClassHeader> byName) {
        if (m.name().equals("<init>") || m.name().equals("<clinit>")) return false;
        if ((m.access() & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) return false;
        Set<String> visited = new HashSet<>();
        boolean[] touchesExternal = {false};
        List<String> declaringAncestors = new ArrayList<>();
        if (!hdr.isInterface && hdr.superName != null) {
            walk(hdr.superName, m.name(), m.descriptor(), byName, visited, declaringAncestors, touchesExternal);
        }
        for (String itf : hdr.interfaces) {
            walk(itf, m.name(), m.descriptor(), byName, visited, declaringAncestors, touchesExternal);
        }
        return touchesExternal[0] || !declaringAncestors.isEmpty();
    }

    /** {@code java/lang/Object}'s full declared method set. Object is never
     *  bundled in the jar, so a naive "missing ancestor = external contract"
     *  rule poisons EVERY virtual method in EVERY class that transitively
     *  extends it (i.e. nearly all of them). But Object's methods are a
     *  closed, fixed set -- a missing-Object ancestor can only be
     *  load-bearing for one of these exact signatures, never for an
     *  obfuscated {@code a(...)} junk name. Any OTHER missing owner (a real
     *  JDK superclass or interface we can't see) stays conservative: poison.
     *  Constructors are never walked (skipped in {@link #build}). */
    private static final Set<String> OBJECT_METHODS = Set.of(
            "getClass()Ljava/lang/Class;",
            "hashCode()I",
            "equals(Ljava/lang/Object;)Z",
            "clone()Ljava/lang/Object;",
            "toString()Ljava/lang/String;",
            "notify()V",
            "notifyAll()V",
            "wait()V",
            "wait(J)V",
            "wait(JI)V",
            "finalize()V"
    );

    /** Declared-method index of JDK/runtime types, by slash-form internal
     *  name. Missing ancestors that ARE on our own runtime classpath (every
     *  {@code java.*} type) get a precise answer -- poison only when the JDK
     *  type genuinely declares this exact signature. A missing owner that is
     *  NOT resolvable at runtime (an absent third-party dep) stays
     *  conservative ({@link #JDK_UNKNOWN} poisons). Null values are not
     *  storable in a {@link ConcurrentHashMap}, so the unknown case is
     *  sentinel-wrapped. */
    private static final Set<String> JDK_UNKNOWN = Set.of();
    private static final Map<String, Set<String>> JDK_METHOD_CACHE = new ConcurrentHashMap<>();

    /** Walks up from {@code owner} (its superclass chain, for classes, plus
     *  interfaces either way) looking for the nearest ancestor(s) that
     *  directly declare {@code name+descriptor}, stopping each branch as
     *  soon as it finds one (that ancestor's own further ancestors are
     *  handled when the ancestor itself is processed as "self" in the
     *  outer loop -- transitivity comes for free from chained unions). */
    private static void walk(String owner, String name, String descriptor, Map<String, RawClassHeader> byName,
                              Set<String> visited, List<String> declaringAncestors, boolean[] touchesExternal) {
        if (owner == null || !visited.add(owner)) return;
        RawClassHeader hdr = byName.get(owner);
        if (hdr == null) {
            if (owner.equals("java/lang/Object") && !OBJECT_METHODS.contains(name + descriptor)) {
                return; // closed world: Object declares nothing with this signature, no contract to preserve
            }
            if (!jdkDeclares(owner, name, descriptor)) {
                return; // on the runtime classpath and declares no such method -- nothing to preserve
            }
            touchesExternal[0] = true;
            return;
        }
        if (hdr.declaresMethod(name, descriptor)) {
            declaringAncestors.add(owner);
            return;
        }
        if (!hdr.isInterface && hdr.superName != null) {
            walk(hdr.superName, name, descriptor, byName, visited, declaringAncestors, touchesExternal);
        }
        for (String itf : hdr.interfaces) {
            walk(itf, name, descriptor, byName, visited, declaringAncestors, touchesExternal);
        }
    }

    /** True when the missing {@code owner} could genuinely declare this
     *  signature in unseen code. Owners resolvable on our own runtime
     *  classpath (the whole {@code java.*} world) get an exact declared-
     *  method check; unresolvable owners (absent third-party deps) stay
     *  conservative and return true. Results are cached: owners repeat
     *  across tens of thousands of walked methods. */
    private static boolean jdkDeclares(String owner, String name, String descriptor) {
        Set<String> declared = JDK_METHOD_CACHE.computeIfAbsent(owner, o -> {
            try {
                Class<?> c = Class.forName(o.replace('/', '.'),
                        false, MethodOverrideGroups.class.getClassLoader());
                Set<String> set = new HashSet<>();
                for (var m : c.getDeclaredMethods()) {
                    set.add(m.getName() + Type.getMethodDescriptor(m));
                }
                return set;
            } catch (Throwable t) {
                return JDK_UNKNOWN;
            }
        });
        if (declared == JDK_UNKNOWN) return true;
        return declared.contains(name + descriptor);
    }

    private String find(String key) {
        String root = key;
        while (true) {
            String p = parent.get(root);
            if (p == null) {
                parent.put(root, root);
                break;
            }
            if (p.equals(root)) break;
            root = p;
        }
        String cur = key;
        while (!cur.equals(root)) {
            String next = parent.get(cur);
            parent.put(cur, root);
            cur = next;
        }
        return root;
    }

    private void ensure(String key, boolean selfPoisoned) {
        if (!parent.containsKey(key)) {
            parent.put(key, key);
            poisonedAt.put(key, selfPoisoned);
            externalAt.put(key, false);
        } else if (selfPoisoned) {
            String root = find(key);
            poisonedAt.merge(root, true, Boolean::logicalOr);
        }
    }

    private void markExternal(String key) {
        if (!parent.containsKey(key)) {
            parent.put(key, key);
            poisonedAt.put(key, false);
            externalAt.put(key, true);
        } else {
            externalAt.merge(find(key), true, Boolean::logicalOr);
        }
    }

    private void union(String a, String b) {
        String ra = find(a);
        String rb = find(b);
        if (ra.equals(rb)) return;
        boolean poison = poisonedAt.getOrDefault(ra, false) || poisonedAt.getOrDefault(rb, false);
        boolean external = externalAt.getOrDefault(ra, false) || externalAt.getOrDefault(rb, false);
        parent.put(ra, rb);
        poisonedAt.remove(ra);
        poisonedAt.put(rb, poison);
        externalAt.remove(ra);
        externalAt.put(rb, external);
    }
}
