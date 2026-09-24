package com.cleandecompile.stage4;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.DoWhileLoopTree;
import com.sun.source.tree.EnhancedForLoopTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.ForLoopTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.WildcardType;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Fixes the "specific type required, {@code Object} found" family:
 *
 * <ul>
 *   <li>{@code array required, but Object found}</li>
 *   <li>{@code for-each not applicable to expression type ... found: Object}</li>
 *   <li>{@code cannot find symbol ... location: variable v of type Object}
 *       (method call or field access on an {@code Object} local)</li>
 *   <li>{@code bad operand types for binary operator ... Object / char}</li>
 * </ul>
 *
 * <h2>Why these appear</h2>
 * The JVM verifier types every interface value as {@code Object} at a
 * control-flow merge, and ProGuard's slot allocation reuses one local slot
 * for unrelated types. Decompilers that can't split the variable back apart
 * emit a single {@code Object v}, while the initializer expressions still
 * have precise static types ({@code v = names.iterator()}).
 *
 * <h2>Approach: javac as the type oracle</h2>
 * A line-level regex can't know that {@code object[0]} needs {@code int[]}.
 * So the files carrying these errors are re-attributed in-process with the
 * Compiler Tree API, which yields (even on erroneous code) the static type
 * of every assignment to the variable. For each flagged local:
 *
 * <ol>
 *   <li><b>Retype the declaration</b> ({@code Object it} to
 *       {@code Iterator<String> it}) when that is provably sound: one type
 *       explains every flagged use and <i>every</i> assignment to the
 *       variable is assignable to it. This is the cleanest output and
 *       cannot introduce a runtime cast.</li>
 *   <li>Otherwise <b>cast at the use site</b>
 *       ({@code ((String[]) v)[0]}), using the type of the assignment that
 *       reaches the use. "Reaches" is decided conservatively: the assignment
 *       must dominate the use (its block encloses the use, it is not behind
 *       a short-circuit / ternary / lambda, and no loop-carried
 *       reassignment can intervene). A use that can't be attributed to one
 *       assignment is left alone rather than guessed, since a wrong cast
 *       would compile and then throw at runtime.</li>
 *   <li>If no assignment has a usable type, infer from what the flagged use
 *       demands: a field name declared by exactly one class in the source
 *       tree (sound, since generated {@code fieldNNNN} names are unique),
 *       or -- as a clearly logged <i>guess</i> -- a JDK type from a small
 *       catalog that uniquely (or as a supertype chain) has the demanded
 *       methods.</li>
 * </ol>
 *
 * <p>Array-element types, primitive operand types and for-each element types
 * are never guessed from demand alone; they need assignment evidence.
 *
 * <p>Idempotent (a retyped declaration or a cast use no longer errors) and
 * fail-soft: any internal javac failure yields "0 fixes" rather than
 * aborting Stage 4. Edits never add or remove lines, so line numbers in the
 * remaining diagnostics stay valid for later fixers.
 */
final class ObjectTypedLocalFixer {

    /** Diagnostics this fixer may act on (matched against the English message). */
    private static final Pattern FAMILY = Pattern.compile(
            "array required, but (?:java\\.lang\\.)?Object found"
                    + "|for-each not applicable to expression type[\\s\\S]*found:\\s+(?:java\\.lang\\.)?Object\\s*$"
                    + "|cannot find symbol[\\s\\S]*location: variable \\w+ of type (?:java\\.lang\\.)?Object\\s*$"
                    + "|bad operand types for binary operator[\\s\\S]*(?:first|second) type:\\s+(?:java\\.lang\\.)?Object\\b");
    private static final Pattern BAD_OPERAND = Pattern.compile("bad operand types for binary operator");

    /** Name-based guesses, tried only when neither assignments nor the
     *  source tree give evidence. Order is irrelevant; a candidate wins only
     *  if it is the widest of all catalog types having the demanded methods. */
    private static final List<String> CATALOG = List.of(
            "java.lang.String", "java.lang.StringBuilder",
            "java.util.Collection", "java.util.List", "java.util.Set", "java.util.Map",
            "java.util.Iterator", "java.util.Scanner", "java.io.File");

    private static final int MAX_LOGGED_GUESSES = 40;

    /** Outcome of one {@link #tryFixAll} call. */
    record Result(int fixes, Set<Diagnostic<? extends JavaFileObject>> handled) {
        static final Result NONE = new Result(0, Set.of());

        /** The diagnostics this fixer did not address, for the later fixers
         *  (which would otherwise stack redundant casts on the same lines). */
        List<DiagnosticBucketer.Bucketed> unhandled(List<DiagnosticBucketer.Bucketed> all) {
            if (handled.isEmpty()) return all;
            return all.stream().filter(b -> !handled.contains(b.diagnostic())).toList();
        }
    }

    private ObjectTypedLocalFixer() {
    }

    static Result tryFixAll(Path sourceRoot, List<DiagnosticBucketer.Bucketed> bucketed,
                            List<Path> classpath, String releaseLevel) {
        Map<Path, List<Diagnostic<? extends JavaFileObject>>> flagged = new LinkedHashMap<>();
        // javac computes a diagnostic's line/column lazily from the file's
        // CURRENT text and caches it. This fixer rewrites files, so force
        // every diagnostic's position now: otherwise line numbers read
        // after the rewrite (here, or in the later fixers) would be
        // computed against shifted text and point at the wrong line.
        for (var b : bucketed) {
            var d = b.diagnostic();
            if (d.getSource() != null) {
                d.getLineNumber();
                d.getColumnNumber();
                d.getPosition();
            }
        }
        for (var b : bucketed) {
            var d = b.diagnostic();
            if (d.getSource() == null || !FAMILY.matcher(d.getMessage(Locale.ENGLISH)).find()) continue;
            Path file = pathOf(d.getSource());
            if (file != null) flagged.computeIfAbsent(file, f -> new ArrayList<>()).add(d);
        }
        if (flagged.isEmpty()) return Result.NONE;
        try {
            return new Session(sourceRoot, classpath, releaseLevel).run(flagged);
        } catch (IOException | RuntimeException | AssertionError e) {
            // javac internals can throw on pathological input; this fixer is
            // an optimisation of the loop, never a reason to abort it.
            System.out.println("  object-typed fixer skipped: " + e);
            return Result.NONE;
        }
    }

    private static Path pathOf(JavaFileObject fo) {
        try {
            return Path.of(fo.toUri()).toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Model
    // ------------------------------------------------------------------

    private enum Role { ARRAY, ITERATE, METHOD, FIELD, PRIMITIVE }

    /** One flagged use of an Object-typed variable. */
    private record Site(TreePath path, String var, int start, int end, int line,
                        Role role, String member, int arity) {
    }

    /** One assignment (or initializer) of the variable. */
    private record Def(int offset, int stmtStart, TreePath rhs, Tree block, boolean conditional) {
    }

    private static final class VarInfo {
        final VariableElement element;
        VariableTree decl;
        TreePath declPath;
        final List<Def> defs = new ArrayList<>();
        final List<Site> sites = new ArrayList<>();

        VarInfo(VariableElement element) {
            this.element = element;
        }
    }

    private record Edit(int start, int end, String expectedOld, String replacement) {
    }

    /** A def with its resolved static type ({@code type == null}: unusable). */
    private record TypedDef(Def def, TypeMirror type) {
    }

    // ------------------------------------------------------------------
    // Session: one javac attribution shared by all flagged files
    // ------------------------------------------------------------------

    private static final class Session {
        private final Path sourceRoot;
        private final List<Path> classpath;
        private final String releaseLevel;

        private JavaCompiler compiler;
        private Trees trees;
        private SourcePositions positions;
        private Elements elements;
        private Types types;
        private TypeMirror iterableRaw;
        private Set<String> objectMethodNames;
        private Map<String, Set<String>> fieldOwners;
        private final List<String> guessLog = new ArrayList<>();

        Session(Path sourceRoot, List<Path> classpath, String releaseLevel) {
            this.sourceRoot = sourceRoot;
            this.classpath = classpath;
            this.releaseLevel = releaseLevel;
        }

        Result run(Map<Path, List<Diagnostic<? extends JavaFileObject>>> flagged) throws IOException {
            compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) return Result.NONE;
            DiagnosticCollector<JavaFileObject> ignored = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(ignored, null, StandardCharsets.UTF_8)) {
                fm.setLocationFromPaths(StandardLocation.SOURCE_PATH, List.of(sourceRoot));
                List<Path> cp = classpath.stream().filter(Files::exists).toList();
                if (!cp.isEmpty()) fm.setLocationFromPaths(StandardLocation.CLASS_PATH, cp);

                // The decisive option: by default javac may stop attributing
                // once errors exist; the oracle needs types even on broken code.
                List<String> options = List.of("-proc:none", "-nowarn", "--release", releaseLevel,
                        "-implicit:none", "-XDshould-stop.at=FLOW", "-XDshould-stop.ifError=FLOW",
                        "-Xmaxerrs", "100000");
                JavacTask task = (JavacTask) compiler.getTask(null, fm, ignored, options, null,
                        fm.getJavaFileObjectsFromPaths(new ArrayList<>(flagged.keySet())));
                List<CompilationUnitTree> units = new ArrayList<>();
                task.parse().forEach(units::add);
                task.analyze();

                trees = Trees.instance(task);
                positions = trees.getSourcePositions();
                elements = task.getElements();
                types = task.getTypes();
                TypeElement iterable = elements.getTypeElement("java.lang.Iterable");
                TypeElement object = elements.getTypeElement("java.lang.Object");
                if (iterable == null || object == null) return Result.NONE;
                iterableRaw = types.erasure(iterable.asType());
                objectMethodNames = ElementFilter.methodsIn(elements.getAllMembers(object)).stream()
                        .map(m -> m.getSimpleName().toString()).collect(Collectors.toSet());

                Map<Path, String> rewritten = new LinkedHashMap<>();
                Map<Path, Set<Integer>> fixedLines = new HashMap<>();
                int fixes = 0;
                for (CompilationUnitTree cu : units) {
                    Path file = pathOf(cu.getSourceFile());
                    var diags = file == null ? null : flagged.get(file);
                    if (diags == null) continue;
                    Set<Integer> binaryLines = diags.stream()
                            .filter(d -> BAD_OPERAND.matcher(d.getMessage(Locale.ENGLISH)).find())
                            .map(d -> (int) d.getLineNumber()).collect(Collectors.toSet());
                    FilePlanner planner = new FilePlanner(cu, binaryLines);
                    List<Edit> edits = planner.plan();
                    if (edits.isEmpty()) continue;
                    String text = cu.getSourceFile().getCharContent(true).toString();
                    String updated = apply(text, edits);
                    if (updated == null || updated.equals(text)) continue;
                    rewritten.put(file, updated);
                    Set<Integer> lines = fixedLines.computeIfAbsent(file, f -> new HashSet<>());
                    for (int line : planner.coveredLines) {
                        lines.add(line);
                        lines.add(line + 1); // wrapped call chains report the next line
                    }
                    fixes += edits.size();
                }
                for (var entry : rewritten.entrySet()) {
                    Files.writeString(entry.getKey(), entry.getValue(), StandardCharsets.UTF_8);
                }

                Set<Diagnostic<? extends JavaFileObject>> handled =
                        Collections.newSetFromMap(new IdentityHashMap<>());
                for (var entry : flagged.entrySet()) {
                    Set<Integer> lines = fixedLines.get(entry.getKey());
                    if (lines == null) continue;
                    for (var d : entry.getValue()) {
                        if (lines.contains((int) d.getLineNumber())) handled.add(d);
                    }
                }
                for (int i = 0; i < Math.min(guessLog.size(), MAX_LOGGED_GUESSES); i++) {
                    System.out.println("  ~ name-based guess (audit): " + guessLog.get(i));
                }
                if (guessLog.size() > MAX_LOGGED_GUESSES) {
                    System.out.println("  ~ ... and " + (guessLog.size() - MAX_LOGGED_GUESSES) + " more guesses");
                }
                return new Result(fixes, handled);
            }
        }

        /** Applies edits back to front so earlier offsets stay valid; every
         *  edit re-verifies the text it replaces (null: file changed under us). */
        private static String apply(String text, List<Edit> edits) {
            List<Edit> sorted = new ArrayList<>(edits);
            sorted.sort(Comparator.comparingInt(Edit::start).reversed());
            StringBuilder sb = new StringBuilder(text);
            int lastStart = Integer.MAX_VALUE;
            for (Edit e : sorted) {
                if (e.end() > lastStart || e.start() < 0 || e.end() > sb.length()) continue; // overlap / out of range
                if (!sb.substring(e.start(), e.end()).equals(e.expectedOld())) continue;
                sb.replace(e.start(), e.end(), e.replacement());
                lastStart = e.start();
            }
            return sb.toString();
        }

        // --------------------------------------------------------------
        // Per-file: discover, then plan
        // --------------------------------------------------------------

        private final class FilePlanner {
            private final CompilationUnitTree cu;
            private final Set<Integer> binaryLines;
            private final Map<VariableElement, VarInfo> vars = new LinkedHashMap<>();
            private final Set<Tree> methodSelects = Collections.newSetFromMap(new IdentityHashMap<>());
            /** Every line whose flagged use was addressed by an edit. */
            final Set<Integer> coveredLines = new HashSet<>();
            private final Scope scope;

            FilePlanner(CompilationUnitTree cu, Set<Integer> binaryLines) {
                this.cu = cu;
                this.binaryLines = binaryLines;
                this.scope = scopeOf(cu);
            }

            List<Edit> plan() {
                new Discovery().scan(cu, null);
                List<Edit> edits = new ArrayList<>();
                for (VarInfo v : vars.values()) {
                    if (!v.sites.isEmpty()) planVariable(v, edits);
                }
                return edits;
            }

            // ---- discovery ----

            private final class Discovery extends TreePathScanner<Void, Void> {
                @Override
                public Void visitVariable(VariableTree node, Void p) {
                    Element el = trees.getElement(getCurrentPath());
                    if (el instanceof VariableElement ve && el.getKind() == ElementKind.LOCAL_VARIABLE
                            && isObject(ve.asType())) {
                        VarInfo v = vars.computeIfAbsent(ve, VarInfo::new);
                        v.decl = node;
                        v.declPath = getCurrentPath();
                        if (node.getInitializer() != null) {
                            addDef(v, getCurrentPath(), node.getInitializer());
                        }
                    }
                    return super.visitVariable(node, p);
                }

                @Override
                public Void visitAssignment(AssignmentTree node, Void p) {
                    if (node.getVariable() instanceof IdentifierTree) {
                        VarInfo v = objectVar(new TreePath(getCurrentPath(), node.getVariable()));
                        if (v != null) addDef(v, getCurrentPath(), node.getExpression());
                    }
                    return super.visitAssignment(node, p);
                }

                @Override
                public Void visitArrayAccess(ArrayAccessTree node, Void p) {
                    site(getCurrentPath(), node.getExpression(), Role.ARRAY, null, 0);
                    return super.visitArrayAccess(node, p);
                }

                @Override
                public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void p) {
                    site(getCurrentPath(), node.getExpression(), Role.ITERATE, null, 0);
                    return super.visitEnhancedForLoop(node, p);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
                    if (node.getMethodSelect() instanceof MemberSelectTree ms) {
                        methodSelects.add(ms);
                        String name = ms.getIdentifier().toString();
                        if (!objectMethodNames.contains(name)) {
                            site(new TreePath(getCurrentPath(), ms), ms.getExpression(), Role.METHOD,
                                    name, node.getArguments().size());
                        }
                    }
                    return super.visitMethodInvocation(node, p);
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree node, Void p) {
                    if (!methodSelects.contains(node)) {
                        String name = node.getIdentifier().toString();
                        if (name.equals("length")) {
                            site(getCurrentPath(), node.getExpression(), Role.ARRAY, null, 0);
                        } else if (!name.equals("class") && !name.equals("this") && !name.equals("super")) {
                            site(getCurrentPath(), node.getExpression(), Role.FIELD, name, 0);
                        }
                    }
                    return super.visitMemberSelect(node, p);
                }

                @Override
                public Void visitBinary(BinaryTree node, Void p) {
                    operand(node.getLeftOperand(), node.getRightOperand());
                    operand(node.getRightOperand(), node.getLeftOperand());
                    return super.visitBinary(node, p);
                }

                private void operand(ExpressionTree candidate, ExpressionTree other) {
                    if (!(candidate instanceof IdentifierTree)) return;
                    TypeMirror ot = trees.getTypeMirror(new TreePath(getCurrentPath(), other));
                    if (ot == null || !ot.getKind().isPrimitive()) return;
                    // Only where javac actually rejected it: some Object/primitive
                    // comparisons are accepted, and casting those would change meaning.
                    site(getCurrentPath(), candidate, Role.PRIMITIVE, null, 0);
                }

                /** Records a use of {@code expr} when it is an Object-typed
                 *  local/parameter (identifier only -- no chains). {@code parent}
                 *  is the path of the node whose direct child {@code expr} is. */
                private void site(TreePath parent, ExpressionTree expr, Role role, String member, int arity) {
                    if (!(expr instanceof IdentifierTree id)) return;
                    VarInfo v = objectVar(new TreePath(parent, expr));
                    if (v == null) return;
                    long start = positions.getStartPosition(cu, id);
                    long end = positions.getEndPosition(cu, id);
                    if (start < 0 || end < 0) return;
                    int line = (int) cu.getLineMap().getLineNumber(start);
                    if (role == Role.PRIMITIVE && !binaryLines.contains(line)) return;
                    v.sites.add(new Site(new TreePath(parent, expr), id.getName().toString(),
                            (int) start, (int) end, line, role, member, arity));
                }

                private VarInfo objectVar(TreePath identPath) {
                    if (!(identPath.getLeaf() instanceof IdentifierTree id)) return null;
                    if (id.getName().contentEquals("this") || id.getName().contentEquals("super")) return null;
                    Element el = trees.getElement(identPath);
                    if (!(el instanceof VariableElement ve)) return null;
                    if (el.getKind() != ElementKind.LOCAL_VARIABLE && el.getKind() != ElementKind.PARAMETER) {
                        return null;
                    }
                    if (!isObject(ve.asType())) return null;
                    return vars.computeIfAbsent(ve, VarInfo::new);
                }

                private void addDef(VarInfo v, TreePath defPath, ExpressionTree rhs) {
                    long end = positions.getEndPosition(cu, rhs);
                    long stmtStart = positions.getStartPosition(cu, defPath.getLeaf());
                    if (end < 0 || stmtStart < 0) return;
                    Tree block = null;
                    boolean conditional = false;
                    for (TreePath p = defPath.getParentPath(); p != null; p = p.getParentPath()) {
                        Tree t = p.getLeaf();
                        if (t instanceof BlockTree || t instanceof CaseTree) {
                            block = t;
                            break;
                        }
                        // Not guaranteed to execute (short circuit, ternary arm)
                        // or executes elsewhere/later (lambda body).
                        if (t instanceof ConditionalExpressionTree || t instanceof LambdaExpressionTree
                                || (t instanceof BinaryTree b && (b.getKind() == Tree.Kind.CONDITIONAL_AND
                                || b.getKind() == Tree.Kind.CONDITIONAL_OR))) {
                            conditional = true;
                        }
                        if (t instanceof MethodTree || t instanceof ClassTree) break;
                    }
                    v.defs.add(new Def((int) end, (int) stmtStart,
                            new TreePath(defPath, rhs), block, conditional));
                }
            }

            // ---- planning ----

            private void planVariable(VarInfo v, List<Edit> edits) {
                Scope pkg = scope;
                List<TypedDef> defs = new ArrayList<>();
                List<Def> ordered = new ArrayList<>(v.defs);
                ordered.sort(Comparator.comparingInt(Def::offset));
                for (Def d : ordered) {
                    TypeMirror t = staticType(d.rhs());
                    if (t != null && t.getKind() == TypeKind.NULL) continue; // "= null" says nothing
                    defs.add(new TypedDef(d, usable(t) ? t : null));
                }
                List<Site> sites = new ArrayList<>(v.sites);
                sites.sort(Comparator.comparingInt(Site::start));

                // The reaching definition of each site: the last one before it.
                Map<Site, List<Site>> regionOf = new IdentityHashMap<>();
                Map<Integer, List<Site>> regions = new LinkedHashMap<>();
                for (Site s : sites) {
                    int idx = -1;
                    for (int i = 0; i < defs.size(); i++) {
                        if (defs.get(i).def().offset() <= s.start()) idx = i;
                    }
                    List<Site> region = regions.computeIfAbsent(idx, k -> new ArrayList<>());
                    region.add(s);
                    regionOf.put(s, region);
                }

                Map<Site, TypeMirror> resolved = new IdentityHashMap<>();
                Set<Site> guessed = Collections.newSetFromMap(new IdentityHashMap<>());
                for (var entry : regions.entrySet()) {
                    TypedDef reaching = entry.getKey() >= 0 ? defs.get(entry.getKey()) : null;
                    for (Site s : entry.getValue()) {
                        TypeMirror t = fromAssignment(reaching, s, v, ordered);
                        if (t == null) {
                            Inferred inf = inferFromDemand(entry.getValue(), s, pkg);
                            if (inf != null) {
                                t = inf.type();
                                if (inf.guess()) guessed.add(s);
                            }
                        }
                        if (t != null) resolved.put(s, t);
                    }
                }

                String retype = retypeTarget(v, defs, sites, resolved, pkg);
                if (retype != null) {
                    var type = v.decl.getType();
                    long ts = positions.getStartPosition(cu, type);
                    long te = positions.getEndPosition(cu, type);
                    String old = ts >= 0 && te >= 0 ? sourceText().substring((int) ts, (int) te) : "";
                    if (old.equals("Object") || old.equals("java.lang.Object")) {
                        edits.add(new Edit((int) ts, (int) te, old, retype));
                        for (Site s : sites) coveredLines.add(s.line());
                        return;
                    }
                }

                for (Site s : sites) {
                    TypeMirror t = resolved.get(s);
                    String src = t == null ? null : render(t, pkg);
                    if (src == null) continue;
                    edits.add(new Edit(s.start(), s.end(), s.var(), "((" + src + ") " + s.var() + ")"));
                    coveredLines.add(s.line());
                    if (guessed.contains(s)) {
                        guessLog.add(pathOf(cu.getSourceFile()).getFileName() + ":" + s.line()
                                + " " + s.var() + " -> " + src);
                    }
                }
            }

            private String sourceText;

            private String sourceText() {
                if (sourceText == null) {
                    try {
                        sourceText = cu.getSourceFile().getCharContent(true).toString();
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
                return sourceText;
            }

            /** The assignment-evidence type for one site, or null. */
            private TypeMirror fromAssignment(TypedDef reaching, Site s, VarInfo v, List<Def> allDefs) {
                if (reaching == null || reaching.type() == null) return null;
                if (!dominates(reaching.def(), s) || loopCarried(s, allDefs)) return null;
                return satisfies(reaching.type(), s) ? reaching.type() : null;
            }

            /** The def's block encloses the site and the def always executes. */
            private boolean dominates(Def d, Site s) {
                if (d.conditional() || d.block() == null) return false;
                for (TreePath p = s.path(); p != null; p = p.getParentPath()) {
                    if (p.getLeaf() == d.block()) return true;
                }
                return false;
            }

            /** A reassignment later in an enclosing loop reaches this use on
             *  the next iteration, so the "last def before the site" is not
             *  the only reaching one. */
            private boolean loopCarried(Site s, List<Def> allDefs) {
                for (TreePath p = s.path().getParentPath(); p != null; p = p.getParentPath()) {
                    Tree t = p.getLeaf();
                    if (t instanceof MethodTree || t instanceof ClassTree) break;
                    if (t instanceof WhileLoopTree || t instanceof DoWhileLoopTree
                            || t instanceof ForLoopTree || t instanceof EnhancedForLoopTree) {
                        long ls = positions.getStartPosition(cu, t);
                        long le = positions.getEndPosition(cu, t);
                        for (Def d : allDefs) {
                            if (d.offset() > s.start() && d.stmtStart() >= ls && d.stmtStart() < le) return true;
                        }
                    }
                }
                return false;
            }

            /**
             * Source text of the type to give the declaration, or null when
             * retyping isn't provably sound. Sound = one type explains every
             * flagged use AND every assignment is assignable to it (so no
             * cast is ever needed, and nothing can throw that didn't before).
             */
            private String retypeTarget(VarInfo v, List<TypedDef> defs, List<Site> sites,
                                        Map<Site, TypeMirror> resolved, Scope pkg) {
                if (v.element.getKind() != ElementKind.LOCAL_VARIABLE || v.decl == null) return null;
                if (!isStatement(v.declPath) || isMultiDeclarator(v)) return null;
                // Prefer evidence from the assignments themselves: a type every
                // assignment is assignable to, that also explains every flagged
                // use. This holds across branch merges where no single
                // assignment dominates a use (so per-use casts are refused),
                // and keeps type arguments (Iterator<String>, not raw Iterator).
                TypeMirror common = commonOfDefs(defs, sites);
                if (common == null) {
                    if (resolved.size() != sites.size()) return null; // some use unexplained
                    for (Site s : sites) {
                        TypeMirror t = resolved.get(s);
                        if (common == null) common = t;
                        else if (!types.isSameType(common, t)) return null;
                    }
                }
                if (common == null) return null;
                // Reference types only: retyping to a primitive would drop
                // boxing (== identity). char[] only: overloads like
                // append/valueOf/println treat it differently from Object.
                boolean referenceType = common.getKind() == TypeKind.DECLARED || common.getKind() == TypeKind.ARRAY;
                if (!referenceType) return null;
                if (common.getKind() == TypeKind.ARRAY
                        && ((ArrayType) common).getComponentType().getKind() == TypeKind.CHAR) {
                    return null;
                }
                for (TypedDef d : defs) {
                    if (d.type() == null || !types.isAssignable(d.type(), common)) return null;
                }
                return render(common, pkg);
            }

            /** A type every typed assignment is assignable to and that has what
             *  every flagged use needs; null when there is none (an untyped
             *  assignment, unrelated types, or a use the type cannot serve). */
            private TypeMirror commonOfDefs(List<TypedDef> defs, List<Site> sites) {
                if (defs.isEmpty() || sites.isEmpty()) return null;
                for (TypedDef d : defs) {
                    if (d.type() == null) return null;
                }
                for (TypedDef candidate : defs) {
                    TypeMirror t = candidate.type();
                    boolean all = true;
                    for (TypedDef other : defs) {
                        if (!types.isAssignable(other.type(), t)) {
                            all = false;
                            break;
                        }
                    }
                    if (!all) continue;
                    for (Site s : sites) {
                        if (!satisfies(t, s)) {
                            all = false;
                            break;
                        }
                    }
                    if (all) return t;
                }
                return null;
            }

            private boolean isStatement(TreePath declPath) {
                Tree parent = declPath.getParentPath().getLeaf();
                return parent instanceof BlockTree || parent instanceof CaseTree;
            }

            /** {@code Object a = x, b = y;} parses as two declarations sharing
             *  one type node; retyping one would silently retype the other. */
            private boolean isMultiDeclarator(VarInfo v) {
                Tree parent = v.declPath.getParentPath().getLeaf();
                List<? extends Tree> siblings = parent instanceof BlockTree b ? b.getStatements()
                        : parent instanceof CaseTree c ? c.getStatements() : List.of();
                long typeStart = positions.getStartPosition(cu, v.decl.getType());
                int same = 0;
                for (Tree t : siblings) {
                    if (t instanceof VariableTree vt && positions.getStartPosition(cu, vt.getType()) == typeStart) {
                        same++;
                    }
                }
                return same > 1;
            }
        }

        // --------------------------------------------------------------
        // Types
        // --------------------------------------------------------------

        private TypeMirror staticType(TreePath path) {
            try {
                return trees.getTypeMirror(path);
            } catch (RuntimeException e) {
                return null;
            }
        }

        private boolean isObject(TypeMirror t) {
            return t != null && t.getKind() == TypeKind.DECLARED
                    && ((TypeElement) ((DeclaredType) t).asElement()).getQualifiedName().contentEquals("java.lang.Object");
        }

        /** A type that carries information and could stand in a declaration. */
        private boolean usable(TypeMirror t) {
            if (t == null) return false;
            return switch (t.getKind()) {
                case ARRAY, DECLARED -> !isObject(t);
                case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> true;
                default -> false; // ERROR (cascade), NULL, VOID, TYPEVAR, INTERSECTION, ...
            };
        }

        private boolean satisfies(TypeMirror t, Site s) {
            return switch (s.role()) {
                case ARRAY -> t.getKind() == TypeKind.ARRAY;
                case ITERATE -> t.getKind() == TypeKind.ARRAY
                        || (t.getKind() == TypeKind.DECLARED && types.isAssignable(types.erasure(t), iterableRaw));
                case METHOD -> t.getKind() == TypeKind.DECLARED
                        && hasMethod((TypeElement) ((DeclaredType) t).asElement(), s.member(), s.arity());
                case FIELD -> t.getKind() == TypeKind.DECLARED
                        && hasField((TypeElement) ((DeclaredType) t).asElement(), s.member());
                case PRIMITIVE -> t.getKind().isPrimitive() || unboxes(t);
            };
        }

        private boolean unboxes(TypeMirror t) {
            try {
                return types.unboxedType(t) != null;
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        private boolean hasMethod(TypeElement te, String name, int arity) {
            for (ExecutableElement m : ElementFilter.methodsIn(elements.getAllMembers(te))) {
                if (!m.getSimpleName().contentEquals(name)) continue;
                int n = m.getParameters().size();
                if (n == arity || (m.isVarArgs() && arity >= n - 1)) return true;
            }
            return false;
        }

        private boolean hasField(TypeElement te, String name) {
            for (VariableElement f : ElementFilter.fieldsIn(elements.getAllMembers(te))) {
                if (f.getSimpleName().contentEquals(name)) return true;
            }
            return false;
        }

        // --------------------------------------------------------------
        // Demand-based inference (no usable assignment)
        // --------------------------------------------------------------

        private record Inferred(TypeMirror type, boolean guess) {
        }

        /**
         * Only member accesses can be inferred from demand; array element
         * types, primitive operand types and for-each element types can't.
         * {@code region} holds every use sharing the reaching assignment, so
         * several demands narrow the candidates together.
         */
        private Inferred inferFromDemand(List<Site> region, Site site, Scope pkg) {
            if (site.role() != Role.METHOD && site.role() != Role.FIELD) return null;
            Inferred agg = inferFrom(region, pkg);
            return agg != null ? agg : inferFrom(List.of(site), pkg);
        }

        private Inferred inferFrom(List<Site> demands, Scope pkg) {
            for (Site d : demands) {
                if (d.role() != Role.METHOD && d.role() != Role.FIELD) return null;
            }
            Set<String> fieldNames = demands.stream().filter(d -> d.role() == Role.FIELD)
                    .map(Site::member).collect(Collectors.toCollection(TreeSet::new));
            if (!fieldNames.isEmpty()) {
                TypeElement owner = uniqueFieldOwner(fieldNames);
                if (owner == null) return null;
                TypeMirror t = types.erasure(owner.asType());
                for (Site d : demands) if (!satisfies(t, d)) return null;
                return new Inferred(t, false);
            }
            // Methods only: which catalog types have all of them?
            List<TypeElement> matches = new ArrayList<>();
            for (String name : CATALOG) {
                TypeElement te = elements.getTypeElement(name);
                if (te == null) continue;
                boolean all = true;
                for (Site d : demands) all &= hasMethod(te, d.member(), d.arity());
                if (all) matches.add(te);
            }
            TypeElement widest = null;
            for (TypeElement w : matches) {
                boolean coversAll = true;
                for (TypeElement c : matches) {
                    coversAll &= types.isSubtype(types.erasure(c.asType()), types.erasure(w.asType()));
                }
                if (coversAll) {
                    widest = w;
                    break;
                }
            }
            if (widest == null) return null;
            // Non-String names (add, size, next...) are also declared by
            // library classes kept under their real names, so a single
            // generic method name is too weak; Strings' names are not.
            long distinct = demands.stream().map(d -> d.member() + "/" + d.arity()).distinct().count();
            if (!widest.getQualifiedName().contentEquals("java.lang.String") && distinct < 2) return null;
            return new Inferred(types.erasure(widest.asType()), true);
        }

        /** The one source-tree class declaring every named field, else null.
         *  Generated {@code fieldNNNN} names come from Stage 0's renamer, so
         *  a match is exact; ambiguity means "don't guess". */
        private TypeElement uniqueFieldOwner(Set<String> names) {
            Set<String> candidates = null;
            for (String name : names) {
                Set<String> owners = ownersOf(name);
                if (candidates == null) candidates = new TreeSet<>(owners);
                else candidates.retainAll(owners);
            }
            if (candidates == null || candidates.size() != 1) return null;
            return elements.getTypeElement(candidates.iterator().next());
        }

        private Set<String> ownersOf(String fieldName) {
            if (fieldOwners == null) fieldOwners = new HashMap<>();
            return fieldOwners.computeIfAbsent(fieldName, this::scanOwners);
        }

        /** Parse-only scan (no attribution) of the files mentioning the name. */
        private Set<String> scanOwners(String fieldName) {
            Set<String> owners = new TreeSet<>();
            Pattern word = Pattern.compile("\\b" + Pattern.quote(fieldName) + "\\b");
            List<Path> candidates = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(sourceRoot)) {
                for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".java"))::iterator) {
                    if (word.matcher(new String(Files.readAllBytes(p), StandardCharsets.UTF_8)).find()) {
                        candidates.add(p);
                    }
                }
            } catch (IOException e) {
                return owners;
            }
            if (candidates.isEmpty()) return owners;
            DiagnosticCollector<JavaFileObject> ignored = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(ignored, null, StandardCharsets.UTF_8)) {
                JavacTask parse = (JavacTask) compiler.getTask(null, fm, ignored, List.of("-proc:none"), null,
                        fm.getJavaFileObjectsFromPaths(candidates));
                for (CompilationUnitTree cu : parse.parse()) {
                    String pkg = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
                    for (Tree decl : cu.getTypeDecls()) {
                        if (decl instanceof ClassTree c && c.getSimpleName().length() > 0) {
                            indexClass(c, pkg.isEmpty() ? c.getSimpleName().toString()
                                    : pkg + "." + c.getSimpleName(), fieldName, owners);
                        }
                    }
                }
            } catch (IOException e) {
                return owners;
            }
            return owners;
        }

        private void indexClass(ClassTree cls, String fqn, String fieldName, Set<String> owners) {
            for (Tree member : cls.getMembers()) {
                if (member instanceof VariableTree v && v.getName().contentEquals(fieldName)) {
                    owners.add(fqn);
                } else if (member instanceof ClassTree inner && inner.getSimpleName().length() > 0) {
                    indexClass(inner, fqn + "." + inner.getSimpleName(), fieldName, owners);
                }
            }
        }

        // --------------------------------------------------------------
        // Rendering a type as source
        // --------------------------------------------------------------

        /** Fully-qualified source text for {@code t}, or null when it can't be
         *  written at this site (type variables, captures, anonymous or
         *  inaccessible classes) -- in which case the fix is simply skipped. */
        private String render(TypeMirror t, Scope from) {
            switch (t.getKind()) {
                case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE:
                    return t.toString();
                case ARRAY: {
                    String c = render(((ArrayType) t).getComponentType(), from);
                    return c == null ? null : c + "[]";
                }
                case DECLARED: {
                    DeclaredType dt = (DeclaredType) t;
                    TypeElement te = (TypeElement) dt.asElement();
                    if (!accessible(te, from)) return null;
                    String base = nameFor(te, from);
                    if (dt.getTypeArguments().isEmpty()) return base;
                    List<String> args = new ArrayList<>();
                    for (TypeMirror a : dt.getTypeArguments()) {
                        String r = renderArgument(a, from);
                        if (r == null) return null;
                        args.add(r);
                    }
                    return base + "<" + String.join(", ", args) + ">";
                }
                default:
                    return null;
            }
        }

        private String renderArgument(TypeMirror a, Scope from) {
            if (a.getKind() == TypeKind.WILDCARD) {
                WildcardType w = (WildcardType) a;
                if (w.getExtendsBound() != null) {
                    String b = render(w.getExtendsBound(), from);
                    return b == null ? null : "? extends " + b;
                }
                if (w.getSuperBound() != null) {
                    String b = render(w.getSuperBound(), from);
                    return b == null ? null : "? super " + b;
                }
                return "?";
            }
            return render(a, from);
        }

        /** What a file can legally write for a type: simple names resolve for
         *  java.lang, the file's own package, and explicit imports of that
         *  exact class; anything else (or anything a different same-named
         *  class could shadow) stays fully qualified. */
        private record Scope(PackageElement pkg, Map<String, String> importsBySimpleName) {
        }

        private Scope scopeOf(CompilationUnitTree cu) {
            Map<String, String> imports = new HashMap<>();
            for (ImportTree imp : cu.getImports()) {
                String name = imp.getQualifiedIdentifier().toString();
                if (imp.isStatic() || name.endsWith(".*")) continue;
                imports.put(name.substring(name.lastIndexOf('.') + 1), name);
            }
            String pkgName = cu.getPackageName() == null ? "" : cu.getPackageName().toString();
            PackageElement pkg = pkgName.isEmpty() ? elements.getPackageElement("")
                    : elements.getPackageElement(pkgName);
            return new Scope(pkg, imports);
        }

        private String nameFor(TypeElement te, Scope scope) {
            TypeElement top = te;
            while (top.getEnclosingElement() instanceof TypeElement outer) top = outer;
            String topFqn = top.getQualifiedName().toString();
            String simple = top.getSimpleName().toString();
            String nested = te.getQualifiedName().toString().substring(topFqn.length());
            String imported = scope.importsBySimpleName().get(simple);
            boolean useSimple;
            if (imported != null) {
                useSimple = imported.equals(topFqn);
            } else {
                PackageElement topPkg = elements.getPackageOf(top);
                boolean visible = topPkg.getQualifiedName().contentEquals("java.lang")
                        || topPkg.equals(scope.pkg());
                // A same-package class of the same simple name would shadow java.lang's.
                String ownPkg = scope.pkg() == null ? "" : scope.pkg().getQualifiedName().toString();
                TypeElement shadow = elements.getTypeElement(ownPkg.isEmpty() ? simple : ownPkg + "." + simple);
                useSimple = visible && (shadow == null || shadow.equals(top));
            }
            return (useSimple ? simple : topFqn) + nested;
        }

        private boolean accessible(TypeElement te, Scope from) {
            Element e = te;
            while (e instanceof TypeElement t) {
                if (t.getNestingKind() == NestingKind.ANONYMOUS || t.getNestingKind() == NestingKind.LOCAL) {
                    return false;
                }
                Set<Modifier> mods = t.getModifiers();
                if (mods.contains(Modifier.PRIVATE)) return false;
                if (!mods.contains(Modifier.PUBLIC) && !elements.getPackageOf(t).equals(from.pkg())) return false;
                e = t.getEnclosingElement();
            }
            return true;
        }
    }
}
