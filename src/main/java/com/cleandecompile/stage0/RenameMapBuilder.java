package com.cleandecompile.stage0;

import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.model.RenameEntry;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds the global old-internal-name -&gt; new-internal-name map that
 * {@link BytecodeNormalizer} feeds straight into {@link QualifiedRemapper}.
 *
 * <p>In-scope classes get rename entries via {@link PackageTree} (which
 * resolves names as a tree rather than patching classes independently).
 * Out-of-scope classes keep their names -- EXCEPT the handful that share
 * their path with a package ({@link LibraryClashRepair}), which javac
 * cannot express in source at all.
 *
 * <p>{@link CustomNameOverrides} only ever applies to in-scope classes --
 * consulting it for an out-of-scope class would ask {@link PackageTree} to
 * move a bundled library class, which breaks the "bundled libraries stay
 * byte-identical" guarantee. Out-of-scope classes always get the identity
 * (unchanged) desired path.
 */
public final class RenameMapBuilder {

    public record Result(Map<String, String> renameMap, List<RenameEntry> manifestEntries) {}

    public Result build(List<ClassInfo> allClasses, CustomNameOverrides overrides) {
        PackageTree tree = new PackageTree();
        for (ClassInfo ci : allClasses) {
            CustomNameOverrides.DesiredPath desired = ci.inScope()
                    ? overrides.desiredPathFor(ci.internalName())
                    : CustomNameOverrides.DesiredPath.unchanged(ci.internalName());
            tree.insert(ci, desired);
        }
        // Seed both pools with every custom target segment so an
        // auto-generated name (Class7, pkg3) can never collide with a
        // manually-chosen one, however unlikely that already is given the
        // distinct default prefixes.
        Set<String> claimed = new HashSet<>(overrides.allCustomSegmentTexts());
        UniqueNamePool packagePool = new UniqueNamePool(overrides.packageNamePrefix(), new HashSet<>(claimed));
        UniqueNamePool classPool = new UniqueNamePool(overrides.classNamePrefix(), new HashSet<>(claimed));
        List<PackageTree.Placement> placements =
                tree.resolve(packagePool, classPool, overrides.minKeepableLength());

        Map<String, String> renameMap = new LinkedHashMap<>();
        List<RenameEntry> manifest = new ArrayList<>();
        for (var p : placements) {
            renameMap.put(p.originalInternalName(), p.newInternalName());
            manifest.add(new RenameEntry(p.originalInternalName(), p.newInternalName(), p.reason()));
        }
        for (var e : LibraryClashRepair.repair(allClasses).entrySet()) {
            renameMap.put(e.getKey(), e.getValue());
            manifest.add(new RenameEntry(e.getKey(), e.getValue(), RenameEntry.REASON_LIBRARY_CLASH));
        }
        return new Result(renameMap, manifest);
    }
}
