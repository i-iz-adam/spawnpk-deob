package com.cleandecompile.stage0;

import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.model.MemberRenameEntry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 0's method/field renaming pass. Where {@link RenameMapBuilder} (via
 * {@link PackageTree}) resolves class/package names, this gives every
 * in-scope FIELD and every virtual-dispatch-safe METHOD FAMILY a name too.
 *
 * <p>Short names always get replaced, long legal names stay put: a member
 * whose simple name is shorter than {@code minKeepableLength} (default 3,
 * from {@link CustomNameOverrides#minKeepableLength}) is ALWAYS renamed to a
 * fresh globally-unique name ({@code field0}... / {@code method0}...), even
 * if already a legal identifier -- obfuscators favor 1-2 character junk
 * ({@code a}, {@code b}, {@code p}). A member at/above that length is kept
 * verbatim when source-legal, so genuine names ({@code eventBus},
 * {@code sendChatMessage}) survive. Explicit {@link CustomNameOverrides}
 * entries always win over this heuristic in either direction.
 *
 * <p>Fields are simple: each one belongs to exactly one declaring class,
 * so it's renamed (or kept, or given a custom name) independently. Methods
 * are the hard part -- a method can't be renamed in isolation from
 * whatever it overrides/implements without breaking dynamic dispatch, so
 * that safety analysis is delegated to {@link MethodOverrideGroups}; this
 * class just turns its answer into a rename map plus a manifest.
 */
public final class MemberRenamePlanner {

    public record Result(
            Map<String, String> methodRenameMap, // MemberKeyParts.methodKey(...) -> new simple name
            Map<String, String> fieldRenameMap,  // MemberKeyParts.fieldKey(...) -> new simple name
            List<MemberRenameEntry> manifestEntries
    ) {}

    public Result plan(List<ClassInfo> allClasses, CustomNameOverrides overrides) {
        Map<String, RawClassHeader> byName = RawClassHeader.indexAll(allClasses);

        Map<String, String> fieldRenameMap = new LinkedHashMap<>();
        Map<String, String> methodRenameMap = new LinkedHashMap<>();
        List<MemberRenameEntry> manifest = new ArrayList<>();

        planFields(allClasses, byName, overrides, fieldRenameMap, manifest);
        planMethods(byName, overrides, methodRenameMap, manifest);

        // Bytecode names the STATIC receiver type in member refs, which is
        // often a subclass that merely inherits the member (Client.hQ where
        // hQ is declared in superclass C). The remapper looks up the ref's
        // owner verbatim, so without these inherited-owner aliases the
        // declaration renames while some usages don't -- "cannot find
        // symbol" in every file that touches them. Aliases are map-only
        // (no manifest entries: nothing new was renamed).
        propagateToSubclasses(fieldRenameMap, byName, true);
        propagateToSubclasses(methodRenameMap, byName, false);

        return new Result(methodRenameMap, fieldRenameMap, manifest);
    }

    private void propagateToSubclasses(Map<String, String> renameMap, Map<String, RawClassHeader> byName,
                                       boolean fields) {
        Map<String, List<String>> children = new HashMap<>();
        for (RawClassHeader hdr : byName.values()) {
            if (hdr.superName != null) {
                children.computeIfAbsent(hdr.superName, k -> new ArrayList<>()).add(hdr.internalName);
            }
            for (String itf : hdr.interfaces) {
                children.computeIfAbsent(itf, k -> new ArrayList<>()).add(hdr.internalName);
            }
        }

        List<Map.Entry<String, String>> entries = new ArrayList<>(renameMap.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (var entry : entries) {
            MemberKeyParts.Parts parts = fields
                    ? MemberKeyParts.parseFieldKey(entry.getKey())
                    : MemberKeyParts.parseMethodKey(entry.getKey());
            Deque<String> queue = new ArrayDeque<>(children.getOrDefault(parts.owner(), List.of()));
            Set<String> visited = new HashSet<>();
            while (!queue.isEmpty()) {
                String sub = queue.removeFirst();
                if (!visited.add(sub)) continue;
                RawClassHeader subHdr = byName.get(sub);
                if (subHdr == null) continue;
                boolean hides = fields
                        ? subHdr.declaresField(parts.name(), parts.descriptor())
                        : subHdr.declaresMethod(parts.name(), parts.descriptor());
                if (!hides) {
                    String subKey = fields
                            ? MemberKeyParts.fieldKey(sub, parts.name(), parts.descriptor())
                            : MemberKeyParts.methodKey(sub, parts.name(), parts.descriptor());
                    renameMap.putIfAbsent(subKey, entry.getValue());
                }
                queue.addAll(children.getOrDefault(sub, List.of()));
            }
        }
    }

    private void planFields(List<ClassInfo> allClasses, Map<String, RawClassHeader> byName,
                             CustomNameOverrides overrides, Map<String, String> fieldRenameMap,
                             List<MemberRenameEntry> manifest) {
        Set<String> reserved = new HashSet<>(overrides.allCustomFieldNewNames());
        UniqueNamePool pool = new UniqueNamePool(overrides.fieldNamePrefix(), reserved);

        for (ClassInfo ci : allClasses) {
            if (!ci.inScope()) continue;
            RawClassHeader hdr = byName.get(ci.internalName());
            if (hdr == null) continue; // unparsable -- BytecodeNormalizer flags this class separately

            for (RawClassHeader.Member f : hdr.fields) {
                CustomNameOverrides.FieldOverride custom =
                        overrides.fieldOverride(ci.internalName(), f.name(), f.descriptor());
                boolean hardProtected = MemberIdentifierPolicy.isHardProtectedField(f.name());

                if (custom != null && custom.keepOriginal()) {
                    manifest.add(MemberRenameEntry.keptField(ci.internalName(), f.name(), f.descriptor(),
                            MemberRenameEntry.REASON_CUSTOM_KEEP));
                    continue;
                }
                if (hardProtected) {
                    manifest.add(MemberRenameEntry.keptField(ci.internalName(), f.name(), f.descriptor(),
                            custom != null ? MemberRenameEntry.REASON_CUSTOM_OVERRIDE_IGNORED
                                    : MemberRenameEntry.REASON_HARD_PROTECTED));
                    continue;
                }
                if (custom == null && MemberIdentifierPolicy.isSyntheticInnerClassPattern(f.name())) {
                    manifest.add(MemberRenameEntry.keptField(ci.internalName(), f.name(), f.descriptor(),
                            MemberRenameEntry.REASON_SYNTHETIC_HEURISTIC));
                    continue;
                }
                if (custom == null && isKeepable(f.name(), overrides)) {
                    manifest.add(MemberRenameEntry.keptField(ci.internalName(), f.name(), f.descriptor(),
                            MemberRenameEntry.REASON_KEEP_LENGTH));
                    continue;
                }

                String newName = custom != null ? custom.newName() : pool.next();
                fieldRenameMap.put(MemberKeyParts.fieldKey(ci.internalName(), f.name(), f.descriptor()), newName);
                manifest.add(MemberRenameEntry.renamedField(ci.internalName(), f.name(), f.descriptor(), newName,
                        custom != null ? MemberRenameEntry.REASON_CUSTOM_OVERRIDE : MemberRenameEntry.REASON_UNIQUE_NAME));
            }
        }
    }

    private void planMethods(Map<String, RawClassHeader> byName, CustomNameOverrides overrides,
                              Map<String, String> methodRenameMap, List<MemberRenameEntry> manifest) {
        MethodOverrideGroups.Result groups = MethodOverrideGroups.build(byName);

        Set<String> reserved = new HashSet<>(overrides.allCustomMethodNewNames());
        UniqueNamePool pool = new UniqueNamePool(overrides.methodNamePrefix(), reserved);

        for (var groupEntry : groups.groups().entrySet()) {
            Set<String> members = groupEntry.getValue();
            boolean hardPoisoned = groups.poisonedRoots().getOrDefault(groupEntry.getKey(), false);
            boolean externalOnly = !hardPoisoned
                    && groups.externalRoots().getOrDefault(groupEntry.getKey(), false);

            String customName = null;
            String customSource = null;
            boolean explicitKeep = false;
            for (String memberKey : members) {
                MemberKeyParts.Parts parts = MemberKeyParts.parseMethodKey(memberKey);
                CustomNameOverrides.MethodOverride mo =
                        overrides.methodOverride(parts.owner(), parts.name(), parts.descriptor());
                if (mo == null) continue;
                if (mo.keepOriginal()) {
                    explicitKeep = true;
                    continue;
                }
                if (customName != null && !customName.equals(mo.newName())) {
                    throw new IllegalStateException("custom-names: conflicting names for methods that override "
                            + "each other and must share one name -- \"" + customSource + "\" wants \"" + customName
                            + "\" but \"" + memberKey + "\" wants \"" + mo.newName() + "\".");
                }
                customName = mo.newName();
                customSource = memberKey;
            }
            if (explicitKeep && customName != null) {
                throw new IllegalStateException("custom-names: \"" + customSource + "\" asks to rename this "
                        + "override family to \"" + customName + "\", but another member of the same family is "
                        + "listed in keepMethods -- these methods override each other and can't do both.");
            }

            if (hardPoisoned) {
                String reason = (customName != null || explicitKeep)
                        ? MemberRenameEntry.REASON_CUSTOM_OVERRIDE_IGNORED
                        : MemberRenameEntry.REASON_HARD_PROTECTED;
                addKeptMethods(members, reason, manifest);
                continue;
            }
            if (isAnnotationFamily(members, byName)) {
                // Annotation elements are referenced by bare name string in
                // every usage site's bytecode -- the remapper only rewrites
                // symbolic refs, so a rename would silently detach all
                // usages. Not even a custom override can fix that up.
                String reason = (customName != null || explicitKeep)
                        ? MemberRenameEntry.REASON_CUSTOM_OVERRIDE_IGNORED
                        : MemberRenameEntry.REASON_ANNOTATION_ELEMENT;
                addKeptMethods(members, reason, manifest);
                continue;
            }
            if (explicitKeep) {
                // Explicit keep wins over heuristic external-touch poison
                // (the caller knows the name is safe); hard poison above
                // still vetoes.
                addKeptMethods(members, MemberRenameEntry.REASON_CUSTOM_KEEP, manifest);
                continue;
            }
            if (customName == null && externalOnly) {
                // No explicit instruction and only a heuristic reason to
                // keep: stay conservative, leave the original name.
                addKeptMethods(members, MemberRenameEntry.REASON_HARD_PROTECTED, manifest);
                continue;
            }
            if (customName == null) {
                // Every member of a group shares the same original simple
                // name by construction -- see MethodOverrideGroups javadoc.
                String repName = MemberKeyParts.parseMethodKey(members.iterator().next()).name();
                if (MemberIdentifierPolicy.isSyntheticInnerClassPattern(repName)) {
                    addKeptMethods(members, MemberRenameEntry.REASON_SYNTHETIC_HEURISTIC, manifest);
                    continue;
                }
                if (isKeepable(repName, overrides)) {
                    addKeptMethods(members, MemberRenameEntry.REASON_KEEP_LENGTH, manifest);
                    continue;
                }
            }

            String finalName = customName != null ? customName : pool.next();
            String reason = customName != null
                    ? MemberRenameEntry.REASON_CUSTOM_OVERRIDE
                    : (members.size() > 1 ? MemberRenameEntry.REASON_OVERRIDE_GROUP : MemberRenameEntry.REASON_UNIQUE_NAME);
            for (String memberKey : members) {
                methodRenameMap.put(memberKey, finalName);
                MemberKeyParts.Parts parts = MemberKeyParts.parseMethodKey(memberKey);
                manifest.add(MemberRenameEntry.renamedMethod(parts.owner(), parts.name(), parts.descriptor(), finalName, reason));
            }
        }
    }

    private void addKeptMethods(Set<String> members, String reason, List<MemberRenameEntry> manifest) {
        for (String memberKey : members) {
            MemberKeyParts.Parts parts = MemberKeyParts.parseMethodKey(memberKey);
            manifest.add(MemberRenameEntry.keptMethod(parts.owner(), parts.name(), parts.descriptor(), reason));
        }
    }

    private boolean isAnnotationFamily(Set<String> members, Map<String, RawClassHeader> byName) {
        for (String memberKey : members) {
            RawClassHeader owner = byName.get(MemberKeyParts.parseMethodKey(memberKey).owner());
            if (owner != null && owner.isAnnotation) return true;
        }
        return false;
    }

    private boolean isKeepable(String name, CustomNameOverrides overrides) {
        return name.length() >= overrides.minKeepableLength() && !IdentifierSanitizer.needsRename(name);
    }
}
