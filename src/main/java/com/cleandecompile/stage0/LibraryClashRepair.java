package com.cleandecompile.stage0;

import com.cleandecompile.model.ClassInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Repairs library type/package name clashes that are legal bytecode but
 * uncompilable source. Shaded obfuscated libraries do this routinely: trove
 * ships an interface {@code gnu/trove/f} AND a package {@code gnu/trove/f/}
 * (with classes like {@code gnu/trove/f/b}) in the same jar. The JVM keeps
 * both straight; {@code javac} does not -- a type always shadows a package
 * of the same name, so {@code import gnu.trove.f.b.cc} dies with "cannot
 * find symbol: class b, location: interface gnu.trove.f" and every usage
 * cascades after it.
 *
 * <p>Only out-of-scope (library) types are ever renamed here, and only the
 * TYPE side moves: {@code gnu/trove/f} becomes {@code gnu/trove/f_} in the
 * same package (deterministic, suffix disambiguated). The package side --
 * potentially dozens of classes -- stays put. In-scope classes need no
 * repair: short ones are already renamed to {@code ClassN} and kept-long
 * ones can never coincide with a library package (library code lives
 * outside every {@code --own-package} root by construction).
 *
 * <p>The returned map merges into the same global rename map {@link
 * BytecodeNormalizer} feeds to ASM, so the library class bytes themselves
 * AND every reference (in-scope sources, vendored bytecode, decompiled
 * library sources under {@code --decompile-libraries}) move together.
 * Dependency fingerprinting hashes the renamed bytes -- acceptable, that
 * stage is currently a stub anyway.
 */
final class LibraryClashRepair {

    private LibraryClashRepair() {
    }

    /** @return old-internal-name to new-internal-name, sorted for stable manifests. */
    static Map<String, String> repair(List<ClassInfo> allClasses) {
        Set<String> allNames = allClasses.stream()
                .map(ClassInfo::internalName)
                .collect(Collectors.toSet());

        List<String> clashers = allClasses.stream()
                .filter(ci -> !ci.inScope())
                .map(ClassInfo::internalName)
                .filter(name -> allNames.stream()
                        .anyMatch(other -> !other.equals(name) && other.startsWith(name + "/")))
                .sorted()
                .toList();

        Map<String, String> renames = new LinkedHashMap<>();
        Set<String> taken = new TreeSet<>(allNames);
        for (String original : clashers) {
            int slash = original.lastIndexOf('/');
            String parent = slash < 0 ? "" : original.substring(0, slash);
            String simple = slash < 0 ? original : original.substring(slash + 1);
            String candidate = simple + "_";
            int disambiguator = 2;
            while (taken.contains(parent.isEmpty() ? candidate : parent + "/" + candidate)) {
                candidate = simple + "_" + disambiguator++;
            }
            String full = parent.isEmpty() ? candidate : parent + "/" + candidate;
            renames.put(original, full);
            taken.add(full);
        }
        return renames;
    }
}
