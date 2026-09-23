package com.cleandecompile.stage0;

import com.cleandecompile.model.ClassInfo;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Header-only view of a class -- its supertype/interfaces plus every
 * declared member's name/descriptor/access, without paying for a full
 * method-body parse. Built once, up front, over EVERY class in the jar
 * (both scopes), so {@link MethodOverrideGroups} can tell "this ancestor is
 * a bundled library class we can see" apart from "this ancestor isn't in
 * the jar at all" (a genuine JDK/library type we have zero visibility
 * into) -- that distinction is exactly what decides whether an override
 * family is safe to rename.
 */
final class RawClassHeader {

    record Member(String name, String descriptor, int access) {}

    final String internalName;
    final String superName; // null only for java/lang/Object itself
    final List<String> interfaces;
    final boolean isInterface;
    final boolean inScope;
    final List<Member> methods = new ArrayList<>();
    final List<Member> fields = new ArrayList<>();

    private RawClassHeader(String internalName, String superName, List<String> interfaces,
                            boolean isInterface, boolean inScope) {
        this.internalName = internalName;
        this.superName = superName;
        this.interfaces = interfaces;
        this.isInterface = isInterface;
        this.inScope = inScope;
    }

    boolean declaresMethod(String name, String descriptor) {
        for (Member m : methods) {
            if (m.name().equals(name) && m.descriptor().equals(descriptor)) return true;
        }
        return false;
    }

    /** Parses every class's header once. A class that fails to parse is
     *  simply absent from the result -- everywhere this map is looked up,
     *  "absent" is treated the same as "external/unknown", which is the
     *  safe direction (see class javadoc). */
    static Map<String, RawClassHeader> indexAll(List<ClassInfo> allClasses) {
        Map<String, RawClassHeader> byName = new HashMap<>(allClasses.size() * 2);
        for (ClassInfo ci : allClasses) {
            RawClassHeader hdr = parse(ci);
            if (hdr != null) byName.put(hdr.internalName, hdr);
        }
        return byName;
    }

    private static RawClassHeader parse(ClassInfo ci) {
        try {
            ClassReader reader = new ClassReader(ci.bytes());
            RawClassHeader[] holder = new RawClassHeader[1];
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                   String superName, String[] interfaces) {
                    holder[0] = new RawClassHeader(name, superName,
                            interfaces == null ? List.of() : List.of(interfaces),
                            (access & Opcodes.ACC_INTERFACE) != 0, ci.inScope());
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                                String signature, Object value) {
                    holder[0].fields.add(new Member(name, descriptor, access));
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    holder[0].methods.add(new Member(name, descriptor, access));
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return holder[0];
        } catch (Exception e) {
            return null;
        }
    }
}
