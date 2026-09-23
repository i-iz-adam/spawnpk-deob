package com.cleandecompile.stage0;

import org.objectweb.asm.commons.Remapper;

import java.util.Map;

/**
 * Custom {@link Remapper} used in place of ASM's {@code SimpleRemapper} so
 * Stage 0 can remap classes, methods AND fields consistently in one
 * {@code ClassRemapper} pass.
 *
 * <p>{@code SimpleRemapper}'s field-rename key is {@code owner + '.' + name}
 * -- no descriptor -- which can't distinguish two fields that share a name
 * but differ only in type within the same class. That's legal at the
 * bytecode level (real javac never produces it, but a hostile obfuscator
 * can, precisely to confuse tools that assume {@code SimpleRemapper}'s
 * shape). This remapper's field map is keyed by owner+name+descriptor
 * instead, so that shape is handled correctly. See
 * {@link MemberKeyParts#fieldKey}.
 *
 * <p>Constructors and static initializers are never looked up in the
 * method map (their names are JVM-special, not real Java identifiers, and
 * {@link MemberRenamePlanner} never puts entries for them there anyway) --
 * this is just a defensive belt-and-braces check.
 */
final class QualifiedRemapper extends Remapper {

    private final Map<String, String> classMap;
    private final Map<String, String> methodMap; // key: MemberKeyParts.methodKey(owner, name, descriptor)
    private final Map<String, String> fieldMap;  // key: MemberKeyParts.fieldKey(owner, name, descriptor)

    QualifiedRemapper(Map<String, String> classMap, Map<String, String> methodMap, Map<String, String> fieldMap) {
        this.classMap = classMap;
        this.methodMap = methodMap;
        this.fieldMap = fieldMap;
    }

    @Override
    public String map(String internalName) {
        return classMap.getOrDefault(internalName, internalName);
    }

    @Override
    public String mapMethodName(String owner, String name, String descriptor) {
        if (name.equals("<init>") || name.equals("<clinit>")) return name;
        return methodMap.getOrDefault(MemberKeyParts.methodKey(owner, name, descriptor), name);
    }

    @Override
    public String mapFieldName(String owner, String name, String descriptor) {
        return fieldMap.getOrDefault(MemberKeyParts.fieldKey(owner, name, descriptor), name);
    }
}
