package com.cleandecompile.stage0;

import java.util.Set;

/**
 * Hands out names like {@code field0}, {@code field1}, ... (whatever
 * {@code prefix} is configured) that are guaranteed distinct from every
 * name already in {@code claimed} -- which callers seed with every custom
 * name the user has already assigned in that namespace, so an
 * auto-generated name can never collide with a manually-chosen one, even
 * if the counter would otherwise land on that exact literal text (e.g. a
 * custom name that happens to be spelled {@code "field3"}).
 */
final class UniqueNamePool {
    private final String prefix;
    private final Set<String> claimed;
    private long counter = 0;

    /** @param claimed mutated in place as names are handed out; seed it
     *                 with every name this namespace must never reuse. */
    UniqueNamePool(String prefix, Set<String> claimed) {
        this.prefix = prefix;
        this.claimed = claimed;
    }

    String next() {
        String candidate;
        do {
            candidate = prefix + counter++;
        } while (!claimed.add(candidate));
        return candidate;
    }
}
