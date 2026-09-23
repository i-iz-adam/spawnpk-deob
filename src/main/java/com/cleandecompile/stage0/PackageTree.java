package com.cleandecompile.stage0;

import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.model.RenameEntry;

import java.util.*;

/**
 * Resolves Stage 0's class/package renaming as a genuine tree problem
 * instead of patching classes independently.
 *
 * <h2>Short names always get replaced, long names stay put</h2>
 * A segment shorter than {@code minKeepableLength} (default 3, from
 * {@link CustomNameOverrides#minKeepableLength}) is ALWAYS replaced with a
 * fresh globally-unique name ({@code Class0}... / {@code pkg0}...), even if
 * already a legal Java identifier -- obfuscators overwhelmingly favor 1-2
 * character names ({@code a}, {@code b}, {@code rs.f}), while genuine names
 * ({@code Client}, {@code cache}, {@code eventbus}, {@code gui}) are almost
 * never that short. A segment at/above that length is kept verbatim unless
 * it is source-illegal (then it gets an auto name) or collides with a
 * sibling (then it is disambiguated with a numeric suffix). Anything PINNED
 * -- your {@code --own-package} root itself, or an explicit
 * {@link CustomNameOverrides} entry -- keeps its exact text regardless of
 * length. Out-of-scope (bundled library) classes are always left
 * byte-identical.
 *
 * <h2>The classic package/class collision</h2>
 * The one structural wrinkle this still has to handle: a single tree node
 * can be BOTH a class's own leaf position AND a package (has children) --
 * e.g. an obfuscator emitting {@code rs/a/a} while {@code rs/a/a/Other}
 * also exists, so {@code rs/a/a} needs to simultaneously be a file and a
 * directory. Auto-generated names sidestep this for free -- the class gets
 * a name from an entirely different pool (`Class7`) than the directory
 * role (`pkg12`), so they can never coincidentally collide. A PINNED class
 * name still needs the classic fix: a second, distinct name for the
 * class-file role specifically, unique among the same siblings.
 *
 * <p>Because a node's tree position can come from a custom override rather
 * than the class's true original path, every leaf keeps a direct reference
 * to its {@link ClassInfo} rather than reconstructing "original path" from
 * tree segments -- {@code ci.internalName()} is always the ground truth,
 * independent of where the tree decided to place it.
 */
final class PackageTree {

    static final class Node {
        final String segment; // this level's DESIRED-path segment (may already reflect a custom override)
        final Node parent;
        final Map<String, Node> children = new LinkedHashMap<>();
        ClassInfo outOfScopeClass; // set if an out-of-scope class's (always-unchanged) path ends exactly here
        ClassInfo inScopeClass;    // set if an in-scope class's DESIRED path ends exactly here
        boolean pinned;            // true if `segment` came from an explicit custom override (or an implied --own-package pin)
        String finalPackageSegment; // name descendants compose their paths against
        String finalClassSegment;   // name used for the class file that ends exactly here (only set when inScopeClass != null)

        Node(String segment, Node parent) {
            this.segment = segment;
            this.parent = parent;
        }
    }

    record Placement(String originalInternalName, String newInternalName, String reason) {}

    private final Node root = new Node("", null);

    /** @param desired the class's DESIRED starting path -- its own
     *                 original path unless a custom override (or an
     *                 implied {@code --own-package} pin) puts it somewhere
     *                 else. See {@link CustomNameOverrides#desiredPathFor}. */
    void insert(ClassInfo ci, CustomNameOverrides.DesiredPath desired) {
        Node cur = root;
        List<String> segs = desired.segments();
        for (int i = 0; i < segs.size(); i++) {
            String seg = segs.get(i);
            Node child = cur.children.get(seg);
            if (child == null) {
                child = new Node(seg, cur);
                cur.children.put(seg, child);
            }
            if (i < desired.pinnedPrefixLength()) child.pinned = true;
            cur = child;
        }
        if (ci.inScope()) cur.inScopeClass = ci;
        else cur.outOfScopeClass = ci;
    }

    /** Resolves final names for every node, then returns one Placement per
     *  in-scope class whose final path differs from its true original.
     *  {@code packagePool}/{@code classPool} supply fresh names for short
     *  or illegal segments; long, legal segments are kept verbatim (see
     *  class javadoc). {@code minKeepableLength} is the length threshold --
     *  segments at/above it are keepable, below it always replaced. */
    List<Placement> resolve(UniqueNamePool packagePool, UniqueNamePool classPool, int minKeepableLength) {
        resolveChildren(root, packagePool, classPool, minKeepableLength);
        List<Placement> placements = new ArrayList<>();
        collectPlacements(root, placements);
        return placements;
    }

    private void resolveChildren(Node parent, UniqueNamePool packagePool, UniqueNamePool classPool,
                                 int minKeepableLength) {
        Set<String> usedAtThisLevel = new HashSet<>();

        // 1. Out-of-scope siblings are fixed -- their bytes are written
        //    under their literal original name no matter what, so nothing
        //    else at this level may claim it.
        for (Node child : parent.children.values()) {
            if (child.outOfScopeClass != null) {
                child.finalPackageSegment = child.segment;
                usedAtThisLevel.add(child.finalPackageSegment);
            }
        }

        // 2. Pinned children (an explicit custom override, or an implied
        //    --own-package pin) get their exact requested text next,
        //    ahead of anything auto-generated -- an organic collision
        //    should never bump an intentional name. (Already validated
        //    legal at config-load time, so no sanitize step here -- just
        //    resolve a genuine namespace collision, e.g. against an
        //    out-of-scope sibling.)
        for (Node child : parent.children.values()) {
            if (child.finalPackageSegment != null) continue;
            if (child.pinned) {
                String candidate = disambiguate(child.segment, usedAtThisLevel);
                child.finalPackageSegment = candidate;
                usedAtThisLevel.add(candidate);
            }
        }

        // 3. Everything else (non-pinned, in-scope-derived positions):
        //    keep long, legal names verbatim (Client, cache, gui survive);
        //    replace short or illegal ones with a fresh globally-unique
        //    name (a, b, f, rs junk goes away). Kept names still get
        //    sibling-disambiguation so a kept name can never collide with
        //    an out-of-scope or pinned sibling at the same level.
        for (Node child : parent.children.values()) {
            if (child.finalPackageSegment != null) continue;
            if (isKeepable(child.segment, minKeepableLength) && !usedAtThisLevel.contains(child.segment)) {
                child.finalPackageSegment = child.segment;
            } else if (isKeepable(child.segment, minKeepableLength)) {
                String candidate = disambiguate(child.segment, usedAtThisLevel);
                child.finalPackageSegment = candidate;
            } else {
                child.finalPackageSegment = packagePool.next();
            }
            usedAtThisLevel.add(child.finalPackageSegment);
        }

        // The class-file role is resolved independently of the
        // directory role above. Pinned classes still need the classic
        // fix (a second, distinct name) if they're also a directory.
        // Kept (long, legal) class names need the same fix when the node
        // is BOTH a class and a package -- otherwise file and directory
        // would share one name. Auto-generated classes draw from an
        // entirely different pool than packages, so they never collide.
        for (Node child : parent.children.values()) {
            if (child.inScopeClass == null) continue;
            if (child.pinned) {
                if (!child.children.isEmpty()) {
                    String classCandidate = disambiguate(child.finalPackageSegment + "_", usedAtThisLevel);
                    child.finalClassSegment = classCandidate;
                    usedAtThisLevel.add(classCandidate);
                } else {
                    child.finalClassSegment = child.finalPackageSegment;
                }
            } else if (isKeepable(child.segment, minKeepableLength)) {
                if (child.children.isEmpty()) {
                    if (!usedAtThisLevel.contains(child.segment)
                            || child.segment.equals(child.finalPackageSegment)) {
                        // No directory-role collision at this level to worry
                        // about beyond the package segment itself, which for
                        // a leaf equals this same text -- reuse it.
                        child.finalClassSegment = child.finalPackageSegment;
                    } else {
                        String classCandidate = disambiguate(child.segment, usedAtThisLevel);
                        child.finalClassSegment = classCandidate;
                        usedAtThisLevel.add(classCandidate);
                    }
                } else {
                    // Both a class AND a directory: directory already took
                    // the kept text, so the file role needs its own name.
                    String classCandidate = disambiguate(child.finalPackageSegment + "_", usedAtThisLevel);
                    child.finalClassSegment = classCandidate;
                    usedAtThisLevel.add(classCandidate);
                }
            } else {
                child.finalClassSegment = classPool.next();
            }
        }

        for (Node child : parent.children.values()) {
            resolveChildren(child, packagePool, classPool, minKeepableLength);
        }
    }

    private boolean isKeepable(String segment, int minKeepableLength) {
        // Inner-class simple names (a$a, EventBus$Subscriber) are ALWAYS
        // replaced: stubs emit each class as its own top-level file, where
        // a $ name either reads as junk or -- worse -- breaks the
        // Outer.Inner nesting the rest of the tree references. The remapper
        // keeps the renamed class linked to its outer via the InnerClasses
        // attribute either way.
        if (segment.contains("$")) {
            return false;
        }
        return segment.length() >= minKeepableLength && !IdentifierSanitizer.needsRename(segment);
    }

    private String disambiguate(String candidate, Set<String> usedAtThisLevel) {
        if (!usedAtThisLevel.contains(candidate)) return candidate;
        int suffix = 1;
        String attempt;
        do {
            attempt = candidate + suffix;
            suffix++;
        } while (usedAtThisLevel.contains(attempt));
        return attempt;
    }

    private void collectPlacements(Node node, List<Placement> out) {
        for (Node child : node.children.values()) {
            if (child.inScopeClass != null) {
                String originalPath = child.inScopeClass.internalName();
                String finalPath = fullFinalPath(child);
                if (!originalPath.equals(finalPath)) {
                    out.add(new Placement(originalPath, finalPath, classifyReason(child)));
                }
            }
            collectPlacements(child, out);
        }
    }

    private String classifyReason(Node child) {
        return child.pinned ? RenameEntry.REASON_CUSTOM_OVERRIDE : RenameEntry.REASON_UNIQUE_NAME;
    }

    /** Composes the FINAL path for {@code node} -- ancestors via
     *  {@code finalPackageSegment}, the node itself via
     *  {@code finalClassSegment}. The ORIGINAL path is never reconstructed
     *  from tree segments; see {@link #collectPlacements}, which reads it
     *  straight off the leaf's {@link ClassInfo} instead. */
    private String fullFinalPath(Node node) {
        List<String> parts = new ArrayList<>();
        parts.add(node.finalClassSegment);
        Node cur = node.parent;
        while (cur != null && cur.parent != null) {
            parts.add(cur.finalPackageSegment);
            cur = cur.parent;
        }
        Collections.reverse(parts);
        return String.join("/", parts);
    }
}