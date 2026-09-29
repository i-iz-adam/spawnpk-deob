package com.cleandecompile.stage4;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.util.TreePath;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.List;

/**
 * Unqualifies an enum {@code case} label: {@code case Kind.ALPHA:} becomes
 * {@code case ALPHA:}.
 *
 * <p>Before an enum is restored ({@link EnumRestoreFixer}) its constants are
 * ordinary static fields, so {@code case ALPHA:} in another file does not
 * resolve and {@code MemberResolutionFixer} qualifies it. Once the class is a
 * real enum, source levels below 21 insist on the bare name:
 * <pre>
 *   an enum switch case label must be the unqualified name of an enumeration constant
 * </pre>
 * javac has already established that the switch is over an enum and that the
 * label is a qualified reference; the rule it states is exactly the edit, so
 * nothing is guessed. Only a {@code case} label that is a member select is
 * touched, and only its last segment is kept.
 */
final class EnumCaseLabelFixer implements SpanFixers.Planner {

    static final String NAME = "enumCaseLabel";

    private static final String MESSAGE =
            "an enum switch case label must be the unqualified name of an enumeration constant";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean wants(String firstLine) {
        return firstLine.startsWith(MESSAGE);
    }

    @Override
    public boolean plan(SpanFixers.ParsedFile file, Diagnostic<? extends JavaFileObject> d,
                        List<SpanFixers.Edit> out) {
        TreePath path = file.findExact(d.getStartPosition(), d.getEndPosition(), t -> t instanceof MemberSelectTree);
        if (path == null) return false;
        TreePath parent = path.getParentPath();
        // JDK 21 wraps a constant label in a CONSTANT_CASE_LABEL node; older trees hang it off the case.
        if (parent != null && parent.getLeaf().getKind().name().equals("CONSTANT_CASE_LABEL")) {
            parent = parent.getParentPath();
        }
        if (parent == null || !(parent.getLeaf() instanceof CaseTree caseTree)) return false;
        if (!caseTree.getExpressions().contains(path.getLeaf())) return false;   // the label, not something inside it
        MemberSelectTree select = (MemberSelectTree) path.getLeaf();
        out.add(new SpanFixers.Edit((int) file.start(select), (int) file.end(select),
                select.getIdentifier().toString()));
        return true;
    }
}
