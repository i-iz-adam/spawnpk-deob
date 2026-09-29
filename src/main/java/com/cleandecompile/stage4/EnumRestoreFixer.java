package com.cleandecompile.stage4;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a class the decompiler printed as {@code class X extends Enum<X>}
 * back into an {@code enum}.
 *
 * <p>javac answers such a class with {@code classes cannot directly extend
 * java.lang.Enum}, and then, because {@code X} is no longer an enum, fails
 * every {@code switch} over it: {@code patterns in switch statements are not
 * supported in -source N} on the switch and {@code pattern or enum constant
 * required} on each {@code case}. One unsugared enum therefore costs an
 * error per case label in every file that switches on it, which is why this
 * is worth doing even though the trigger is a single diagnostic.
 *
 * <p>Decompilers give up on enum sugar when the compiler-generated members
 * no longer look canonical (renamed {@code values()}/{@code $VALUES}, a
 * dropped {@code ACC_ENUM}), and print what the bytecode literally says. That
 * output has two shapes, both handled:
 * <ul>
 *   <li><b>hidden-parameter form</b> (the literal bytecode): constants are
 *       {@code new X("NAME", 0, args...)}, the constructor takes
 *       {@code (String, int, args...)} and starts with
 *       {@code super(name, ordinal)};</li>
 *   <li><b>plain form</b>: constants are {@code new X(args...)} and the
 *       constructor has no {@code super} call.</li>
 * </ul>
 *
 * <h2>What is rewritten</h2>
 * <ul>
 *   <li>the header: {@code [mods] class X extends Enum<X> implements I}
 *       becomes {@code [mods] enum X implements I} ({@code final} and
 *       {@code abstract} are illegal on an enum and dropped);</li>
 *   <li>the leading run of constant fields becomes the constant list, in
 *       order, keeping the (decompiler-assigned) field names -- every use in
 *       the tree refers to them;</li>
 *   <li>hidden-form constructors lose their first two parameters and the
 *       {@code super}/{@code this} arguments that carried them; constructors
 *       may not be {@code public}/{@code protected} in an enum;</li>
 *   <li>members the enum syntax regenerates are removed only when they are
 *       exactly the canonical ones: {@code values()}, {@code valueOf(String)},
 *       the static block that fills {@code $VALUES}, and then the
 *       {@code $VALUES}/{@code $values()} helpers once nothing else uses
 *       them. Renamed copies are kept (callers use them) and, if their
 *       backing array lost its initializer, get {@code = new X[]{constants}}.</li>
 * </ul>
 *
 * <h2>Refusals</h2>
 * The class is left alone (the error is reported for a human) unless every
 * one of these holds: generics-free, the constants form one unbroken run at
 * the top of the body, no constant has its own body, the ordinals in the
 * hidden form are the literal positions, all constructors agree on hidden or
 * plain, hidden parameters are not otherwise used in the constructor, and any
 * member named like a generated one really is the canonical one.
 *
 * <p>Every edit keeps the file's line count, so the later line-based fixers
 * still see the line numbers of the diagnostics they were given.
 *
 * <p>Known behavioural difference: in the hidden form the original name
 * strings ({@code "NAME"}) are dropped, so {@code name()}/{@code toString()}
 * return the field identifier instead. Stage 0 has already renamed those
 * fields; code that depends on the obfuscated literal names would need the
 * literals kept, which enum syntax cannot express without a constructor
 * argument.
 */
final class EnumRestoreFixer implements SpanFixers.Planner {

    static final String NAME = "enumRestore";

    private static final String MESSAGE = "classes cannot directly extend java.lang.Enum";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean wants(String firstLine) {
        return firstLine.equals(MESSAGE);
    }

    @Override
    public boolean plan(SpanFixers.ParsedFile file, Diagnostic<? extends JavaFileObject> d,
                        List<SpanFixers.Edit> out) {
        TreePath path = file.findExact(d.getStartPosition(), d.getEndPosition(), t -> t instanceof ClassTree);
        if (path == null) return false;
        return new Plan(file, (ClassTree) path.getLeaf()).build(out);
    }

    // ------------------------------------------------------------------ plan

    private static final class Plan {
        final SpanFixers.ParsedFile f;
        final ClassTree cls;
        final String self;
        final String nl;
        final List<SpanFixers.Edit> edits = new ArrayList<>();

        Plan(SpanFixers.ParsedFile f, ClassTree cls) {
            this.f = f;
            this.cls = cls;
            this.self = cls.getSimpleName().toString();
            this.nl = f.text.contains("\r\n") ? "\r\n" : "\n";
        }

        boolean build(List<SpanFixers.Edit> out) {
            if (cls.getKind() != Tree.Kind.CLASS || !cls.getTypeParameters().isEmpty()) return false;
            if (cls.getExtendsClause() == null || !isEnumType(f.slice(cls.getExtendsClause()))) return false;

            List<? extends Tree> members = cls.getMembers();
            List<VariableTree> constants = new ArrayList<>();
            int i = 0;
            while (i < members.size() && members.get(i) instanceof VariableTree v && isConstant(v)) {
                constants.add(v);
                i++;
            }
            if (constants.isEmpty()) return false;
            List<Tree> rest = new ArrayList<>(members.subList(i, members.size()));
            for (Tree m : rest) {
                if (m instanceof VariableTree v && isConstant(v)) return false;     // run is not contiguous
            }

            // ---- constructors: hidden-parameter form or plain form, never a mix
            List<MethodTree> ctors = new ArrayList<>();
            for (Tree m : rest) {
                if (m instanceof MethodTree mt && mt.getName().contentEquals("<init>")) ctors.add(mt);
            }
            int hiddenCount = 0;
            for (MethodTree c : ctors) {
                Boolean hidden = isHiddenCtor(c);
                if (hidden == null) return false;
                if (hidden) hiddenCount++;
            }
            if (hiddenCount != 0 && hiddenCount != ctors.size()) return false;
            boolean hidden = hiddenCount > 0;

            // ---- constants
            List<String> constantNames = new ArrayList<>();
            for (int k = 0; k < constants.size(); k++) {
                VariableTree v = constants.get(k);
                NewClassTree nc = (NewClassTree) v.getInitializer();
                List<? extends ExpressionTree> args = nc.getArguments();
                if (hidden) {
                    if (args.size() < 2 || !isStringLiteral(args.get(0)) || !isIntLiteral(args.get(1), k)) {
                        return false;
                    }
                }
                constantNames.add(v.getName().toString());
                boolean last = k == constants.size() - 1;
                int drop = hidden ? 2 : 0;
                String text = constantText(v.getName().toString(), args, drop) + (last ? ";" : ",");
                if (!replace(v, text)) return false;
            }

            // ---- constructors
            for (MethodTree c : ctors) {
                if (!rewriteConstructor(c, hidden)) return false;
            }

            // ---- generated members
            if (!handleGenerated(rest, constants, constantNames)) return false;

            // ---- header
            if (!rewriteHeader()) return false;

            out.addAll(edits);
            return !edits.isEmpty();
        }

        // ------------------------------------------------------------ shapes

        private boolean isEnumType(String spelled) {
            if (spelled == null) return false;
            String s = spelled.replaceAll("\\s+", "");
            return s.matches("(?:java\\.lang\\.)?Enum(?:<" + Pattern.quote(self) + ">)?");
        }

        private boolean isConstant(VariableTree v) {
            if (!v.getModifiers().getFlags().contains(Modifier.STATIC)) return false;
            String type = f.slice(v.getType());
            if (type == null || !type.strip().equals(self)) return false;
            if (!(v.getInitializer() instanceof NewClassTree nc)) return false;
            if (nc.getClassBody() != null || nc.getEnclosingExpression() != null) return false;
            String id = f.slice(nc.getIdentifier());
            return id != null && id.strip().equals(self);
        }

        private static boolean isStringLiteral(ExpressionTree e) {
            return e instanceof LiteralTree l && l.getValue() instanceof String;
        }

        private static boolean isIntLiteral(ExpressionTree e, int expected) {
            return e instanceof LiteralTree l && l.getValue() instanceof Integer n && n == expected;
        }

        /** {@code true}/{@code false} for a hidden-form/plain constructor, {@code null} when unrecognised. */
        private Boolean isHiddenCtor(MethodTree c) {
            MethodInvocationTree call = explicitCall(c);
            List<? extends VariableTree> params = c.getParameters();
            if (call == null) return false;
            String target = call.getMethodSelect() instanceof IdentifierTree id ? id.getName().toString() : "";
            boolean twoHidden = params.size() >= 2 && typeIs(params.get(0), "String") && typeIs(params.get(1), "int");
            if (target.equals("super")) {
                return twoHidden && call.getArguments().size() == 2 ? Boolean.TRUE : null;
            }
            if (target.equals("this")) {
                if (!twoHidden || call.getArguments().size() < 2) return null;
                boolean forwards = call.getArguments().get(0) instanceof IdentifierTree a
                        && a.getName().contentEquals(params.get(0).getName())
                        && call.getArguments().get(1) instanceof IdentifierTree b
                        && b.getName().contentEquals(params.get(1).getName());
                return forwards ? Boolean.TRUE : null;
            }
            return false;
        }

        private boolean typeIs(VariableTree p, String simple) {
            String t = f.slice(p.getType());
            return t != null && (t.strip().equals(simple) || t.strip().equals("java.lang." + simple));
        }

        private static MethodInvocationTree explicitCall(MethodTree c) {
            if (c.getBody() == null || c.getBody().getStatements().isEmpty()) return null;
            StatementTree first = c.getBody().getStatements().get(0);
            if (first instanceof ExpressionStatementTree es && es.getExpression() instanceof MethodInvocationTree mi
                    && mi.getMethodSelect() instanceof IdentifierTree id
                    && (id.getName().contentEquals("super") || id.getName().contentEquals("this"))) {
                return mi;
            }
            return null;
        }

        // ------------------------------------------------------ constructors

        private boolean rewriteConstructor(MethodTree c, boolean hidden) {
            // Enum constructors are implicitly private.
            stripModifierWords(c.getModifiers(), Set.of("public", "protected"));
            if (!hidden) return true;

            List<? extends VariableTree> params = c.getParameters();
            String p0 = params.get(0).getName().toString();
            String p1 = params.get(1).getName().toString();
            MethodInvocationTree call = explicitCall(c);

            // The hidden parameters may only appear in the call that forwards them.
            long bodyStart = f.start(c.getBody());
            long bodyEnd = f.end(c.getBody());
            long callStart = f.start(call);
            long callEnd = f.end(call);
            String body = f.slice(bodyStart, callStart) + " " + f.slice(callEnd, bodyEnd);
            if (uses(body, p0) || uses(body, p1)) return false;

            // Parameters.
            long from = f.start(params.get(0));
            long to = params.size() > 2 ? f.start(params.get(2)) : f.end(params.get(1));
            if (!replace(from, to, "")) return false;

            // The super(...)/this(...) call.
            boolean isSuper = ((IdentifierTree) call.getMethodSelect()).getName().contentEquals("super");
            if (isSuper) {
                // The statement including its semicolon.
                StatementTree stmt = c.getBody().getStatements().get(0);
                if (!replace(stmt, "")) return false;
            } else {
                List<? extends ExpressionTree> args = call.getArguments();
                long a0 = f.start(args.get(0));
                long aEnd = args.size() > 2 ? f.start(args.get(2)) : f.end(args.get(1));
                if (!replace(a0, aEnd, "")) return false;
            }
            return true;
        }

        private static boolean uses(String text, String ident) {
            return Pattern.compile("(?<![\\w$])" + Pattern.quote(ident) + "(?![\\w$])").matcher(text).find();
        }

        // -------------------------------------------------- generated members

        private boolean handleGenerated(List<Tree> rest, List<VariableTree> constants, List<String> constantNames) {
            VariableTree arrayField = null;
            MethodTree values = null;
            MethodTree valueOf = null;
            List<MethodTree> valuesHelpers = new ArrayList<>();
            List<BlockTree> staticBlocks = new ArrayList<>();

            // Identify the backing array first; values() names it.
            for (Tree m : rest) {
                if (m instanceof MethodTree mt && isValuesShape(mt)) {
                    // Vineflower does not print the synthetic backing array at all, so a
                    // missing field is fine: values() is still exactly the canonical body.
                    String field = valuesField(mt);
                    for (Tree other : rest) {
                        if (other instanceof VariableTree v && v.getName().contentEquals(field)
                                && v.getModifiers().getFlags().contains(Modifier.STATIC)
                                && compact(f.slice(v.getType())).equals(self + "[]")) {
                            arrayField = v;
                        }
                    }
                    values = mt;
                } else if (m instanceof MethodTree mt && isValueOfShape(mt)) {
                    valueOf = mt;
                }
            }
            for (Tree m : rest) {
                if (m instanceof MethodTree mt) {
                    String n = mt.getName().toString();
                    boolean isValuesNamed = n.equals("values") && mt.getParameters().isEmpty();
                    boolean isValueOfNamed = n.equals("valueOf") && mt.getParameters().size() == 1;
                    if (isValuesNamed && mt != values) return false;      // a values() we do not understand
                    if (isValueOfNamed && mt != valueOf) return false;
                    if (isValuesHelperShape(mt)) valuesHelpers.add(mt);
                } else if (m instanceof BlockTree b && b.isStatic()) {
                    staticBlocks.add(b);
                }
            }

            Set<Tree> removed = new HashSet<>();
            if (values != null && values.getName().contentEquals("values")) removed.add(values);
            if (valueOf != null && valueOf.getName().contentEquals("valueOf")) removed.add(valueOf);

            // The static block(s) filling the array: removable only if that is all they do.
            for (BlockTree b : staticBlocks) {
                if (arrayField != null && onlyFills(b, arrayField.getName().toString())) removed.add(b);
            }

            // Helpers and the array itself go once nothing that stays refers to them.
            for (MethodTree helper : valuesHelpers) {
                if (!referencedByKept(rest, removed, helper, helper.getName().toString())) removed.add(helper);
            }
            if (arrayField != null && !referencedByKept(rest, removed, arrayField, arrayField.getName().toString())) {
                removed.add(arrayField);
            }

            for (Tree t : removed) {
                if (!replace(t, "")) return false;
            }

            // A kept array whose initialisation vanished with the static block needs one.
            if (arrayField != null && !removed.contains(arrayField) && arrayField.getInitializer() == null) {
                boolean stillAssigned = false;
                for (BlockTree b : staticBlocks) {
                    if (!removed.contains(b) && uses(f.slice(b), arrayField.getName().toString())) stillAssigned = true;
                }
                if (!stillAssigned) {
                    long end = f.end(arrayField);
                    int semi = (int) end - 1;
                    if (semi < 0 || f.text.charAt(semi) != ';') return false;
                    edits.add(new SpanFixers.Edit(semi, semi,
                            " = new " + self + "[]{" + String.join(", ", constantNames) + "}"));
                }
            }
            return true;
        }

        private boolean isStaticNoArg(MethodTree mt, String returnType) {
            return mt.getModifiers().getFlags().contains(Modifier.STATIC)
                    && mt.getParameters().isEmpty()
                    && mt.getReturnType() != null
                    && compact(f.slice(mt.getReturnType())).equals(returnType)
                    && mt.getBody() != null && mt.getBody().getStatements().size() == 1;
        }

        private static final Pattern VALUES_BODY = Pattern.compile("^(?:\\((?:java\\.lang\\.)?SELF\\[\\]\\))?(?:SELF\\.)?([\\w$]+)\\.clone\\(\\)$");

        private boolean isValuesShape(MethodTree mt) {
            return valuesField(mt) != null;
        }

        private String valuesField(MethodTree mt) {
            if (!isStaticNoArg(mt, self + "[]")) return null;
            if (!(mt.getBody().getStatements().get(0) instanceof ReturnTree r) || r.getExpression() == null) return null;
            Matcher m = Pattern.compile(VALUES_BODY.pattern().replace("SELF", Pattern.quote(self)))
                    .matcher(compact(f.slice(r.getExpression())));
            return m.matches() ? m.group(1) : null;
        }

        private boolean isValueOfShape(MethodTree mt) {
            if (!mt.getModifiers().getFlags().contains(Modifier.STATIC) || mt.getParameters().size() != 1
                    || mt.getReturnType() == null || !compact(f.slice(mt.getReturnType())).equals(self)
                    || !typeIs(mt.getParameters().get(0), "String")
                    || mt.getBody() == null || mt.getBody().getStatements().size() != 1) return false;
            if (!(mt.getBody().getStatements().get(0) instanceof ReturnTree r) || r.getExpression() == null) return false;
            String body = compact(f.slice(r.getExpression()));
            return body.matches("(?:\\(" + Pattern.quote(self) + "\\))?(?:java\\.lang\\.)?Enum\\.valueOf\\("
                    + Pattern.quote(self) + "\\.class,[\\w$]+\\)");
        }

        private boolean isValuesHelperShape(MethodTree mt) {
            if (!isStaticNoArg(mt, self + "[]")) return false;
            if (!(mt.getBody().getStatements().get(0) instanceof ReturnTree r) || r.getExpression() == null) return false;
            return compact(f.slice(r.getExpression())).matches("new" + Pattern.quote(self) + "\\[\\]\\{[\\w$,.]*\\}");
        }

        private boolean onlyFills(BlockTree block, String field) {
            if (block.getStatements().isEmpty()) return false;
            for (StatementTree s : block.getStatements()) {
                if (!(s instanceof ExpressionStatementTree es)) return false;
                String c = compact(f.slice(es));
                if (!c.matches("(?:" + Pattern.quote(self) + "\\.)?" + Pattern.quote(field) + "=.*;")) return false;
            }
            return true;
        }

        private boolean referencedByKept(List<Tree> rest, Set<Tree> removed, Tree subject, String ident) {
            for (Tree m : rest) {
                if (m == subject || removed.contains(m)) continue;
                String text = f.slice(m);
                if (text != null && uses(text, ident)) return true;
            }
            return false;
        }

        // ------------------------------------------------------------ header

        private boolean rewriteHeader() {
            ModifiersTree mods = cls.getModifiers();
            stripModifierWords(mods, Set.of("final", "abstract"));

            long clsStart = f.start(cls);
            long modsEnd = f.end(mods);
            int searchFrom = (int) (modsEnd > clsStart ? modsEnd : clsStart);
            Matcher kw = Pattern.compile("\\bclass\\b").matcher(f.text);
            if (!kw.find(searchFrom)) return false;
            int kwStart = kw.start();
            int afterKw = kw.end();

            long bodySearch = afterKw;
            bodySearch = Math.max(bodySearch, f.end(cls.getExtendsClause()));
            List<String> implemented = new ArrayList<>();
            for (Tree impl : cls.getImplementsClause()) {
                bodySearch = Math.max(bodySearch, f.end(impl));
                String s = f.slice(impl);
                if (s == null) return false;
                implemented.add(s.strip());
            }
            int brace = f.text.indexOf('{', (int) bodySearch);
            if (brace < 0) return false;

            String replacement = "enum " + self + (implemented.isEmpty() ? "" : " implements " + String.join(", ", implemented)) + " ";
            return replace(kwStart, brace, replacement);
        }

        // ---------------------------------------------------------- helpers

        private String constantText(String name, List<? extends ExpressionTree> args, int drop) {
            if (args.size() <= drop) return name;
            long from = f.start(args.get(drop));
            long to = f.end(args.get(args.size() - 1));
            return name + "(" + f.slice(from, to) + ")";
        }

        private void stripModifierWords(ModifiersTree mods, Set<String> words) {
            long s = f.start(mods);
            long e = f.end(mods);
            if (s < 0 || e <= s) return;
            List<long[]> annotations = new ArrayList<>();
            for (AnnotationTree a : mods.getAnnotations()) annotations.add(new long[]{f.start(a), f.end(a)});
            String slice = f.slice(s, e);
            Matcher m = Pattern.compile("(?<![\\w$])(?:" + String.join("|", words) + ")(?![\\w$])").matcher(slice);
            while (m.find()) {
                long from = s + m.start();
                long to = s + m.end();
                // Take the blanks after the word too (they may lie beyond the modifiers' span).
                while (to < f.text.length() && (f.text.charAt((int) to) == ' ' || f.text.charAt((int) to) == '\t')) to++;
                boolean inAnnotation = annotations.stream().anyMatch(a -> from >= a[0] && from < a[1]);
                if (!inAnnotation) edits.add(new SpanFixers.Edit((int) from, (int) to, ""));
            }
        }

        private boolean replace(Tree tree, String text) {
            return replace(f.start(tree), f.end(tree), text);
        }

        /** Replaces {@code [from, to)}, padding with newlines so the file keeps its line count. */
        private boolean replace(long from, long to, String text) {
            String original = f.slice(from, to);
            if (original == null) return false;
            long lost = original.chars().filter(c -> c == '\n').count()
                    - text.chars().filter(c -> c == '\n').count();
            StringBuilder padded = new StringBuilder(text);
            for (long k = 0; k < lost; k++) padded.append(nl);
            edits.add(new SpanFixers.Edit((int) from, (int) to, padded.toString()));
            return true;
        }

        private static String compact(String s) {
            return s == null ? "" : s.replaceAll("\\s+", "");
        }
    }
}
