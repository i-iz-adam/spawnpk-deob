package com.cleandecompile.model;

/**
 * One entry in Stage 0's method/field rename manifest -- the member-level
 * counterpart to {@link RenameEntry}, which only ever covered classes and
 * packages. Covers both actual renames and documented "kept" decisions (a
 * member Stage 0 deliberately left alone), so the manifest can explain
 * every member's fate, not just the ones that changed.
 *
 * @param kind         {@link #KIND_FIELD} or {@link #KIND_METHOD}.
 * @param owner        the member's declaring class, in its ORIGINAL
 *                      (pre-rename) internal-name form. This matches the
 *                      owner half of the key the rename maps are built
 *                      from, so it stays the pre-rename name even once the
 *                      class itself has moved elsewhere.
 * @param originalName the member's original (obfuscated) simple name.
 * @param descriptor    JVM field or method descriptor.
 * @param newName      the assigned name, or {@code null} when {@code kept}
 *                      is true.
 * @param kept         true if this entry documents a decision NOT to
 *                      rename this member; false if it documents an
 *                      actual rename.
 * @param reason       machine-readable reason code, see the
 *                      {@code REASON_*} constants.
 */
public record MemberRenameEntry(
        String kind,
        String owner,
        String originalName,
        String descriptor,
        String newName,
        boolean kept,
        String reason
) {
    public static final String KIND_FIELD = "field";
    public static final String KIND_METHOD = "method";

    /** Assigned from the global "field0"/"method0"... counter. */
    public static final String REASON_UNIQUE_NAME = "global-unique-name";
    /** Method-only: this name is shared with every other method in the
     *  same virtual-dispatch override family. */
    public static final String REASON_OVERRIDE_GROUP = "shared-override-group-name";
    /** Came from the user-supplied custom-names file. */
    public static final String REASON_CUSTOM_OVERRIDE = "custom-override";
    /** Renaming this would risk a silent behavior change (JNI linkage,
     *  reflection-driven serialization, the jar's entry point, or an
     *  override family that touches a class outside the jar or outside
     *  scope) -- never renamed, not even via a custom override. */
    public static final String REASON_HARD_PROTECTED = "hard-protected-not-renamed";
    /** Matches a compiler-generated inner-class naming pattern
     *  (this$N/val$.../access$N) -- left alone by default so a decompiler
     *  can still recognize it, but a custom override may force a rename. */
    public static final String REASON_SYNTHETIC_HEURISTIC = "synthetic-inner-class-pattern-not-renamed";
    /** A custom override targeted a hard-protected member; the override
     *  was ignored and the original name was kept. */
    public static final String REASON_CUSTOM_OVERRIDE_IGNORED = "custom-override-ignored-hard-protected";
    /** The custom-names file explicitly listed this member to keep. */
    public static final String REASON_CUSTOM_KEEP = "custom-override-keep";
    /** Declared by an annotation type: element names are referenced by
     *  string in every usage site's bytecode (not symbolic refs the
     *  remapper could rewrite), so renaming one breaks all of them.
     *  Never renamed, not even via a custom override. */
    public static final String REASON_ANNOTATION_ELEMENT = "annotation-element-not-renamed";
    /** Name is already at/above minKeepableLength and source-legal, so it
     *  looks genuine (e.g. Client, eventBus) rather than obfuscator junk
     *  (a, b, x1) -- left alone by default. */
    public static final String REASON_KEEP_LENGTH = "already-meaningful-name-not-renamed";

    public static MemberRenameEntry renamedField(String owner, String name, String desc, String newName, String reason) {
        return new MemberRenameEntry(KIND_FIELD, owner, name, desc, newName, false, reason);
    }

    public static MemberRenameEntry keptField(String owner, String name, String desc, String reason) {
        return new MemberRenameEntry(KIND_FIELD, owner, name, desc, null, true, reason);
    }

    public static MemberRenameEntry renamedMethod(String owner, String name, String desc, String newName, String reason) {
        return new MemberRenameEntry(KIND_METHOD, owner, name, desc, newName, false, reason);
    }

    public static MemberRenameEntry keptMethod(String owner, String name, String desc, String reason) {
        return new MemberRenameEntry(KIND_METHOD, owner, name, desc, null, true, reason);
    }
}
