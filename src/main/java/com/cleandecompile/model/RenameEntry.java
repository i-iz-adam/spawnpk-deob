package com.cleandecompile.model;

/**
 * One entry in the Stage 0 rename manifest: why a class/package segment was
 * renamed, so a human can audit the mapping and Stage 4 can explain
 * "unresolved symbol" errors that trace back to a rename decision.
 */
public record RenameEntry(
        String originalInternalName,
        String newInternalName,
        String reason
) {
    public static final String REASON_RESERVED_WORD = "reserved-word-segment";
    public static final String REASON_INVALID_IDENTIFIER = "invalid-identifier-segment";
    public static final String REASON_PACKAGE_CLASS_COLLISION = "package-class-name-collision";
    public static final String REASON_DUPLICATE_AFTER_SANITIZE = "duplicate-after-sanitize";
    /** Assigned from the global "Class0"/"pkg0"... counter -- the default
     *  for every in-scope class/package segment that isn't pinned. See
     *  {@link com.cleandecompile.stage0.PackageTree}. */
    public static final String REASON_UNIQUE_NAME = "global-unique-name";
    /** Came from the user-supplied custom-names file (a package-prefix or
     *  full-class override), or is the caller's own {@code --own-package}
     *  root kept exactly as typed, rather than being auto-generated. */
    public static final String REASON_CUSTOM_OVERRIDE = "custom-override";
}