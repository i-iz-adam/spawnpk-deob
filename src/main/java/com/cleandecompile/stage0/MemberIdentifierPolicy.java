package com.cleandecompile.stage0;

import org.objectweb.asm.Opcodes;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which methods/fields Stage 0 must never rename versus which ones
 * it skips only by default, for decompiler-readability reasons.
 *
 * <p><b>Hard-protected</b> (see {@link #isHardProtectedMethod} /
 * {@link #isHardProtectedField}): renaming these would risk a silent
 * runtime behavior change rather than just a cosmetic difference --
 * {@code native} methods (JNI looks them up by name), the Serializable
 * magic methods and {@code serialVersionUID} (the serialization runtime
 * finds these reflectively by exact name; renaming them doesn't break
 * compilation, it silently changes what gets serialized), and
 * {@code public static void main(String[])} (the jar's/tooling's entry
 * point). {@link MemberRenamePlanner} never renames these, even if a
 * custom override asks for it -- see
 * {@link com.cleandecompile.model.MemberRenameEntry#REASON_CUSTOM_OVERRIDE_IGNORED}.
 *
 * <p><b>Soft-protected</b> ({@link #isSyntheticInnerClassPattern}): purely
 * a decompiler-readability nicety -- compiler-generated inner-class
 * plumbing ({@code this$0}, {@code val$captured}, {@code access$0}) that
 * some decompilers pattern-match on by name to reconstruct idiomatic
 * {@code Outer.this} syntax. Perfectly safe to rename at the JVM level, so
 * a custom override is free to do so.
 */
final class MemberIdentifierPolicy {
    private MemberIdentifierPolicy() {}

    static final String MAIN_METHOD_NAME = "main";
    static final String MAIN_METHOD_DESC = "([Ljava/lang/String;)V";

    private static final Pattern SYNTHETIC_OUTER_REF = Pattern.compile("^this\\$\\d+$");
    private static final Pattern SYNTHETIC_CAPTURED_LOCAL = Pattern.compile("^val\\$.+$");
    private static final Pattern SYNTHETIC_ACCESSOR = Pattern.compile("^access\\$\\d+$");

    // Methods the serialization runtime locates via reflection by exact
    // name+descriptor, never through a normal interface/override contract
    // our hierarchy walk would otherwise catch.
    private static final Set<String> SERIALIZABLE_MAGIC_METHODS = Set.of(
            "writeObject(Ljava/io/ObjectOutputStream;)V",
            "readObject(Ljava/io/ObjectInputStream;)V",
            "readObjectNoData()V",
            "writeReplace()Ljava/lang/Object;",
            "readResolve()Ljava/lang/Object;"
    );

    static boolean isHardProtectedMethod(String name, String descriptor, int access) {
        if ((access & Opcodes.ACC_NATIVE) != 0) {
            return true;
        }
        if (SERIALIZABLE_MAGIC_METHODS.contains(name + descriptor)) {
            return true;
        }
        return (access & Opcodes.ACC_STATIC) != 0
                && name.equals(MAIN_METHOD_NAME)
                && descriptor.equals(MAIN_METHOD_DESC);
    }

    static boolean isHardProtectedField(String name) {
        return name.equals("serialVersionUID");
    }

    static boolean isSyntheticInnerClassPattern(String name) {
        return SYNTHETIC_OUTER_REF.matcher(name).matches()
                || SYNTHETIC_CAPTURED_LOCAL.matcher(name).matches()
                || SYNTHETIC_ACCESSOR.matcher(name).matches();
    }
}
