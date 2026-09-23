package com.cleandecompile.stage0;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holds every name Stage 0 already knows, supplied by the caller instead of
 * derived automatically -- "this obfuscated package is actually the
 * plugins package", "this specific class is PluginManager", "this field is
 * pluginName". Loaded once from a JSON file and consulted by both
 * {@link RenameMapBuilder} (packages/classes) and {@link MemberRenamePlanner}
 * (fields/methods).
 *
 * <h2>File format</h2>
 * <pre>{@code
 * {
 *   "packages": { "rs.a": "com.example.plugins" },
 *   "classes":  { "rs.a.b": "com.example.plugins.PluginManager" },
 *   "fields":   {
 *     "rs.a.b#c:Ljava/lang/String;": "pluginName",
 *     "rs.a.c#d": "loaded"
 *   },
 *   "methods":  { "rs.a.b#e()V": "loadPlugin" },
 *   "keepFields":  ["rs.a.x#weirdField:I"],
 *   "keepMethods": ["rs.a.x#weirdMethod()V"],
 *   "fieldPrefix":  "field",
 *   "methodPrefix": "method",
 *   "classPrefix":  "Class",
 *   "packagePrefix": "pkg",
 *   "minKeepableLength": 3
 * }
 * }</pre>
 *
 * <p>A package/class segment shorter than {@code minKeepableLength}
 * (default 3) is ALWAYS replaced with a fresh, globally unique placeholder
 * name ({@code Class0}, {@code Class1}, ... / {@code pkg0}, {@code pkg1},
 * ...), even if it's already a legal Java identifier -- obfuscators
 * overwhelmingly favor 1-2 character names ({@code a}, {@code b}, ...),
 * while genuine names (like {@code Client}, {@code cache}, {@code gui})
 * are almost never that short. A segment at or above that length is left
 * as its original text (sanitized only if actually illegal), so a jar that
 * mixes obfuscated junk packages with real ones -- vendored libraries,
 * parts the obfuscator didn't touch -- doesn't lose the parts that were
 * already readable. Your {@code --own-package} root itself is pinned
 * regardless of its length, since you already know what that is; this
 * file is how you carve out anything else you've identified, including
 * something short you actually want kept (add it to {@code packages} or
 * {@code classes} mapped to itself).
 *
 * <p>{@code packages}/{@code classes} use dotted binary names, same
 * convention as {@code --own-package}. {@code fields}/{@code methods} key
 * on {@code OwnerDottedName#member}, with the JVM descriptor appended
 * (required for methods, since overloads share a name; optional for
 * fields, required only if that owner genuinely has more than one field
 * with that simple name -- a legal but vanishingly rare bytecode shape).
 * A method's descriptor always starts with {@code (}; a field's follows a
 * {@code :}.
 *
 * <p>A {@code packages} entry matches a class by longest-prefix match on
 * its package path; a {@code classes} entry pins one class's ENTIRE final
 * name (package and simple name both) and takes priority over any
 * {@code packages} match. Custom names are validated for Java-identifier
 * legality at load time -- this file is asking for a specific literal
 * name, so an illegal one is a config error, not something to silently
 * sanitize.
 */
final class CustomNameOverrides {

    /** A class's desired starting path for {@link PackageTree}: its
     *  segments, and how many of the leading segments are PINNED (came
     *  from an explicit override and must not be auto-disambiguated away
     *  except to resolve a genuine collision). */
    record DesiredPath(List<String> segments, int pinnedPrefixLength) {
        static DesiredPath unchanged(String originalInternalName) {
            return new DesiredPath(List.of(originalInternalName.split("/")), 0);
        }
    }

    record FieldOverride(String newName, boolean keepOriginal) {
        static FieldOverride rename(String newName) { return new FieldOverride(newName, false); }
        static FieldOverride keep() { return new FieldOverride(null, true); }
    }

    record MethodOverride(String newName, boolean keepOriginal) {
        static MethodOverride rename(String newName) { return new MethodOverride(newName, false); }
        static MethodOverride keep() { return new MethodOverride(null, true); }
    }

    private record MemberRef(String owner, String name, String descriptor /* nullable for fields */) {}

    // Jackson target for the raw file.
    private record OverridesFile(
            Map<String, String> packages,
            Map<String, String> classes,
            Map<String, String> fields,
            Map<String, String> methods,
            List<String> keepFields,
            List<String> keepMethods,
            String fieldPrefix,
            String methodPrefix,
            String classPrefix,
            String packagePrefix,
            Integer minKeepableLength
    ) {}

    private final Map<String, String> packagePrefixes; // slash form -> slash form
    private final Map<String, String> classRenames;     // slash form original -> slash form full path
    private final Map<String, String> fieldExact;       // "owner.name:desc" -> newName
    private final Map<String, String> fieldByNameOnly;  // "owner.name" -> newName
    private final Set<String> keepFieldsExact;
    private final Set<String> keepFieldsByNameOnly;
    private final Map<String, String> methodExact;      // "owner.name(desc)" -> newName
    private final Set<String> keepMethodsExact;
    private final String fieldPrefix;
    private final String methodPrefix;
    private final String classPrefix;
    private final String packagePrefix;
    private final int minKeepableLength;

    private CustomNameOverrides(Map<String, String> packagePrefixes, Map<String, String> classRenames,
                                Map<String, String> fieldExact, Map<String, String> fieldByNameOnly,
                                Set<String> keepFieldsExact, Set<String> keepFieldsByNameOnly,
                                Map<String, String> methodExact, Set<String> keepMethodsExact,
                                String fieldPrefix, String methodPrefix, String classPrefix, String packagePrefix,
                                int minKeepableLength) {
        this.packagePrefixes = packagePrefixes;
        this.classRenames = classRenames;
        this.fieldExact = fieldExact;
        this.fieldByNameOnly = fieldByNameOnly;
        this.keepFieldsExact = keepFieldsExact;
        this.keepFieldsByNameOnly = keepFieldsByNameOnly;
        this.methodExact = methodExact;
        this.keepMethodsExact = keepMethodsExact;
        this.fieldPrefix = fieldPrefix;
        this.methodPrefix = methodPrefix;
        this.classPrefix = classPrefix;
        this.packagePrefix = packagePrefix;
        this.minKeepableLength = minKeepableLength;
    }

    static CustomNameOverrides none() {
        return new CustomNameOverrides(Map.of(), Map.of(), Map.of(), Map.of(), Set.of(), Set.of(),
                Map.of(), Set.of(), "field", "method", "Class", "pkg", 3);
    }

    /** Returns a copy with an identity pin ({@code prefix -> prefix}) added
     *  for every prefix in {@code impliedPrefixes} that isn't already
     *  covered by an explicit {@code packages}/{@code classes} entry --
     *  used so the exact {@code --own-package} root(s) the caller already
     *  typed on the command line stay recognizable by default, while
     *  everything auto-derived BENEATH that root still gets the new
     *  global-unique treatment. An explicit custom mapping for the same
     *  (or a shorter) prefix always wins over this implicit one, since
     *  {@link #desiredPathFor} picks the LONGEST matching prefix and an
     *  explicit entry is never shorter than what it's meant to override. */
    CustomNameOverrides withImpliedPackagePins(List<String> impliedPrefixesSlashForm) {
        Map<String, String> merged = new HashMap<>(packagePrefixes);
        for (String prefix : impliedPrefixesSlashForm) {
            merged.putIfAbsent(prefix, prefix);
        }
        return new CustomNameOverrides(merged, classRenames, fieldExact, fieldByNameOnly, keepFieldsExact,
                keepFieldsByNameOnly, methodExact, keepMethodsExact, fieldPrefix, methodPrefix, classPrefix,
                packagePrefix, minKeepableLength);
    }

    static CustomNameOverrides loadFromJson(Path path) throws IOException {
        OverridesFile raw = new ObjectMapper().readValue(path.toFile(), OverridesFile.class);

        Map<String, String> packagePrefixes = new HashMap<>();
        if (raw.packages() != null) {
            for (var e : raw.packages().entrySet()) {
                String from = internalForm(e.getKey());
                String to = internalForm(e.getValue());
                validateSegments(to, "packages[\"" + e.getKey() + "\"]");
                packagePrefixes.put(from, to);
            }
        }

        Map<String, String> classRenames = new HashMap<>();
        if (raw.classes() != null) {
            Map<String, String> seenTargets = new HashMap<>();
            for (var e : raw.classes().entrySet()) {
                String from = internalForm(e.getKey());
                String to = internalForm(e.getValue());
                validateSegments(to, "classes[\"" + e.getKey() + "\"]");
                String priorSource = seenTargets.put(to, from);
                if (priorSource != null) {
                    throw new IllegalArgumentException("custom-names: \"" + priorSource + "\" and \"" + from
                            + "\" both target the same class name \"" + e.getValue() + "\" -- pick distinct names.");
                }
                classRenames.put(from, to);
            }
        }

        Map<String, String> fieldExact = new HashMap<>();
        Map<String, String> fieldByNameOnly = new HashMap<>();
        if (raw.fields() != null) {
            for (var e : raw.fields().entrySet()) {
                MemberRef ref = parseFieldRef(e.getKey());
                validateNewMemberName(e.getValue(), "fields[\"" + e.getKey() + "\"]");
                if (ref.descriptor() != null) {
                    fieldExact.put(ref.owner() + '.' + ref.name() + ':' + ref.descriptor(), e.getValue());
                } else {
                    fieldByNameOnly.put(ref.owner() + '.' + ref.name(), e.getValue());
                }
            }
        }

        Set<String> keepFieldsExact = new HashSet<>();
        Set<String> keepFieldsByNameOnly = new HashSet<>();
        if (raw.keepFields() != null) {
            for (String key : raw.keepFields()) {
                MemberRef ref = parseFieldRef(key);
                if (ref.descriptor() != null) {
                    keepFieldsExact.add(ref.owner() + '.' + ref.name() + ':' + ref.descriptor());
                } else {
                    keepFieldsByNameOnly.add(ref.owner() + '.' + ref.name());
                }
            }
        }

        Map<String, String> methodExact = new HashMap<>();
        if (raw.methods() != null) {
            for (var e : raw.methods().entrySet()) {
                MemberRef ref = parseMethodRef(e.getKey());
                validateNewMemberName(e.getValue(), "methods[\"" + e.getKey() + "\"]");
                methodExact.put(ref.owner() + '.' + ref.name() + ref.descriptor(), e.getValue());
            }
        }

        Set<String> keepMethodsExact = new HashSet<>();
        if (raw.keepMethods() != null) {
            for (String key : raw.keepMethods()) {
                MemberRef ref = parseMethodRef(key);
                keepMethodsExact.add(ref.owner() + '.' + ref.name() + ref.descriptor());
            }
        }

        String fieldPrefix = raw.fieldPrefix() != null ? raw.fieldPrefix() : "field";
        String methodPrefix = raw.methodPrefix() != null ? raw.methodPrefix() : "method";
        String classPrefix = raw.classPrefix() != null ? raw.classPrefix() : "Class";
        String packagePrefixName = raw.packagePrefix() != null ? raw.packagePrefix() : "pkg";
        int minKeepableLength = raw.minKeepableLength() != null ? raw.minKeepableLength() : 3;

        return new CustomNameOverrides(packagePrefixes, classRenames, fieldExact, fieldByNameOnly,
                keepFieldsExact, keepFieldsByNameOnly, methodExact, keepMethodsExact,
                fieldPrefix, methodPrefix, classPrefix, packagePrefixName, minKeepableLength);
    }

    String fieldNamePrefix() { return fieldPrefix; }

    String methodNamePrefix() { return methodPrefix; }

    String classNamePrefix() { return classPrefix; }

    String packageNamePrefix() { return packagePrefix; }

    /** A package/class segment shorter than this (default 3) is always
     *  replaced with a fresh auto-generated name, even if it's already a
     *  legal Java identifier -- obfuscators overwhelmingly favor 1-2
     *  character names, while genuine names are almost never that short.
     *  A segment at or above this length is left as its original text
     *  (sanitized only if actually illegal) unless a custom override or
     *  {@code --own-package} pin says otherwise. Set to {@code 0} to
     *  disable the heuristic and keep every already-legal name; set it
     *  higher to be more aggressive about replacing short-but-legal names
     *  like {@code "ui"}. */
    int minKeepableLength() { return minKeepableLength; }

    Set<String> allCustomFieldNewNames() {
        Set<String> names = new HashSet<>(fieldExact.values());
        names.addAll(fieldByNameOnly.values());
        return names;
    }

    Set<String> allCustomMethodNewNames() {
        return Set.copyOf(methodExact.values());
    }

    /** Every individual path segment used anywhere in a custom
     *  {@code packages}/{@code classes} target -- used to seed the
     *  auto-naming pools for packages/classes so an auto-generated name
     *  (e.g. {@code "Class7"}) can never collide with one you spelled out
     *  explicitly, however unlikely that already is given the distinct
     *  default prefixes. */
    Set<String> allCustomSegmentTexts() {
        Set<String> segments = new HashSet<>();
        for (String value : packagePrefixes.values()) segments.addAll(List.of(value.split("/")));
        for (String value : classRenames.values()) segments.addAll(List.of(value.split("/")));
        return segments;
    }

    /** @param originalInternalName ORIGINAL (pre-rename) slash-form internal name. */
    DesiredPath desiredPathFor(String originalInternalName) {
        String full = classRenames.get(originalInternalName);
        if (full != null) {
            List<String> segments = List.of(full.split("/"));
            return new DesiredPath(segments, segments.size());
        }

        int lastSlash = originalInternalName.lastIndexOf('/');
        String pkg = lastSlash < 0 ? "" : originalInternalName.substring(0, lastSlash);
        String simple = lastSlash < 0 ? originalInternalName : originalInternalName.substring(lastSlash + 1);

        String bestPrefix = null;
        for (String candidate : packagePrefixes.keySet()) {
            boolean matches = pkg.equals(candidate) || pkg.startsWith(candidate + "/");
            if (matches && (bestPrefix == null || candidate.length() > bestPrefix.length())) {
                bestPrefix = candidate;
            }
        }
        if (bestPrefix == null) {
            return DesiredPath.unchanged(originalInternalName);
        }

        String mappedPrefix = packagePrefixes.get(bestPrefix);
        String tail = pkg.equals(bestPrefix) ? "" : pkg.substring(bestPrefix.length() + 1);

        List<String> segments = new ArrayList<>(List.of(mappedPrefix.split("/")));
        int pinnedCount = segments.size();
        if (!tail.isEmpty()) segments.addAll(List.of(tail.split("/")));
        segments.add(simple);
        return new DesiredPath(segments, pinnedCount);
    }

    /** @param owner ORIGINAL (pre-rename) slash-form internal name. */
    FieldOverride fieldOverride(String owner, String name, String descriptor) {
        String exactKey = owner + '.' + name + ':' + descriptor;
        if (keepFieldsExact.contains(exactKey)) return FieldOverride.keep();
        String newName = fieldExact.get(exactKey);
        if (newName != null) return FieldOverride.rename(newName);

        String nameOnlyKey = owner + '.' + name;
        if (keepFieldsByNameOnly.contains(nameOnlyKey)) return FieldOverride.keep();
        newName = fieldByNameOnly.get(nameOnlyKey);
        return newName != null ? FieldOverride.rename(newName) : null;
    }

    /** @param owner ORIGINAL (pre-rename) slash-form internal name. */
    MethodOverride methodOverride(String owner, String name, String descriptor) {
        String key = owner + '.' + name + descriptor;
        if (keepMethodsExact.contains(key)) return MethodOverride.keep();
        String newName = methodExact.get(key);
        return newName != null ? MethodOverride.rename(newName) : null;
    }

    private static String internalForm(String name) {
        return name.replace('.', '/');
    }

    private static void validateSegments(String slashFormPath, String where) {
        for (String seg : slashFormPath.split("/")) {
            if (IdentifierSanitizer.needsRename(seg)) {
                throw new IllegalArgumentException("custom-names: " + where + " = \"" + slashFormPath.replace('/', '.')
                        + "\" contains an illegal or reserved segment \"" + seg + "\".");
            }
        }
    }

    private static void validateNewMemberName(String name, String where) {
        if (IdentifierSanitizer.needsRename(name)) {
            throw new IllegalArgumentException("custom-names: " + where + " -> \"" + name
                    + "\" is not a legal Java identifier.");
        }
    }

    private static MemberRef parseFieldRef(String ref) {
        int hash = ref.indexOf('#');
        if (hash < 0) {
            throw new IllegalArgumentException(
                    "custom-names: field key must be \"Owner#name\" or \"Owner#name:descriptor\": " + ref);
        }
        String owner = internalForm(ref.substring(0, hash));
        String rest = ref.substring(hash + 1);
        int colon = rest.indexOf(':');
        String name = colon < 0 ? rest : rest.substring(0, colon);
        String descriptor = colon < 0 ? null : rest.substring(colon + 1);
        rejectSpecialName(name, ref);
        return new MemberRef(owner, name, descriptor);
    }

    private static MemberRef parseMethodRef(String ref) {
        int hash = ref.indexOf('#');
        if (hash < 0) {
            throw new IllegalArgumentException("custom-names: method key must be \"Owner#name(descriptor)\": " + ref);
        }
        String owner = internalForm(ref.substring(0, hash));
        String rest = ref.substring(hash + 1);
        int paren = rest.indexOf('(');
        if (paren < 0) {
            throw new IllegalArgumentException(
                    "custom-names: method key is missing its descriptor (must contain '('): " + ref);
        }
        String name = rest.substring(0, paren);
        String descriptor = rest.substring(paren);
        rejectSpecialName(name, ref);
        return new MemberRef(owner, name, descriptor);
    }

    private static void rejectSpecialName(String name, String ref) {
        if (name.equals("<init>") || name.equals("<clinit>")) {
            throw new IllegalArgumentException(
                    "custom-names: \"" + ref + "\" targets a constructor/static initializer, which can't be renamed.");
        }
    }
}