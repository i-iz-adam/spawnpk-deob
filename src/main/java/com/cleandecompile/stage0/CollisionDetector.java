package com.cleandecompile.stage0;

import java.util.HashSet;
import java.util.Set;

/**
 * Tracks every "slot" in the class/package namespace across the WHOLE jar
 * (in-scope and out-of-scope classes both) so a rename never introduces:
 *
 * <ul>
 *   <li>a duplicate class path,</li>
 *   <li>a class path that collides with an existing package path (the
 *       classic {@code rs/a/a.class} vs. {@code rs/a/a/SomeClass.class}
 *       obfuscator artifact), or</li>
 *   <li>a package path that collides with an existing class path.</li>
 * </ul>
 *
 * Out-of-scope (library) classes are registered up front with their
 * original, unchanged paths, since those never move — this is what lets
 * in-scope renaming stay collision-free with respect to bundled libraries
 * without touching them.
 */
public final class CollisionDetector {

    private final Set<String> usedClassPaths = new HashSet<>();
    private final Set<String> usedPackagePrefixes = new HashSet<>();

    /** Commit a final (no longer changing) class path into the namespace. */
    public void register(String fullClassPath) {
        usedClassPaths.add(fullClassPath);
        for (String ancestor : ancestorPrefixes(fullClassPath)) {
            usedPackagePrefixes.add(ancestor);
        }
    }

    /**
     * @return the reason this candidate path would collide with the
     *         already-registered namespace, or null if it's free to use.
     */
    public String collisionReason(String candidateFullClassPath) {
        if (usedClassPaths.contains(candidateFullClassPath)) {
            return "duplicate class path";
        }
        if (usedPackagePrefixes.contains(candidateFullClassPath)) {
            return "candidate is already used as a package path";
        }
        for (String ancestor : ancestorPrefixes(candidateFullClassPath)) {
            if (usedClassPaths.contains(ancestor)) {
                return "ancestor segment '" + ancestor + "' is already a class, can't also be a package";
            }
        }
        return null;
    }

    private static Iterable<String> ancestorPrefixes(String fullClassPath) {
        Set<String> prefixes = new HashSet<>();
        int idx = fullClassPath.indexOf('/');
        while (idx > 0) {
            prefixes.add(fullClassPath.substring(0, idx));
            idx = fullClassPath.indexOf('/', idx + 1);
        }
        return prefixes;
    }
}
