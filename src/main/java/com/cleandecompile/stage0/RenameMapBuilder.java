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
 * <p>Only in-scope classes end up with rename entries -- that's what keeps
 * bundled libraries byte-identical. The actual collision/legality
 * resolution is delegated to {@link PackageTree}, which resolves names as
 * a tree (sibling vs. sibling, level by level) rather than patching
 * classes independently -- see its class-level javadoc for why that
 * matters for the classic package/class-name collision.
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
        return new Result(renameMap, manifest);
    }
}
