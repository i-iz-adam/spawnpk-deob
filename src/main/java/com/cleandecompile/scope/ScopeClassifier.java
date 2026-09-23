package com.cleandecompile.scope;

import java.util.List;

/**
 * Decides which classes are "the application" (get renamed, decompiled,
 * pushed through the compile-fix loop) versus "a bundled library" (left
 * byte-identical, handled in Stage 3 by fingerprint-or-vendor instead).
 *
 * <p>This is the piece that makes the pipeline safe to run against a jar
 * where only part of the tree is the actual target — e.g. a plugin or game
 * client that bundles obfuscated third-party libraries alongside its own
 * obfuscated code. We deliberately do NOT try to guess "is this library
 * code" by heuristics (package structure, obfuscation density, etc.) —
 * that's unreliable. Scope is an explicit, user-provided allowlist of
 * package prefixes. Everything else is out of scope by default, which is
 * the safe direction: worst case you under-decompile and have to add a
 * prefix, rather than silently renaming a bundled library's internals and
 * breaking reflection/serialization inside it.
 */
public final class ScopeClassifier {

    private final List<String> ownedPrefixes; // slash form, e.g. "com/naxos"

    public ScopeClassifier(List<String> ownedPrefixesSlashForm) {
        this.ownedPrefixes = ownedPrefixesSlashForm;
    }

    /**
     * @param internalName JVM internal name, e.g. "com/naxos/game/Client"
     */
    public boolean isInScope(String internalName) {
        if (ownedPrefixes.isEmpty()) {
            return true; // no allowlist configured -> treat the whole jar as the target
        }
        for (String prefix : ownedPrefixes) {
            if (internalName.equals(prefix) || internalName.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
