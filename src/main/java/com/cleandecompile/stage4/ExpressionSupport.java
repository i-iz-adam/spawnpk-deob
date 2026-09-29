package com.cleandecompile.stage4;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;

import java.util.EnumSet;
import java.util.Set;

/**
 * Precedence bookkeeping for span-based rewrites: when the replacement for
 * an expression needs parentheses, and when the surrounding syntax already
 * provides them. Erring toward parentheses is always compile-safe; the
 * "loose" contexts only exist to keep the output readable.
 */
final class ExpressionSupport {

    /** Kinds that bind at least as tightly as a postfix/cast/unary operand. */
    private static final Set<Tree.Kind> PRIMARY_LIKE = EnumSet.of(
            Tree.Kind.IDENTIFIER, Tree.Kind.MEMBER_SELECT, Tree.Kind.METHOD_INVOCATION,
            Tree.Kind.ARRAY_ACCESS, Tree.Kind.PARENTHESIZED, Tree.Kind.NEW_CLASS,
            Tree.Kind.INT_LITERAL, Tree.Kind.LONG_LITERAL, Tree.Kind.FLOAT_LITERAL,
            Tree.Kind.DOUBLE_LITERAL, Tree.Kind.BOOLEAN_LITERAL, Tree.Kind.CHAR_LITERAL,
            Tree.Kind.STRING_LITERAL, Tree.Kind.NULL_LITERAL,
            Tree.Kind.POSTFIX_INCREMENT, Tree.Kind.POSTFIX_DECREMENT,
            Tree.Kind.PREFIX_INCREMENT, Tree.Kind.PREFIX_DECREMENT,
            Tree.Kind.UNARY_MINUS, Tree.Kind.UNARY_PLUS, Tree.Kind.BITWISE_COMPLEMENT,
            Tree.Kind.LOGICAL_COMPLEMENT, Tree.Kind.TYPE_CAST);

    private ExpressionSupport() {
    }

    /** True when {@code tree} can be the left operand of {@code != 0}, {@code == 0}
     *  or a cast without extra parentheses. */
    static boolean isPrimaryLike(Tree tree) {
        return PRIMARY_LIKE.contains(tree.getKind());
    }

    /** True when {@code tree} can be the condition of {@code ? :} unparenthesized:
     *  everything except assignments, conditionals and lambdas (all lower-precedence). */
    static boolean bindsTighterThanTernary(Tree tree) {
        Tree.Kind kind = tree.getKind();
        if (kind == Tree.Kind.CONDITIONAL_EXPRESSION || kind == Tree.Kind.ASSIGNMENT
                || kind == Tree.Kind.LAMBDA_EXPRESSION) {
            return false;
        }
        return !(tree instanceof CompoundAssignmentTree);
    }

    /**
     * True when the parent syntax delimits {@code path}'s expression on its
     * own (variable initializer, assignment right-hand side, return value,
     * call/constructor argument, array index, parentheses), so a
     * replacement of any precedence can go in bare. With
     * {@code allowConditional} a {@code ? :} operand counts too -- safe for
     * relational replacements ({@code x != 0}) but not for a nested
     * conditional ({@code c ? f ? 1 : 0 : z} reads badly).
     */
    static boolean isDelimitedContext(TreePath path, boolean allowConditional) {
        TreePath parentPath = path.getParentPath();
        if (parentPath == null) return false;
        Tree parent = parentPath.getLeaf();
        Tree child = path.getLeaf();
        if (parent instanceof VariableTree v) return v.getInitializer() == child;
        if (parent instanceof AssignmentTree a) return a.getExpression() == child;
        if (parent instanceof CompoundAssignmentTree c) return c.getExpression() == child;
        if (parent instanceof ArrayAccessTree a) return a.getIndex() == child;
        if (parent instanceof MethodInvocationTree m) return m.getArguments().contains(child);
        if (parent instanceof NewClassTree n) return n.getArguments().contains(child);
        if (parent instanceof ConditionalExpressionTree) return allowConditional;
        return switch (parent.getKind()) {
            case RETURN, PARENTHESIZED -> true;
            case CONDITIONAL_AND, CONDITIONAL_OR -> allowConditional;
            default -> false;
        };
    }
}
