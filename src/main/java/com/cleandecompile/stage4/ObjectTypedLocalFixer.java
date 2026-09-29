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
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.LambdaExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.tree.WhileLoopTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ExecutableType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeVariable;
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
 *   <li>{@code incompatible types: Object cannot be converted to X}
 *       (an {@code Object} local passed where the callee demands {@code X})</li>
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
                    + "|bad operand types for binary operator[\\s\\S]*(?:first|second) type:\\s+(?:java\\.lang\\.)?Object\\b"
                    + "|incompatible types: (?:java\\.lang\\.)?Object cannot be converted to ");
    private static final Pattern BAD_OPERAND = Pattern.compile("bad operand types for binary operator");
    /** An {@code Object} where the callee demands a specific type. The source
     *  type has to be the bare {@code Object}: {@code Consumer<T> cannot be
     *  converted to Consumer<Object>} is a generics mismatch, not a widened
     *  local. */
    private static final Pattern CONVERTED = Pattern.compile(
            "incompatible types: (?:java\\.lang\\.)?Object cannot be converted to ");

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

    /**
     * What a use of an Object-typed variable demands.
     *
     * <p>The first six are the shapes javac reports, so a per-use cast can
     * serve each of them. The last four only ever appear in {@link VarInfo#uses}
     * -- they are legal with an {@code Object} declaration and can stop being
     * legal the moment it is narrowed, so the retype gate has to judge them.
     * {@link #OTHER} is the "cannot classify" verdict, and it blocks.
     */
    private enum Role { ARRAY, ARRAY_INDEX, ITERATE, METHOD, FIELD, PRIMITIVE, PASS,
                       PRIMITIVE_ARGUMENT, CAST, INSTANCEOF, COMPARISON, CONCAT, RETURN, OTHER }

    /** One mention of an Object-typed variable, and the demand it makes.
     *  {@code required} carries the type a {@link Role#PASS} or
     *  {@link Role#CAST} or {@link Role#INSTANCEOF} use needs. */
    private record Site(TreePath path, String var, int start, int end, int line,
                        Role role, String member, int arity, TypeMirror required) {
    }

    /** One assignment (or initializer) of the variable. */
    private record Def(int offset, int stmtStart, TreePath rhs, Tree block, boolean conditional) {
    }

    private static final class VarInfo {
        final VariableElement element;
        VariableTree decl;
        TreePath declPath;
        final List<Def> defs = new ArrayList<>();
        /** The uses javac complained about: the ones a cast can serve, and the
         *  only ones that justify a retype at all. */
        final List<Site> sites = new ArrayList<>();
        /** Every use, flagged or not. A retype is judged against this, since a
         *  use that compiled only because the declaration said Object would
         *  otherwise become a fresh error nothing is watching for. */
        final List<Site> uses = new ArrayList<>();

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
        /** Stands in for "this call's parameter types are unknown", so the
         *  cache can record a miss. */
        private static final TypeMirror[] NO_PARAMS = new TypeMirror[0];
        private final Map<String, TypeMirror[]> parameterCache = new HashMap<>();
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
                    Set<Integer> binaryLines = new HashSet<>();
                    for (var d : diags) {
                        if (!BAD_OPERAND.matcher(d.getMessage(Locale.ENGLISH)).find()) continue;
                        int at = (int) d.getLineNumber();
                        binaryLines.add(at);
                        binaryLines.add(at + 1); // wrapped call chains report the next line
                    }
                    Set<Integer> passLines = new HashSet<>();
                    for (var d : diags) {
                        if (!CONVERTED.matcher(d.getMessage(Locale.ENGLISH)).find()) continue;
                        int at = (int) d.getLineNumber();
                        passLines.add(at);
                        passLines.add(at + 1); // wrapped call chains report the next line
                    }
                    FilePlanner planner = new FilePlanner(cu, binaryLines, passLines);
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
            private final Set<Integer> passLines;
            private final Map<VariableElement, VarInfo> vars = new LinkedHashMap<>();
            private final Set<Tree> methodSelects = Collections.newSetFromMap(new IdentityHashMap<>());
            /** Every line whose flagged use was addressed by an edit. */
            final Set<Integer> coveredLines = new HashSet<>();
            private final Scope scope;

            FilePlanner(CompilationUnitTree cu, Set<Integer> binaryLines, Set<Integer> passLines) {
                this.cu = cu;
                this.binaryLines = binaryLines;
                this.passLines = passLines;
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
                    site(getCurrentPath(), node.getExpression(), Role.ARRAY, null, 0, null);
                    return super.visitArrayAccess(node, p);
                }

                @Override
                public Void visitEnhancedForLoop(EnhancedForLoopTree node, Void p) {
                    site(getCurrentPath(), node.getExpression(), Role.ITERATE, null, 0, null);
                    return super.visitEnhancedForLoop(node, p);
                }

                @Override
                public Void visitMethodInvocation(MethodInvocationTree node, Void p) {
                    if (node.getMethodSelect() instanceof MemberSelectTree ms) {
                        methodSelects.add(ms);
                        String name = ms.getIdentifier().toString();
                        if (!objectMethodNames.contains(name)) {
                            site(new TreePath(getCurrentPath(), ms), ms.getExpression(), Role.METHOD,
                                    name, node.getArguments().size(), null);
                        }
                    }
                    arguments(getCurrentPath(), node);
                    return super.visitMethodInvocation(node, p);
                }

                /** An {@code Object} local can also be wrong where there is no
                 *  member select on it at all: handed to a call that demands a
                 *  concrete type ({@code map.put(key, object)}). */
                private void arguments(TreePath invocation, MethodInvocationTree node) {
                    List<? extends ExpressionTree> args = node.getArguments();
                    boolean identifierArgument = false;
                    for (ExpressionTree arg : args) {
                        if (arg instanceof IdentifierTree) {
                            identifierArgument = true;
                            break;
                        }
                    }
                    if (!identifierArgument) return;
                    TypeMirror[] required = parameterTypes(invocation, node);
                    if (required == null) return;
                    for (int i = 0; i < args.size() && i < required.length; i++) {
                        if (required[i] == null) continue;
                        // An int parameter is not a demand a reference type can
                        // ever meet, so treating it as one would veto the whole
                        // declaration over an argument the retype does not
                        // concern. Role.PRIMITIVE owns the unboxing instead.
                        if (required[i].getKind().isPrimitive()) continue;
                        site(invocation, args.get(i), Role.PASS, null, 0, required[i]);
                    }
                }

                @Override
                public Void visitMemberSelect(MemberSelectTree node, Void p) {
                    if (!methodSelects.contains(node)) {
                        String name = node.getIdentifier().toString();
                        if (name.equals("length")) {
                            site(getCurrentPath(), node.getExpression(), Role.ARRAY, null, 0, null);
                        } else if (!name.equals("class") && !name.equals("this") && !name.equals("super")) {
                            site(getCurrentPath(), node.getExpression(), Role.FIELD, name, 0, null);
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

                @Override
                public Void visitIdentifier(IdentifierTree node, Void p) {
                    TreePath path = getCurrentPath();
                    VarInfo v = objectVar(path);
                    if (v != null) use(v, path);
                    return super.visitIdentifier(node, p);
                }

                /**
                 * Classifies one occurrence for the retype gate. Deliberately
                 * separate from {@link #site}: a site is a demand javac
                 * reported and a cast can answer, while a use is every mention
                 * of the variable, and anything this cannot classify becomes
                 * {@link Role#OTHER} and blocks the retype. Guessing here would
                 * be the whole defect: the retype changes the static type of
                 * every mention, not just the reported one.
                 */
                private void use(VarInfo v, TreePath path) {
                    TreePath parent = path.getParentPath();
                    if (parent == null) return;
                    Tree leaf = parent.getLeaf();
                    if (leaf instanceof AssignmentTree a && a.getVariable() == path.getLeaf()) {
                        return; // a definition, judged as a def rather than a use
                    }
                if (leaf instanceof TypeCastTree cast) {
                    record(v, path, Role.CAST, null, 0, typeOf(parent, cast.getType()));
                } else if (leaf instanceof InstanceOfTree io && io.getExpression() == path.getLeaf()) {
                    record(v, path, Role.INSTANCEOF, null, 0, typeOf(parent, io.getType()));
                } else if (leaf instanceof EnhancedForLoopTree loop && loop.getExpression() == path.getLeaf()) {
                    record(v, path, Role.ITERATE, null, 0, null);
                } else if (leaf instanceof ArrayAccessTree access) {
                    if (access.getExpression() == path.getLeaf()) {
                        record(v, path, Role.ARRAY, null, 0, null);
                    } else {
                        // An index is int-convertible by definition (JLS 15.15.1),
                        // whatever type it happens to be written with.
                        record(v, path, Role.ARRAY_INDEX, null, 0,
                                types.getPrimitiveType(TypeKind.INT));
                    }
                } else if (leaf instanceof BinaryTree binary) {
                    operandUse(v, path, binary);
                } else if (leaf instanceof ReturnTree) {
                    record(v, path, Role.RETURN, null, 0, enclosingReturnType(parent));
                } else if (leaf instanceof MethodInvocationTree call) {
                    argumentUse(v, path, call);
                } else if (leaf instanceof MemberSelectTree ms) {
                    memberUse(v, path, ms, parent.getParentPath());
                } else {
                    // A ternary arm, an initializer, a field assignment: legal
                    // with Object, and narrowing can change which type it means.
                    record(v, path, Role.OTHER, null, 0, null);
                }
            }

                /**
                 * A binary operand. Only two of the shapes have a decidable
                 * meaning after narrowing: a comparison, which needs the two
                 * sides comparable, and a concatenation, which string-converts
                 * whatever it is given. Anything else is arithmetic, whose
                 * promotion rules are not worth reasoning about here.
                 */
                private void operandUse(VarInfo v, TreePath path, BinaryTree binary) {
                    ExpressionTree other = binary.getLeftOperand() == path.getLeaf()
                            ? binary.getRightOperand() : binary.getLeftOperand();
                    TypeMirror otherType = staticType(new TreePath(path.getParentPath(), other));
                    if (otherType == null) {
                        record(v, path, Role.OTHER, null, 0, null);
                        return;
                    }
                    if (otherType.getKind().isPrimitive()) {
                        // Unboxing is the only way a reference takes part, and
                        // that is the question Role.PRIMITIVE answers.
                        record(v, path, Role.PRIMITIVE, null, 0, null);
                        return;
                    }
                    if (binary.getKind() == Tree.Kind.PLUS && isString(otherType)) {
                        record(v, path, Role.CONCAT, null, 0, null);
                        return;
                    }
                    if (binary.getKind() == Tree.Kind.EQUAL_TO || binary.getKind() == Tree.Kind.NOT_EQUAL_TO
                            || binary.getKind() == Tree.Kind.LESS_THAN
                            || binary.getKind() == Tree.Kind.GREATER_THAN
                            || binary.getKind() == Tree.Kind.LESS_THAN_EQUAL
                            || binary.getKind() == Tree.Kind.GREATER_THAN_EQUAL) {
                        record(v, path, Role.COMPARISON, null, 0, otherType);
                        return;
                    }
                    record(v, path, Role.OTHER, null, 0, null);
            }

                private void argumentUse(VarInfo v, TreePath path, MethodInvocationTree call) {
                    List<? extends ExpressionTree> args = call.getArguments();
                    int index = -1;
                    for (int i = 0; i < args.size(); i++) {
                        if (args.get(i) == path.getLeaf()) {
                            index = i;
                            break;
                        }
                    }
                    if (index < 0) return;
                    TypeMirror[] required = parameterTypes(path.getParentPath(), call);
                    if (required == null || index >= required.length || required[index] == null) {
                        record(v, path, Role.OTHER, null, 0, null); // an unresolved demand is no demand
                        return;
                    }
                    if (required[index].getKind().isPrimitive()) {
                        // Unboxing is Role.PRIMITIVE's to own, and no reference
                        // type can ever satisfy it -- so it must not veto the
                        // declaration as a whole.
                        record(v, path, Role.PRIMITIVE_ARGUMENT, null, 0, null);
                        return;
                    }
                    record(v, path, Role.PASS, null, 0, required[index]);
                }

                private void memberUse(VarInfo v, TreePath path, MemberSelectTree ms, TreePath grand) {
                    String name = ms.getIdentifier().toString();
                    // Declared by Object, so it exists on every type and can
                    // never be what breaks.
                    if (objectMethodNames.contains(name)) return;
                    if (name.equals("length")) {
                        record(v, path, Role.ARRAY, null, 0, null);
                        return;
                    }
                    if (name.equals("class") || name.equals("this") || name.equals("super")) return;
                    if (grand != null && grand.getLeaf() instanceof MethodInvocationTree call
                            && call.getMethodSelect() == ms) {
                        record(v, path, Role.METHOD, name, call.getArguments().size(), null);
                        return;
                    }
                    record(v, path, Role.FIELD, name, 0, null);
                }

                private void record(VarInfo v, TreePath path, Role role, String member, int arity,
                                    TypeMirror required) {
                    Tree leaf = path.getLeaf();
                    if (!(leaf instanceof IdentifierTree id)) return;
                    long start = positions.getStartPosition(cu, leaf);
                    long end = positions.getEndPosition(cu, leaf);
                    if (start < 0 || end < 0) return;
                    v.uses.add(new Site(path, id.getName().toString(), (int) start, (int) end,
                            (int) cu.getLineMap().getLineNumber(start), role, member, arity, required));
                }

                private TypeMirror typeOf(TreePath parent, Tree type) {
                    return staticType(new TreePath(parent, type));
                }

                /** The enclosing method's declared return type: the demand a
                 *  {@code return v;} makes. */
                private TypeMirror enclosingReturnType(TreePath path) {
                    for (TreePath p = path; p != null; p = p.getParentPath()) {
                        Tree t = p.getLeaf();
                        if (t instanceof ClassTree) return null;
                        if (t instanceof MethodTree m && m.getReturnType() != null) {
                            return typeOf(p, m.getReturnType());
                        }
                    }
                    return null;
                }

                private void operand(ExpressionTree candidate, ExpressionTree other) {
                    if (!(candidate instanceof IdentifierTree)) return;
                    TypeMirror ot = trees.getTypeMirror(new TreePath(getCurrentPath(), other));
                    if (ot == null || !ot.getKind().isPrimitive()) return;
                    // Only where javac actually rejected it: some Object/primitive
                    // comparisons are accepted, and casting those would change meaning.
                    site(getCurrentPath(), candidate, Role.PRIMITIVE, null, 0, null);
                }

                /** Records a use of {@code expr} when it is an Object-typed
                 *  local/parameter (identifier only -- no chains). {@code parent}
                 *  is the path of the node whose direct child {@code expr} is.
                 *  {@code required} is the type the use demands, for
                 *  {@link Role#PASS}. */
                private void site(TreePath parent, ExpressionTree expr, Role role, String member, int arity,
                                  TypeMirror required) {
                    if (!(expr instanceof IdentifierTree id)) return;
                    VarInfo v = objectVar(new TreePath(parent, expr));
                    if (v == null) return;
                    long start = positions.getStartPosition(cu, id);
                    long end = positions.getEndPosition(cu, id);
                    if (start < 0 || end < 0) return;
                    int line = (int) cu.getLineMap().getLineNumber(start);
                    if (role == Role.PRIMITIVE && !binaryLines.contains(line)) return;
                    // Likewise for a passed argument: an Object where any
                    // supertype is wanted is legal plenty of the time, and an
                    // unflagged line says nothing about what was demanded.
                    if (role == Role.PASS && !passLines.contains(line)) return;
                    v.sites.add(new Site(new TreePath(parent, expr), id.getName().toString(),
                            (int) start, (int) end, line, role, member, arity, required));
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
                Map<Site, TypeMirror> sourceOf = new IdentityHashMap<>();
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
                        if (t != null) {
                            resolved.put(s, t);
                            sourceOf.put(s, reaching == null ? null : reaching.type());
                        }
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
                    if (t.getKind().isPrimitive() && !boxedSource(sourceOf.get(s))) continue;
                    edits.add(new Edit(s.start(), s.end(), s.var(), "((" + src + ") " + s.var() + ")"));
                    coveredLines.add(s.line());
                    if (guessed.contains(s)) {
                        guessLog.add(pathOf(cu.getSourceFile()).getFileName() + ":" + s.line()
                                + " " + s.var() + " -> " + src);
                    }
                }
            }

            /**
             * Whether the assignment reaching a use put a boxed value in the
             * slot. It always did: the JVM verifier types an interface value
             * as Object, and the source's {@code Object v = 1} boxes on the
             * way in. So a cast to a primitive compiles and then throws
             * ClassCastException -- unless the value really is the boxed type,
             * which is the one case where the cast is safe.
             */
            private boolean boxedSource(TypeMirror def) {
                return def != null && def.getKind() == TypeKind.DECLARED;
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
                TypeMirror declared = declarationType(common);
                if (declared == null) return null;
                // Reference types only: retyping to a primitive would drop
                // boxing (== identity). char[] only: overloads like
                // append/valueOf/println treat it differently from Object.
                // A type variable stands for whatever it was instantiated with,
                // so it boxes and compares no worse than Object did.
                boolean referenceType = switch (declared.getKind()) {
                    case DECLARED, ARRAY, TYPEVAR -> true;
                    default -> false;
                };
                if (!referenceType) return null;
                if (declared.getKind() == TypeKind.ARRAY
                        && ((ArrayType) declared).getComponentType().getKind() == TypeKind.CHAR) {
                    return null;
                }
                for (TypedDef d : defs) {
                    if (d.type() == null || !types.isAssignable(d.type(), declared)) return null;
                }
                // The decision above only ever saw the uses javac reported.
                // A retype changes the static type of EVERY mention, so one
                // that compiled only because the declaration said Object --
                // an instanceof against a final class, a cast between two
                // final types, an argument position the collectors never
                // reach -- would turn into a fresh error. Any such use blocks
                // the retype and leaves the declaration alone, which is safe:
                // the per-use cast path below can still fix what was reported.
                for (Site use : v.uses) {
                    if (!satisfies(declared, use)) return null;
                }
                return declarationText(declared, pkg);
            }

            /** A type every typed assignment is assignable to and that has what
             *  every flagged use needs; null when there is none (an untyped
             *  assignment, unrelated types, or a use the type cannot serve).
             *  Candidates are compared as the type a declaration would
             *  spell, so a wildcard capture is weighed by the type variable
             *  bounding it. */
            private TypeMirror commonOfDefs(List<TypedDef> defs, List<Site> sites) {
                if (defs.isEmpty() || sites.isEmpty()) return null;
                for (TypedDef d : defs) {
                    if (d.type() == null) return null;
                }
                for (TypedDef candidate : defs) {
                    TypeMirror t = declarationType(candidate.type());
                    if (t == null) continue;
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

        /**
         * The parameter types the callee demands, indexed by argument
         * position, or null when they cannot be pinned down.
         *
         * <p>Not via the method select: on a call javac has already rejected
         * the select carries a synthetic error symbol (a {@code ClassSymbol}
         * named after the method), not the real one. So the callee is found
         * by name and arity among the receiver's members instead -- and only
         * when exactly one such method exists, since a demand that does not
         * match the real signature would make an unsound fix look sound.
         */
        private TypeMirror[] parameterTypes(TreePath invocation, MethodInvocationTree node) {
            int arity = node.getArguments().size();
            if (arity == 0) return null;
            ExpressionTree select = node.getMethodSelect();
            if (!(select instanceof MemberSelectTree ms)) return null; // an implicit receiver names no owner
            TypeMirror receiver = staticType(new TreePath(new TreePath(invocation, select), ms.getExpression()));
            if (!(receiver instanceof DeclaredType owner)) return null;
            // The owner is spelled out, type arguments included: List<String>.add
            // and List<Integer>.add are the same declaration but substitute to
            // different demands, so the name alone would answer for both.
            String key = owner + "#" + ms.getIdentifier() + "/" + arity;
            TypeMirror[] cached = parameterCache.get(key);
            if (cached == null) {
                cached = resolveParameterTypes(owner, ms.getIdentifier().toString(), arity);
                parameterCache.put(key, cached);
            }
            return cached == NO_PARAMS ? null : cached;
        }

        private TypeMirror[] resolveParameterTypes(DeclaredType owner, String name, int arity) {
            ExecutableElement match = uniqueMethod((TypeElement) owner.asElement(), name, arity);
            if (match == null) return NO_PARAMS;
            List<? extends TypeMirror> params;
            try {
                params = ((ExecutableType) types.asMemberOf(owner, match)).getParameterTypes();
            } catch (RuntimeException e) {
                return NO_PARAMS;
            }
            if (params.isEmpty()) return NO_PARAMS;
            int slots = match.isVarArgs() ? params.size() + 1 : params.size();
            TypeMirror[] out = new TypeMirror[slots];
            for (int i = 0; i < params.size(); i++) {
                TypeMirror p = params.get(i);
                // A trailing varargs slot is fed the component type, not the array.
                if (match.isVarArgs() && i == params.size() - 1 && p instanceof ArrayType array) {
                    p = array.getComponentType();
                }
                out[i] = p != null && p.getKind() != TypeKind.ERROR ? p : null;
            }
            if (match.isVarArgs()) out[slots - 1] = out[params.size() - 1];
            return out;
        }

        /** The one method of that name and arity the type declares, or null
         *  when there is none or the choice would be a guess. */
        private ExecutableElement uniqueMethod(TypeElement owner, String name, int arity) {
            ExecutableElement match = null;
            for (ExecutableElement candidate : ElementFilter.methodsIn(elements.getAllMembers(owner))) {
                if (!candidate.getSimpleName().contentEquals(name)) continue;
                int n = candidate.getParameters().size();
                if (n != arity && !(candidate.isVarArgs() && arity >= n - 1)) continue;
                if (match != null) return null;
                match = candidate;
            }
            return match;
        }

        /**
         * The type a local can be declared with, or null when nothing at this
         * site names it. javac reports a wildcard capture as a TYPEVAR, but a
         * capture is not a name: what a declaration may write is its single
         * direct supertype when that is a type variable --
         * {@code List<? extends T>.get()} returns {@code capture#N of ? extends T},
         * and {@code T x = list.get(i)} is exactly what javac accepts, because
         * a capture is a subtype of its upper bound. A real type variable is
         * already its own name; a capture of anything else (an unbounded
         * wildcard, say) has none and is left alone.
         */
        private TypeMirror declarationType(TypeMirror t) {
            if (t == null) return null;
            if (t.getKind() != TypeKind.TYPEVAR) return t;
            if (isTypeVariable(t)) return t;
            try {
                List<? extends TypeMirror> supers = types.directSupertypes(t);
                if (supers.size() == 1 && isTypeVariable(supers.get(0))) return supers.get(0);
            } catch (RuntimeException e) {
                return null;
            }
            return null;
        }

        private boolean isTypeVariable(TypeMirror t) {
            try {
                // javac hands back a placeholder for a capture, so "spells
                // like a type variable" is what separates the two -- not an
                // instanceof test, which both pass.
                return t instanceof TypeVariable tv && tv.asElement() instanceof TypeParameterElement tp
                        && SourceVersion.isIdentifier(tp.getSimpleName());
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** A type that carries information and could stand in a declaration. */
        private boolean usable(TypeMirror t) {
            if (t == null) return false;
            return switch (t.getKind()) {
                case ARRAY, DECLARED -> !isObject(t);
                case BOOLEAN, BYTE, SHORT, INT, LONG, CHAR, FLOAT, DOUBLE -> true;
                case TYPEVAR -> declarationType(t) != null;
                default -> false; // ERROR (cascade), NULL, VOID, WILDCARD, INTERSECTION, ...
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
                case PASS -> s.required() != null && types.isAssignable(t, s.required());
                case PRIMITIVE_ARGUMENT -> true; // the demand is Role.PRIMITIVE's
                case CAST -> castable(t, s.required());
                case INSTANCEOF -> instanceOfOk(t, s.required());
                case COMPARISON -> s.required() != null
                        && (types.isAssignable(t, s.required()) || types.isAssignable(s.required(), t));
                // JLS 15.18.1: string conversion accepts a value of any type,
                // so narrowing the operand cannot make the concatenation stop
                // compiling -- nor change the String it produces.
                case CONCAT -> true;
                case RETURN -> s.required() != null && types.isAssignable(t, s.required());
                case ARRAY_INDEX -> s.required() != null && unboxesTo(t, s.required());
                case OTHER -> false; // unclassified: refuse to narrow
            };
        }

        private boolean isString(TypeMirror t) {
            return t.getKind() == TypeKind.DECLARED
                    && ((TypeElement) ((DeclaredType) t).asElement())
                    .getQualifiedName().contentEquals("java.lang.String");
        }

        /**
         * Whether {@code t instanceof target} still compiles. javac asks
         * whether the two types are downcast-compatible (JLS 5.1.6), so
         * related in either direction is enough -- and otherwise a final
         * class on <i>either</i> side is fatal, because no type can then be
         * both: {@code arrayList instanceof String} is rejected by String
         * being final even though ArrayList is not, and {@code int[] instanceof
         * String} for the same reason.
         */
        private boolean instanceOfOk(TypeMirror t, TypeMirror target) {
            if (t == null || target == null) return false;
            if (types.isAssignable(t, target) || types.isAssignable(target, t)) return true;
            return !isFinal(t) && !isFinal(target);
        }

        /** Final as a <i>class</i>: an array and a type variable are never one,
         *  so neither can rule a subtype in or out. */
        private boolean isFinal(TypeMirror t) {
            if (t.getKind() != TypeKind.DECLARED) return false;
            return ((TypeElement) ((DeclaredType) t).asElement()).getModifiers().contains(Modifier.FINAL);
        }

        /** Whether {@code t} can stand for a value of the primitive {@code p}.
         *  {@code unboxedType} is the exact inverse of what is being asked, so
         *  an Integer serves an int index and a Double serves neither it nor
         *  any other integral one. */
        private boolean unboxesTo(TypeMirror t, TypeMirror p) {
            if (t == null || p == null) return false;
            if (t.getKind() == p.getKind()) return true;
            if (t.getKind() != TypeKind.DECLARED || !p.getKind().isPrimitive()) return false;
            try {
                TypeMirror unboxed = types.unboxedType(t);
                return unboxed != null && unboxed.getKind() == p.getKind();
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        /**
         * Whether {@code (target) t} still compiles once the declaration
         * carries {@code t}. JLS 5.5 lets any reference be cast to an
         * interface, and one reference type to another when either is a
         * subtype of the other; two unrelated final types are the pair that
         * stops compiling.
         */
        private boolean castable(TypeMirror t, TypeMirror target) {
            if (t == null || target == null) return false;
            if (target.getKind() != TypeKind.DECLARED) return false; // a type variable or wildcard is not a cast target
            if (types.isAssignable(t, target) || types.isAssignable(target, t)) return true;
            return ((TypeElement) ((DeclaredType) target).asElement()).getKind() == ElementKind.INTERFACE;
        }

        private boolean unboxes(TypeMirror t) {
            // JLS 5.1.8 unboxes a boxed type. A type variable stands for
            // whatever it was instantiated with, so unboxedType would answer
            // for one of those, not for T.
            if (t.getKind() != TypeKind.DECLARED) return false;
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
         *  inaccessible classes) -- in which case the fix is simply skipped.
         *  A type variable is nameable only where a declaration may use one,
         *  hence {@link #declarationText}. */
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

        /** Source text for a declaration's type. The only case {@link #render}
         *  turns down that source can spell is a type variable, which is
         *  exactly what a capture of a bounded wildcard has to be declared as. */
        private String declarationText(TypeMirror t, Scope from) {
            if (t.getKind() == TypeKind.TYPEVAR && isTypeVariable(t)) {
                return ((TypeVariable) t).asElement().getSimpleName().toString();
            }
            return render(t, from);
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
