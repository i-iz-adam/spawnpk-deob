package com.cleandecompile.stage4;

import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs generic type-argument mismatches that only exist in the source.
 *
 * <p>Generics are erased before bytecode, so a decompiler reconstructing
 * type arguments from the constant pool and stack maps regularly picks
 * arguments that are individually plausible and jointly inconsistent:
 * <pre>
 *   incompatible types: Consumer&lt;T&gt; cannot be converted to Consumer&lt;Object&gt;
 *   no suitable method found for bind(Class&lt;capture#1 of ? extends Plugin&gt;)
 *       ... argument mismatch; Class&lt;capture#2 of ? extends Plugin&gt; cannot be
 *       converted to Class&lt;Plugin&gt;
 * </pre>
 * Both sides are the same raw type, so the cast {@code (Target) (Raw) expr}
 * is a no-op at runtime: the intermediate raw cast is always legal (an
 * unchecked warning at most) and the outer cast then merely relabels the
 * type arguments. It is exactly the escape hatch a human would use.
 *
 * <p>Guard rails, because the cast is emitted as source text:
 * <ul>
 *   <li>only when source and target share the raw type -- a different raw
 *       type is a real type error, not an argument mismatch;</li>
 *   <li>the target must be spelled with fully qualified names only (javac
 *       prints type variables bare, and one that is not in scope at the
 *       flagged expression would not compile);</li>
 *   <li>the target must not itself contain a capture;</li>
 *   <li>for the {@code no suitable method} form, exactly one argument and
 *       exactly one target type may qualify -- otherwise which argument to
 *       cast is a guess.</li>
 * </ul>
 */
final class GenericCastFixer implements SpanFixers.Planner {

    static final String NAME = "genericCast";

    private static final Pattern CONVERSION = Pattern.compile(
            "^incompatible types: (.+<.*>) cannot be converted to (.+<.*>)$");
    private static final Pattern NO_SUITABLE = Pattern.compile(
            "^no suitable method found for ([\\w$]+)\\((.*)\\)$");
    private static final Pattern MISMATCH = Pattern.compile(
            "\\(argument mismatch; (.+?) cannot be converted to (.+?)\\)\\s*$", Pattern.MULTILINE);
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z_$][\\w$.]*");
    private static final Set<String> TYPE_KEYWORDS = Set.of("extends", "super");
    private static final Pattern CAPTURE_INDEX = Pattern.compile("capture#\\d+");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean wants(String firstLine) {
        return (firstLine.startsWith("incompatible types: ") && firstLine.contains("<")
                && firstLine.contains(" cannot be converted to "))
                || (firstLine.startsWith("no suitable method found for ") && firstLine.contains("capture#"));
    }

    @Override
    public boolean plan(SpanFixers.ParsedFile file, Diagnostic<? extends JavaFileObject> d,
                        List<SpanFixers.Edit> out) {
        String message = d.getMessage(Locale.ENGLISH);
        String first = SpanFixers.firstLine(message);
        long start = d.getStartPosition();
        long end = d.getEndPosition();

        Matcher conversion = CONVERSION.matcher(first);
        if (conversion.matches()) {
            String source = conversion.group(1).strip();
            String target = conversion.group(2).strip();
            if (!castable(source, target)) return false;
            TreePath path = file.findExact(start, end, GenericCastFixer::castableExpression);
            if (path == null) return false;
            return cast(file, path, rawOf(target), target, out);
        }

        Matcher noSuitable = NO_SUITABLE.matcher(first);
        if (noSuitable.matches()) {
            return noSuitableMethod(file, start, end, noSuitable.group(2), message, out);
        }
        return false;
    }

    private boolean noSuitableMethod(SpanFixers.ParsedFile file, long start, long end, String actualArgs,
                                     String message, List<SpanFixers.Edit> out) {
        List<String> actual = splitTopLevel(actualArgs);
        if (actual.isEmpty()) return false;

        // Candidates whose only complaint is capture-vs-concrete type arguments.
        String sourceType = null;
        String targetType = null;
        Matcher mismatches = MISMATCH.matcher(message);
        while (mismatches.find()) {
            String source = mismatches.group(1).strip();
            String target = mismatches.group(2).strip();
            if (!source.contains("capture#") || !source.contains("<") || !target.contains("<")) continue;
            if (!castable(source, target)) continue;
            String normalized = normalizeCaptures(source);
            if (sourceType != null && (!sourceType.equals(normalized) || !targetType.equals(target))) {
                return false; // two different qualifying complaints: which one is meant is a guess
            }
            sourceType = normalized;
            targetType = target;
        }
        if (sourceType == null) return false;

        int index = -1;
        for (int i = 0; i < actual.size(); i++) {
            if (normalizeCaptures(actual.get(i).strip()).equals(sourceType)) {
                if (index >= 0) return false; // two identical argument types: ambiguous
                index = i;
            }
        }
        if (index < 0) return false;

        // The diagnostic spans the method select ('binder.<T>bind' / 'bind').
        TreePath select = file.findExact(start, end, t -> t instanceof ExpressionTree);
        if (select == null || select.getParentPath() == null
                || !(select.getParentPath().getLeaf() instanceof MethodInvocationTree call)
                || call.getMethodSelect() != select.getLeaf()
                || call.getArguments().size() != actual.size()) {
            return false;
        }
        ExpressionTree argument = call.getArguments().get(index);
        TreePath argumentPath = new TreePath(select.getParentPath(), argument);
        if (!castableExpression(argument)) return false;
        return cast(file, argumentPath, rawOf(targetType), targetType, out);
    }

    // ---- shared ---------------------------------------------------------------------------------------

    private static boolean castableExpression(Tree tree) {
        if (!(tree instanceof ExpressionTree)) return false;
        Tree.Kind kind = tree.getKind();
        return kind != Tree.Kind.LAMBDA_EXPRESSION && kind != Tree.Kind.MEMBER_REFERENCE;
    }

    /** Same raw type, type arguments differ, target safe to print as source. */
    static boolean castable(String source, String target) {
        if (!source.endsWith(">") || !target.endsWith(">")) return false;
        String sourceRaw = rawOf(source);
        if (!sourceRaw.equals(rawOf(target))) return false;
        if (source.equals(target)) return false;
        if (target.contains("capture#")) return false;
        return fullyQualified(target);
    }

    static String rawOf(String type) {
        int lt = type.indexOf('<');
        return lt < 0 ? type : type.substring(0, lt).strip();
    }

    /** Every name in the type is package-qualified (so no type variable can hide in it). */
    static boolean fullyQualified(String type) {
        Matcher m = TOKEN.matcher(type);
        boolean any = false;
        while (m.find()) {
            String token = m.group();
            if (TYPE_KEYWORDS.contains(token)) continue;
            any = true;
            if (token.indexOf('.') < 0) return false;
        }
        return any;
    }

    private static String normalizeCaptures(String type) {
        return CAPTURE_INDEX.matcher(type).replaceAll("capture#");
    }

    private boolean cast(SpanFixers.ParsedFile file, TreePath path, String raw, String target,
                         List<SpanFixers.Edit> out) {
        Tree leaf = path.getLeaf();
        String text = file.slice(leaf);
        if (text == null) return false;
        String operand = ExpressionSupport.isPrimaryLike(leaf) && leaf.getKind() != Tree.Kind.TYPE_CAST
                ? text : "(" + text + ")";
        String core = "(" + target + ") (" + raw + ") " + operand;
        boolean bare = ExpressionSupport.isDelimitedContext(path, true);
        out.add(new SpanFixers.Edit((int) file.start(leaf), (int) file.end(leaf), bare ? core : "(" + core + ")"));
        return true;
    }

    /** Splits on commas outside angle brackets and parentheses. */
    static List<String> splitTopLevel(String list) {
        List<String> parts = new ArrayList<>();
        String code = list.strip();
        if (code.isEmpty()) return parts;
        int depth = 0;
        int from = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '<' || c == '(' || c == '[') depth++;
            else if (c == '>' || c == ')' || c == ']') depth--;
            else if (c == ',' && depth == 0) {
                parts.add(code.substring(from, i));
                from = i + 1;
            }
        }
        parts.add(code.substring(from));
        return parts;
    }
}
