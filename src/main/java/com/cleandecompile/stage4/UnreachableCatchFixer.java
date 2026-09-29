package com.cleandecompile.stage4;

import com.sun.source.tree.CatchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TryTree;
import com.sun.source.tree.UnionTypeTree;
import com.sun.source.util.TreePath;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps a {@code catch} clause reachable when javac says
 * <pre>
 *   exception java.io.FileNotFoundException is never thrown in body of
 *   corresponding try statement
 * </pre>
 *
 * <p><b>Why the handler is kept, not deleted.</b> ProGuard strips the
 * {@code Exceptions} attribute unless told otherwise, so the callee that
 * really throws {@code FileNotFoundException} at runtime has lost its
 * {@code throws} clause, while the caller's exception table (which no
 * obfuscator can drop) still names the exception. The decompiled source is
 * therefore wrong about the <i>callee</i>, not about the handler. Deleting the
 * handler would make the exception escape at runtime; changing its type to
 * something broader would start swallowing unrelated exceptions. Making the
 * handler reachable in javac's eyes changes nothing at runtime in either
 * case.
 *
 * <p>The edit inserts, at the top of the {@code try} block and on the same
 * line as its opening brace (so line numbers hold),
 * <pre>
 *   &#47;* stage4: keeps the catch reachable *&#47; if (false) { throw (X) null; }
 * </pre>
 * {@code if (false)} is the one conditional javac's flow analysis treats as
 * possibly executing while never generating code for it, and the cast gives
 * the {@code throw} the static type {@code X} that the catch clause needs.
 * It compiles under every {@code --release}; only checked exceptions
 * produce this diagnostic, so {@code X} is always a legal thing to throw.
 *
 * <p>Both diagnostic shapes are handled: the single-type clause (javac spans
 * the whole {@code catch}) and each alternative of a multi-catch (javac spans
 * that alternative's type).
 */
final class UnreachableCatchFixer implements SpanFixers.Planner {

    static final String NAME = "unreachableCatch";

    private static final Pattern MESSAGE = Pattern.compile(
            "^exception ([\\w.$]+) is never thrown in body of corresponding try statement$");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean wants(String firstLine) {
        return MESSAGE.matcher(firstLine).matches();
    }

    @Override
    public boolean plan(SpanFixers.ParsedFile file, Diagnostic<? extends JavaFileObject> d,
                        List<SpanFixers.Edit> out) {
        if (!MESSAGE.matcher(SpanFixers.firstLine(d.getMessage(java.util.Locale.ENGLISH))).matches()) return false;
        long start = d.getStartPosition();
        long end = d.getEndPosition();

        TreePath path = file.findExact(start, end, t -> t instanceof CatchTree);
        String spelled;
        CatchTree catchTree;
        if (path != null) {
            catchTree = (CatchTree) path.getLeaf();
            if (catchTree.getParameter().getType() instanceof UnionTypeTree) return false;   // spans differ
            spelled = file.slice(catchTree.getParameter().getType());
        } else {
            // A multi-catch alternative: the span is one type expression.
            path = file.findExact(start, end, t -> true);
            if (path == null) return false;
            spelled = file.slice(path.getLeaf());
            TreePath parent = path.getParentPath();
            if (parent == null || !(parent.getLeaf() instanceof UnionTypeTree)) return false;
            TreePath clause = parent.getParentPath();
            while (clause != null && !(clause.getLeaf() instanceof CatchTree)) clause = clause.getParentPath();
            if (clause == null) return false;
            catchTree = (CatchTree) clause.getLeaf();
            path = clause;
        }
        if (spelled == null || !spelled.matches("[\\w.$]+")) return false;

        TreePath tryPath = path.getParentPath();
        if (tryPath == null || !(tryPath.getLeaf() instanceof TryTree tryTree)) return false;
        if (!tryTree.getCatches().contains(catchTree)) return false;

        long blockStart = file.start(tryTree.getBlock());
        if (blockStart < 0 || file.text.charAt((int) blockStart) != '{') return false;

        out.add(new SpanFixers.Edit((int) blockStart + 1, (int) blockStart + 1,
                " /* stage4: keeps the catch reachable */ if (false) { throw (" + spelled + ") null; }"));
        return true;
    }

    /** Exposed for tests: whether the marker for {@code exception} is already present. */
    static boolean hasMarker(String source, String exception) {
        Matcher m = Pattern.compile("if \\(false\\) \\{ throw \\(" + Pattern.quote(exception) + "\\) null; \\}")
                .matcher(source);
        return m.find();
    }
}
