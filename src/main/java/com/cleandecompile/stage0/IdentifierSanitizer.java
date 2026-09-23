package com.cleandecompile.stage0;

import java.util.Set;

/**
 * Per-segment identifier legality checks. Obfuscators only have to produce
 * names that are legal at the *bytecode* level (almost anything goes — the
 * JVM spec's identifier rules are far looser than Java source's), so a
 * generic decompiler regularly emits source that can't compile: a package
 * or class literally named "int", a class named "1", a field named "a-b".
 * This class flags every segment (package component or simple class name)
 * that would be illegal or unwise as Java source, without deciding what to
 * rename it to — that's {@link RenameMapBuilder}'s job.
 */
public final class IdentifierSanitizer {

    // JLS 3.9 reserved words, plus contextual keywords/literals that are
    // safe to avoid entirely rather than special-case.
    private static final Set<String> RESERVED = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new",
            "package", "private", "protected", "public", "return", "short", "static",
            "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
            "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "var", "yield", "record", "sealed", "permits",
            "module", "requires", "exports", "open", "opens", "uses", "provides", "with", "to"
    );

    private IdentifierSanitizer() {}

    /** True if this single package-component or simple-class-name segment
     *  is illegal or inadvisable as Java source and needs renaming. */
    public static boolean needsRename(String segment) {
        if (segment.isEmpty()) return true;
        if (RESERVED.contains(segment)) return true;
        if (!Character.isJavaIdentifierStart(segment.charAt(0))) return true;
        for (int i = 1; i < segment.length(); i++) {
            if (!Character.isJavaIdentifierPart(segment.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Produces a legal replacement for an illegal segment. Not guaranteed
     * unique on its own — {@link RenameMapBuilder} resolves collisions
     * across the whole rename map afterward.
     */
    public static String sanitize(String segment) {
        if (segment.isEmpty()) return "_empty";

        StringBuilder sb = new StringBuilder(segment.length() + 1);
        char first = segment.charAt(0);
        if (!Character.isJavaIdentifierStart(first)) {
            sb.append('_');
            if (Character.isJavaIdentifierPart(first)) sb.append(first);
        } else {
            sb.append(first);
        }
        for (int i = 1; i < segment.length(); i++) {
            char c = segment.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        if (RESERVED.contains(sb.toString())) {
            sb.append('_');
        }
        return sb.toString();
    }
}
