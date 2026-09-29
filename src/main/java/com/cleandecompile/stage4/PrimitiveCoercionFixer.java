package com.cleandecompile.stage4;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.util.TreePath;

import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs the JVM's int/boolean conflation.
 *
 * <p>At the bytecode level a boolean IS an int (0 or 1): the verifier
 * cannot tell them apart, so a decompiler that lost the declared type of a
 * local, field or return value prints {@code boolean} where the bytecode
 * moves an {@code int} (or the reverse). javac then rejects the tree:
 * {@code int cannot be converted to boolean}, {@code boolean cannot be
 * converted to int}, {@code bad operand types for binary operator '&'}
 * (one side int, one boolean), {@code incomparable types: boolean and int},
 * {@code bad operand type int for unary operator '!'}.
 *
 * <p>The conversion the bytecode performs implicitly has one exact source
 * spelling, so the fix is mechanical and semantics-preserving:
 * <ul>
 *   <li>int to boolean: {@code x != 0} (what {@code ifne} tests); the
 *       literals {@code 0}/{@code 1} become {@code false}/{@code true};</li>
 *   <li>boolean to int: {@code b ? 1 : 0}; {@code false}/{@code true}
 *       become {@code 0}/{@code 1};</li>
 *   <li>{@code !x} on an int: {@code x == 0}.</li>
 * </ul>
 * Mixed bitwise operators ({@code & | ^}) coerce the boolean side to int,
 * which reproduces the bytecode's {@code iand}/{@code ior}/{@code ixor}
 * exactly; whether the result then needs a further conversion is left to the
 * next round's diagnostic. Only the operand javac flagged is touched, located
 * by javac's own source span -- never by line pattern -- so a second
 * lookalike on the same line cannot be mistaken for it.
 */
final class PrimitiveCoercionFixer implements SpanFixers.Planner {

    static final String NAME = "primitiveCoercion";

    /** JVM int-like: everything the verifier stores in an int slot except boolean. */
    private static final Set<String> INT_LIKE = Set.of("int", "short", "byte", "char");

    private static final Pattern CONVERSION =
            Pattern.compile("^incompatible types: (\\w+) cannot be converted to (\\w+)$");
    private static final Pattern INCOMPARABLE = Pattern.compile("^incomparable types: (\\w+) and (\\w+)$");
    private static final Pattern BAD_UNARY = Pattern.compile("^bad operand type (\\w+) for unary operator '!'$");
    private static final Pattern BAD_BINARY = Pattern.compile(
            "bad operand types for binary operator '([^']+)'\\s+first type:\\s+(\\S+)\\s+second type:\\s+(\\S+)");

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean wants(String firstLine) {
        return firstLine.startsWith("incompatible types: ")
                || firstLine.startsWith("incomparable types: ")
                || firstLine.startsWith("bad operand type");
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
            String from = conversion.group(1);
            String to = conversion.group(2);
            TreePath path = file.findExact(start, end, t -> t instanceof ExpressionTree);
            if (path == null) return false;
            if (INT_LIKE.contains(from) && to.equals("boolean")) return toBoolean(file, path, out);
            if (from.equals("boolean") && to.equals("int")) return toInt(file, path, false, out);
            return false;
        }

        Matcher unary = BAD_UNARY.matcher(first);
        if (unary.matches()) {
            if (!INT_LIKE.contains(unary.group(1))) return false;
            TreePath path = file.findExact(start, end, t -> t.getKind() == Tree.Kind.LOGICAL_COMPLEMENT);
            if (path == null) return false;
            ExpressionTree operand = ((UnaryTree) path.getLeaf()).getExpression();
            String operandText = file.slice(operand);
            if (operandText == null) return false;
            String core = (ExpressionSupport.isPrimaryLike(operand) ? operandText : "(" + operandText + ")")
                    + " == 0";
            out.add(new SpanFixers.Edit((int) start, (int) end,
                    ExpressionSupport.isDelimitedContext(path, true) ? core : "(" + core + ")"));
            return true;
        }

        Matcher incomparable = INCOMPARABLE.matcher(first);
        if (incomparable.matches()) {
            return incomparable(file, start, end, incomparable.group(1), incomparable.group(2), out);
        }

        Matcher binary = BAD_BINARY.matcher(message);
        if (binary.find()) {
            return badBinary(file, start, end, binary.group(1), binary.group(2), binary.group(3), out);
        }
        return false;
    }

    // ---- int -> boolean -------------------------------------------------------------------------------

    private boolean toBoolean(SpanFixers.ParsedFile file, TreePath path, List<SpanFixers.Edit> out) {
        Tree leaf = path.getLeaf();
        long s = file.start(leaf);
        long e = file.end(leaf);
        if (leaf.getKind() == Tree.Kind.INT_LITERAL) {
            Object value = ((LiteralTree) leaf).getValue();
            if (value instanceof Integer i && (i == 0 || i == 1)) {
                out.add(new SpanFixers.Edit((int) s, (int) e, i == 1 ? "true" : "false"));
                return true;
            }
        }
        String text = file.slice(leaf);
        if (text == null) return false;
        String core = (ExpressionSupport.isPrimaryLike(leaf) ? text : "(" + text + ")") + " != 0";
        out.add(new SpanFixers.Edit((int) s, (int) e,
                ExpressionSupport.isDelimitedContext(path, true) ? core : "(" + core + ")"));
        return true;
    }

    // ---- boolean -> int -------------------------------------------------------------------------------

    /** @param compoundRhs the operand is the right side of {@code op=}: the assignment
     *  already delimits it, so a bare conditional reads fine. */
    private boolean toInt(SpanFixers.ParsedFile file, TreePath path, boolean compoundRhs,
                          List<SpanFixers.Edit> out) {
        Tree leaf = path.getLeaf();
        long s = file.start(leaf);
        long e = file.end(leaf);
        if (leaf.getKind() == Tree.Kind.BOOLEAN_LITERAL) {
            Object value = ((LiteralTree) leaf).getValue();
            if (value instanceof Boolean b) {
                out.add(new SpanFixers.Edit((int) s, (int) e, b ? "1" : "0"));
                return true;
            }
        }
        String text = file.slice(leaf);
        if (text == null) return false;
        String core = (ExpressionSupport.bindsTighterThanTernary(leaf) ? text : "(" + text + ")") + " ? 1 : 0";
        boolean bare = compoundRhs || ExpressionSupport.isDelimitedContext(path, false);
        out.add(new SpanFixers.Edit((int) s, (int) e, bare ? core : "(" + core + ")"));
        return true;
    }

    // ---- operators ------------------------------------------------------------------------------------

    private boolean badBinary(SpanFixers.ParsedFile file, long start, long end, String op,
                              String firstType, String secondType, List<SpanFixers.Edit> out) {
        TreePath path = file.findExact(start, end,
                t -> t instanceof BinaryTree || t instanceof CompoundAssignmentTree);
        if (path == null) return false;
        Tree node = path.getLeaf();
        ExpressionTree left;
        ExpressionTree right;
        boolean compound = node instanceof CompoundAssignmentTree;
        if (compound) {
            left = ((CompoundAssignmentTree) node).getVariable();
            right = ((CompoundAssignmentTree) node).getExpression();
        } else {
            left = ((BinaryTree) node).getLeftOperand();
            right = ((BinaryTree) node).getRightOperand();
        }
        // The operator token between the operands must be the one javac names
        // (rules out a span that merely starts at the same place).
        String between = file.slice(file.end(left), file.start(right));
        if (between == null) return false;
        String expected = compound ? op + "=" : op;
        if (!stripComments(between).strip().equals(expected)) return false;

        boolean firstInt = INT_LIKE.contains(firstType);
        boolean secondInt = INT_LIKE.contains(secondType);
        boolean firstBool = firstType.equals("boolean");
        boolean secondBool = secondType.equals("boolean");
        TreePath leftPath = new TreePath(path, left);
        TreePath rightPath = new TreePath(path, right);

        switch (op) {
            case "&", "|", "^" -> {
                if (compound) {
                    // lhs op= rhs: the variable keeps its declared type, the value is coerced to it.
                    if (firstInt && secondBool) return toInt(file, rightPath, true, out);
                    if (firstBool && secondInt) return toBoolean(file, rightPath, out);
                    return false;
                }
                // Bytecode has iand/ior/ixor only: bring the boolean side to int.
                if (firstBool && secondInt) return toInt(file, leftPath, false, out);
                if (firstInt && secondBool) return toInt(file, rightPath, false, out);
                return false;
            }
            case "&&", "||" -> {
                if (compound) return false;
                boolean fixed = false;
                if (firstInt && (secondBool || secondInt)) fixed |= toBoolean(file, leftPath, out);
                if (secondInt && (firstBool || firstInt)) fixed |= toBoolean(file, rightPath, out);
                return fixed;
            }
            default -> {
                return false;
            }
        }
    }

    private boolean incomparable(SpanFixers.ParsedFile file, long start, long end, String a, String b,
                                 List<SpanFixers.Edit> out) {
        boolean mixed = (a.equals("boolean") && INT_LIKE.contains(b)) || (b.equals("boolean") && INT_LIKE.contains(a));
        if (!mixed) return false;
        TreePath path = file.findExact(start, end, t -> t.getKind() == Tree.Kind.EQUAL_TO
                || t.getKind() == Tree.Kind.NOT_EQUAL_TO);
        if (path == null) return false;
        BinaryTree node = (BinaryTree) path.getLeaf();
        TreePath leftPath = new TreePath(path, node.getLeftOperand());
        TreePath rightPath = new TreePath(path, node.getRightOperand());
        boolean leftIsBool = a.equals("boolean");
        TreePath boolSide = leftIsBool ? leftPath : rightPath;
        TreePath intSide = leftIsBool ? rightPath : leftPath;
        // b == 1  ->  b == true : the literal spelling of the same test.
        if (intSide.getLeaf().getKind() == Tree.Kind.INT_LITERAL) {
            Object value = ((LiteralTree) intSide.getLeaf()).getValue();
            if (value instanceof Integer i && (i == 0 || i == 1)) {
                out.add(new SpanFixers.Edit((int) file.start(intSide.getLeaf()),
                        (int) file.end(intSide.getLeaf()), i == 1 ? "true" : "false"));
                return true;
            }
        }
        // Otherwise compare as ints, which is what the bytecode does.
        return toInt(file, boolSide, false, out);
    }

    private static String stripComments(String s) {
        return s.replaceAll("/\\*.*?\\*/", " ").replaceAll("//[^\\n]*", " ");
    }
}
