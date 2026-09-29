package com.cleandecompile.stage4;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.TypeVariable;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves {@code X is not abstract and does not override abstract method
 * m(P) in Owner} for owners the source-level {@code VoidAbstractStubFixer}
 * cannot help with: non-void methods, and owners that are JDK or vendored
 * library types with no source in the tree.
 *
 * <p>Two strategies, in order of how much they trust the evidence:
 *
 * <ol>
 *   <li><b>Delegate to the renamed override.</b> The bytecode of a
 *       non-abstract class necessarily implements every abstract method it
 *       inherits, so when javac says one is missing, the implementation
 *       exists under another name: Stage 0 renames anything it fails to
 *       prove is an override to {@code methodNNN} (the raw-vs-generic
 *       override shapes noted in the README). If the class has exactly one
 *       {@code methodNNN} member with the required parameter types, a
 *       matching return type (checked reflectively when the owner is
 *       loadable) and no {@code throws}, a one-line {@code @Override}
 *       forwarding method restores the contract while keeping the original
 *       body. Behaviour is preserved by construction.</li>
 *   <li><b>Curated JDK defaults.</b> For a handful of {@code LayoutManager2}
 *       hooks with a canonical, documented "no constraint" answer
 *       ({@code maximumLayoutSize} = unbounded, alignment = centred,
 *       {@code invalidateLayout} = nothing cached), a stub with that answer
 *       and a {@code TODO(stage4)} marker. This is the only path that
 *       invents behaviour, which is why the table is tiny and explicit.</li>
 * </ol>
 * It declines whenever the class already declares a same-named method of the
 * same arity (a signature clash is a different problem), when the class
 * cannot be identified uniquely in the file, or when the required types
 * cannot be spelled safely.
 *
 * <p>One method per class per round on purpose: javac reports only the first
 * missing method, and inventing the others could shadow an implementation
 * the class legitimately inherits from its superclass.
 */
final class MissingOverrideFixer {

    private static final Pattern MISSING = Pattern.compile(
            "^([\\w.$]+) is not abstract and does not override abstract method (\\w+)\\((.*)\\) in ([\\w.$]+)$");
    private static final Pattern JUNK_NAME = Pattern.compile("method\\d+");
    private static final Pattern PACKAGE_PREFIX = Pattern.compile("\\b[a-z_][\\w]*\\.");

    /** owner#name/arity -> {return type, body statement} */
    private static final Map<String, String[]> CURATED = Map.of(
            "java.awt.LayoutManager2#maximumLayoutSize/1", new String[]{"java.awt.Dimension",
                    "return new java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);"},
            "java.awt.LayoutManager2#getLayoutAlignmentX/1", new String[]{"float", "return 0.5f;"},
            "java.awt.LayoutManager2#getLayoutAlignmentY/1", new String[]{"float", "return 0.5f;"},
            "java.awt.LayoutManager2#invalidateLayout/1", new String[]{"void", null});

    record Result(int fixes, int delegated, int curated,
                  Set<Diagnostic<? extends JavaFileObject>> handled) {
        static final Result NONE = new Result(0, 0, 0, Set.of());

        List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
            if (handled.isEmpty()) return all;
            return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
        }
    }

    private MissingOverrideFixer() {
    }

    static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed, List<Path> classpath)
            throws IOException {
        Path root = sourceRoot.toAbsolutePath().normalize();
        Map<Path, List<Diagnostic<? extends JavaFileObject>>> byFile = new LinkedHashMap<>();
        for (var b : bucketed) {
            var d = b.diagnostic();
            if (d.getSource() == null) continue;
            if (!MISSING.matcher(SpanFixers.firstLine(d.getMessage(Locale.ENGLISH))).matches()) continue;
            Path file;
            try {
                file = Path.of(d.getSource().toUri()).toAbsolutePath().normalize();
            } catch (RuntimeException e) {
                continue;
            }
            if (!file.startsWith(root)) continue;
            byFile.computeIfAbsent(file, f -> new ArrayList<>()).add(d);
        }
        if (byFile.isEmpty()) return Result.NONE;

        int delegated = 0;
        int curated = 0;
        Set<Diagnostic<? extends JavaFileObject>> handled = Collections.newSetFromMap(new IdentityHashMap<>());
        try (OwnerLoader loader = new OwnerLoader(classpath)) {
            for (var entry : byFile.entrySet()) {
                String text;
                try {
                    text = Files.readString(entry.getKey(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                Set<String> classesDone = new HashSet<>();
                // Later insertions first so earlier offsets stay valid: re-parse after each edit instead.
                for (var d : entry.getValue()) {
                    Matcher m = MISSING.matcher(SpanFixers.firstLine(d.getMessage(Locale.ENGLISH)));
                    if (!m.matches()) continue;
                    String simple = simpleName(m.group(1));
                    if (!classesDone.add(simple)) continue; // one method per class per round
                    SpanFixers.ParsedFile parsed = SpanFixers.ParsedFile.parse(entry.getKey(), text);
                    if (parsed == null) break;
                    Fixed fixed = fixOne(parsed, simple, m.group(2), GenericCastFixer.splitTopLevel(m.group(3)),
                            m.group(4), loader);
                    if (fixed == null) continue;
                    text = fixed.text();
                    handled.add(d);
                    if (fixed.delegated()) delegated++;
                    else curated++;
                }
                if (!text.equals(Files.readString(entry.getKey(), StandardCharsets.UTF_8))) {
                    Files.writeString(entry.getKey(), text, StandardCharsets.UTF_8);
                }
            }
        }
        int total = delegated + curated;
        return total == 0 ? Result.NONE : new Result(total, delegated, curated, handled);
    }

    private record Fixed(String text, boolean delegated) {}

    /** The file text with the stub inserted, or null when this class/method is declined. */
    private static Fixed fixOne(SpanFixers.ParsedFile file, String className, String methodName,
                                 List<String> requiredParams, String owner, OwnerLoader loader) {
        List<ClassTree> matches = file.findAll(t -> t instanceof ClassTree c
                && c.getSimpleName().contentEquals(className)).stream()
                .map(p -> (ClassTree) p.getLeaf()).toList();
        if (matches.size() != 1) return null;
        ClassTree cls = matches.get(0);

        int arity = requiredParams.size();
        List<MethodTree> methods = new ArrayList<>();
        for (Tree member : cls.getMembers()) {
            if (member instanceof MethodTree mt) methods.add(mt);
        }
        for (MethodTree mt : methods) {
            if (mt.getName().contentEquals(methodName) && mt.getParameters().size() == arity) {
                return null; // same name and arity already present: a clash, not a gap
            }
        }

        String stub = delegateStub(file, methods, methodName, requiredParams, owner, loader);
        boolean delegated = stub != null;
        if (stub == null) stub = curatedStub(methodName, arity, owner);
        if (stub == null) return null;
        String updated = insertMember(file, cls, stub);
        return updated == null ? null : new Fixed(updated, delegated);
    }

    // ---- strategy 1: delegate -------------------------------------------------------------------------

    private static String delegateStub(SpanFixers.ParsedFile file, List<MethodTree> methods, String methodName,
                                       List<String> requiredParams, String owner, OwnerLoader loader) {
        List<String> required = requiredParams.stream().map(MissingOverrideFixer::normalizeType).toList();
        List<MethodTree> candidates = new ArrayList<>();
        for (MethodTree mt : methods) {
            if (!JUNK_NAME.matcher(mt.getName()).matches()) continue;
            if (mt.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.STATIC)) continue;
            if (mt.getModifiers().getFlags().contains(javax.lang.model.element.Modifier.ABSTRACT)) continue;
            if (!mt.getTypeParameters().isEmpty() || !mt.getThrows().isEmpty()) continue;
            if (mt.getReturnType() == null || mt.getParameters().size() != required.size()) continue;
            boolean same = true;
            for (int i = 0; i < required.size() && same; i++) {
                String declared = file.slice(mt.getParameters().get(i).getType());
                same = declared != null && normalizeType(declared).equals(required.get(i));
            }
            if (same) candidates.add(mt);
        }
        if (candidates.size() != 1) return null;
        MethodTree target = candidates.get(0);
        String returnType = file.slice(target.getReturnType());
        if (returnType == null) return null;
        if (!returnTypeCompatible(loader, owner, methodName, required.size(), normalizeType(returnType))) {
            return null;
        }

        List<String> declaredParams = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (VariableTree p : target.getParameters()) {
            String type = file.slice(p.getType());
            if (type == null) return null;
            declaredParams.add(type + " " + p.getName());
            names.add(p.getName().toString());
        }
        String call = target.getName() + "(" + String.join(", ", names) + ");";
        boolean isVoid = returnType.strip().equals("void");
        return "// stage4: delegates to " + target.getName() + " (renamed override of " + owner + "#" + methodName + ")\n"
                + "@Override\n"
                + "public " + returnType.strip() + " " + methodName + "(" + String.join(", ", declaredParams) + ") {\n"
                + "    " + (isVoid ? "" : "return ") + call + "\n"
                + "}";
    }

    /** Reflective check when the owner is loadable; permissive when it is not (or the return type is generic). */
    private static boolean returnTypeCompatible(OwnerLoader loader, String owner, String name, int arity,
                                                String declaredReturn) {
        Class<?> type = loader.load(owner);
        if (type == null) return true;
        java.lang.reflect.Method found = null;
        for (java.lang.reflect.Method m : allMethods(type)) {
            if (m.getName().equals(name) && m.getParameterCount() == arity && Modifier.isAbstract(m.getModifiers())) {
                found = m;
                break;
            }
        }
        if (found == null) return true;
        if (found.getGenericReturnType() instanceof TypeVariable<?>) return true;
        String expected = found.getReturnType().getCanonicalName();
        return normalizeType(expected == null ? found.getReturnType().getName() : expected).equals(declaredReturn);
    }

    private static List<java.lang.reflect.Method> allMethods(Class<?> type) {
        List<java.lang.reflect.Method> all = new ArrayList<>();
        Deque<Class<?>> todo = new ArrayDeque<>();
        todo.add(type);
        Set<Class<?>> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            Class<?> c = todo.poll();
            if (c == null || !seen.add(c)) continue;
            try {
                all.addAll(Arrays.asList(c.getDeclaredMethods()));
                if (c.getSuperclass() != null) todo.add(c.getSuperclass());
                todo.addAll(Arrays.asList(c.getInterfaces()));
            } catch (LinkageError | SecurityException ignored) {
                // A half-resolvable library type: judge on what did load.
            }
        }
        return all;
    }

    // ---- strategy 2: curated JDK defaults -------------------------------------------------------------

    private static String curatedStub(String methodName, int arity, String owner) {
        String[] entry = CURATED.get(owner + "#" + methodName + "/" + arity);
        if (entry == null) return null;
        // Parameter is always a java.awt.Container for the entries above.
        String body = entry[1] == null ? "" : "    " + entry[1] + "\n";
        return "// TODO(stage4): synthesized -- the original " + owner + "#" + methodName
                + " was not recovered; canonical default\n"
                + "@Override\n"
                + "public " + entry[0] + " " + methodName + "(java.awt.Container stage4Container) {\n"
                + body
                + "}";
    }

    // ---- insertion ------------------------------------------------------------------------------------

    private static String insertMember(SpanFixers.ParsedFile file, ClassTree cls, String stub) {
        String text = file.text;
        long end = file.end(cls);
        if (end <= 0 || end > text.length() || text.charAt((int) end - 1) != '}') return null;
        int close = (int) end - 1;
        String newline = text.contains("\r\n") ? "\r\n" : "\n";

        int lineStart = text.lastIndexOf('\n', close - 1) + 1;
        String beforeBrace = text.substring(lineStart, close);
        String closeIndent;
        int insertAt;
        String prefix;
        if (beforeBrace.isBlank()) {
            closeIndent = beforeBrace;
            insertAt = lineStart;
            prefix = "";
        } else {
            closeIndent = beforeBrace.substring(0, beforeBrace.length() - beforeBrace.stripLeading().length());
            insertAt = close;
            prefix = newline;
        }
        String memberIndent = memberIndent(file, cls, closeIndent);
        StringBuilder block = new StringBuilder(prefix);
        block.append(newline);
        for (String line : stub.split("\n")) {
            block.append(line.isEmpty() ? "" : memberIndent + line).append(newline);
        }
        if (insertAt == close) block.append(closeIndent);
        return text.substring(0, insertAt) + block + text.substring(insertAt);
    }

    private static String memberIndent(SpanFixers.ParsedFile file, ClassTree cls, String closeIndent) {
        for (Tree member : cls.getMembers()) {
            long s = file.start(member);
            if (s <= 0) continue;
            int lineStart = file.text.lastIndexOf('\n', (int) s - 1) + 1;
            String lead = file.text.substring(lineStart, (int) s);
            if (lead.isBlank() && !lead.isEmpty()) return lead;
        }
        return closeIndent + "    ";
    }

    // ---- helpers --------------------------------------------------------------------------------------

    /** Drops package qualifiers and whitespace so {@code java.lang.Character} equals {@code Character}. */
    static String normalizeType(String type) {
        String t = type.strip().replace("...", "[]");
        t = PACKAGE_PREFIX.matcher(t).replaceAll("");
        return t.replaceAll("\\s+", "");
    }

    private static String simpleName(String qualified) {
        int dot = qualified.lastIndexOf('.');
        return dot < 0 ? qualified : qualified.substring(dot + 1);
    }

    /** Loads owner types without initializing them: JDK, then the vendored-library classpath. */
    private static final class OwnerLoader implements AutoCloseable {
        private final URLClassLoader loader;

        OwnerLoader(List<Path> classpath) {
            List<URL> urls = new ArrayList<>();
            for (Path p : classpath) {
                try {
                    if (Files.exists(p)) urls.add(p.toUri().toURL());
                } catch (Exception ignored) {
                    // unusable classpath entry
                }
            }
            this.loader = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        }

        Class<?> load(String name) {
            // Nested types print as Outer.Inner: retry with '$' from the right.
            String candidate = name;
            while (true) {
                try {
                    return Class.forName(candidate, false, loader);
                } catch (ClassNotFoundException | LinkageError e) {
                    int dot = candidate.lastIndexOf('.');
                    if (dot < 0) return null;
                    candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
                }
            }
        }

        @Override
        public void close() throws IOException {
            loader.close();
        }
    }
}
