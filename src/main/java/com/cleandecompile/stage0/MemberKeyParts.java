package com.cleandecompile.stage0;

/**
 * Builds/splits the composite {@code owner+name+descriptor} strings used as
 * {@link MethodOverrideGroups} node ids and {@link QualifiedRemapper} map
 * keys.
 *
 * <p>Splitting back is unambiguous because none of the three parts can
 * contain the separators used here: a JVM unqualified name (field/method
 * simple name) may never contain {@code .}, {@code ;}, {@code [} or
 * {@code /} (JVM Spec 4.2.2), and a method descriptor always starts with
 * {@code (} while a field descriptor never contains {@code :} or the first
 * {@code .}. So the first {@code .} in a key is always the owner/name
 * boundary, and the first {@code (} (methods) or {@code :} (fields) after
 * it is always the name/descriptor boundary.
 */
final class MemberKeyParts {
    private MemberKeyParts() {}

    record Parts(String owner, String name, String descriptor) {}

    static String methodKey(String owner, String name, String descriptor) {
        return owner + '.' + name + descriptor;
    }

    static String fieldKey(String owner, String name, String descriptor) {
        return owner + '.' + name + ':' + descriptor;
    }

    /** Splits a key of the form {@code owner + '.' + name + descriptor}
     *  (descriptor starting with '('), as used for methods. */
    static Parts parseMethodKey(String key) {
        int dot = key.indexOf('.');
        int paren = key.indexOf('(', dot);
        return new Parts(key.substring(0, dot), key.substring(dot + 1, paren), key.substring(paren));
    }

    /** Splits a key of the form {@code owner + '.' + name + ':' + descriptor}. */
    static Parts parseFieldKey(String key) {
        int dot = key.indexOf('.');
        int colon = key.indexOf(':', dot);
        return new Parts(key.substring(0, dot), key.substring(dot + 1, colon), key.substring(colon + 1));
    }
}
