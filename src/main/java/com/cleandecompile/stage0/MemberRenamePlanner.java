package com.cleandecompile.stage0;

import com.cleandecompile.model.ClassInfo;
import com.cleandecompile.model.MemberRenameEntry;

import java.util.ArrayList;
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

        return new Result(methodRenameMap, fieldRenameMap, manifest);
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

    private boolean isKeepable(String name, CustomNameOverrides overrides) {
        return name.length() >= overrides.minKeepableLength() && !IdentifierSanitizer.needsRename(name);
    }
}
