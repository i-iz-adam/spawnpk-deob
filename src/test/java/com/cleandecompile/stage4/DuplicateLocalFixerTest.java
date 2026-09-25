package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DuplicateLocalFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static String messages(JavacRunner.CompileOutcome outcome) {        return outcome.diagnostics().stream()
                .map(d -> d.getLineNumber() + ": " + d.getMessage(Locale.ENGLISH))
                .reduce((a, b) -> a + " | " + b)
                .orElse("<none>");
    }

    private static void assertReportsDuplicate(JavacRunner.CompileOutcome outcome, String name) {
        assertTrue(outcome.diagnostics().stream().anyMatch(d ->
                        d.getMessage(Locale.ENGLISH).startsWith(
                                "variable " + name + " is already defined in method ")),
                "expected a duplicate-variable diagnostic for '" + name
                        + "', got: " + messages(outcome));
    }

    @Test
    void renamesForEachLocalThatCollidesWithAnOuterLocal(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    private static int[] field1494 = new int[0];

                    public boolean method586(int flag) {
                        int n112;
                        n112 = 1;
                        System.out.println(n112);
                        if (flag > 0) {
                            for (int n112 : field1494) {
                                System.out.println(n112);
                            }
                        }
                        return n112 > 0;
                    }
                }
                """);
        long before = Files.readAllLines(root.resolve("rs/Client.java")).size();

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "n112");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/Client.java"));
        assertTrue(updated.contains("for (int n112_2 : field1494) {"),
                "loop declaration not renamed:\n" + updated);
        assertTrue(updated.contains("System.out.println(n112_2);"),
                "loop use not renamed:\n" + updated);
        assertTrue(updated.contains("int n112;"), "earlier declaration must survive:\n" + updated);
        assertTrue(updated.contains("n112 = 1;"), "earlier use must survive:\n" + updated);
        assertTrue(updated.contains("return n112 > 0;"), "later outer use must survive:\n" + updated);
        assertEquals(2, updated.split("n112_2", -1).length - 1, "exactly two renames expected");
        assertEquals(before, Files.readAllLines(root.resolve("rs/Client.java")).size(),
                "line count must be preserved");

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void renamesLocalsRedeclaredInsideANestedLambda(@TempDir Path root) throws IOException {
        write(root, "rs/ui/Class297.java", """
                package rs.ui;

                public class Class297 {
                    public String method2780() {
                        return "x";
                    }
                }
                """);
        write(root, "rs/ui/Class292.java", """
                package rs.ui;

                public class Class292 {
                    private boolean field6101;
                    private Object field6111;

                    public void onNavigationButtonAdded(String button) {
                        Runnable outer = () -> {
                            Class297 class297 = new Class297();
                            boolean bl = field6101;
                            System.out.println(class297.method2780());
                            System.out.println(bl);
                            Thread t = new Thread(() -> {
                                boolean bl;
                                Class297 class297 = new Class297();
                                if (class297.method2780().isEmpty()) {
                                    return;
                                }
                                bl = field6111 != null;
                                if (bl) {
                                    System.out.println(bl);
                                }
                            });
                            t.start();
                        };
                        outer.run();
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "bl");
        assertReportsDuplicate(outcome, "class297");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(2, result.fixes());
        String updated = Files.readString(root.resolve("rs/ui/Class292.java"));
        assertTrue(updated.contains("boolean bl_2;"), "lambda declaration not renamed:\n" + updated);
        assertTrue(updated.contains("bl_2 = field6111 != null;"), "assignment not renamed:\n" + updated);
        assertTrue(updated.contains("if (bl_2) {"), "branch condition not renamed:\n" + updated);
        assertTrue(updated.contains("Class297 class297_2 = new Class297();"),
                "lambda type declaration not renamed:\n" + updated);
        assertTrue(updated.contains("if (class297_2.method2780().isEmpty()) {"),
                "lambda use not renamed:\n" + updated);
        assertTrue(updated.contains("Class297 class297 = new Class297();"),
                "outer declaration must survive:\n" + updated);
        assertTrue(updated.contains("boolean bl = field6101;"),
                "outer declaration must survive:\n" + updated);
        assertTrue(updated.contains("System.out.println(bl);"),
                "outer use must survive:\n" + updated);

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void renamesFlatSlotLoopVariableAndLeavesTheOuterSlotAlone(@TempDir Path root) throws IOException {
        write(root, "rs/gui/LoadoutFolders.java", """
                package rs.gui;

                import java.util.List;

                public class LoadoutFolders {
                    private List<Object> get(String name) {
                        return List.of();
                    }

                    public void renameLoadout(String string, String newName) {
                        Object object2;
                        if (newName.isEmpty()) {
                            System.out.println("empty");
                            return;
                        }
                        if (!newName.equals("x")) {
                            for (Object object2 : (List<Object>)this.get(string)) {
                                if (!object2.toString().isEmpty()) {
                                    System.out.println("duplicate");
                                    return;
                                }
                            }
                        }
                        object2 = new Object();
                        System.out.println(object2);
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "object2");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/gui/LoadoutFolders.java"));
        assertTrue(updated.contains("for (Object object2_2 : (List<Object>)this.get(string)) {"),
                "loop declaration not renamed:\n" + updated);
        assertTrue(updated.contains("if (!object2_2.toString().isEmpty()) {"),
                "loop use not renamed:\n" + updated);
        assertTrue(updated.contains("Object object2;"), "earlier declaration must survive:\n" + updated);
        assertTrue(updated.contains("object2 = new Object();"),
                "outer assignment after the loop must survive:\n" + updated);
        assertTrue(updated.contains("System.out.println(object2);"),
                "outer use after the loop must survive:\n" + updated);

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void leavesStringAndCommentMentionsAlone(@TempDir Path root) throws IOException {
        write(root, "rs/Literals.java", """
                package rs;

                public class Literals {
                    void m(int[] xs) {
                        int n112 = 1;
                        // n112 is the loop variable below
                        String label = "n112";
                        for (int n112 : xs) {
                            /* n112 again, in a block comment */
                            System.out.println(n112 + "n112");
                        }
                        System.out.println(label);
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/Literals.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "n112");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/Literals.java"));
        assertTrue(updated.contains("for (int n112_2 : xs) {"), "declaration not renamed:\n" + updated);
        assertTrue(updated.contains("System.out.println(n112_2 + \"n112\");"),
                "code use not renamed while the string literal must be:\n" + updated);
        assertTrue(updated.contains("// n112 is the loop variable below"),
                "line comment must be untouched:\n" + updated);
        assertTrue(updated.contains("String label = \"n112\";"),
                "string literal must be untouched:\n" + updated);
        assertTrue(updated.contains("/* n112 again, in a block comment */"),
                "block comment must be untouched:\n" + updated);
        assertTrue(original.contains("int n112 = 1;"), "fixture sanity");

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void doesNotReFixAnAlreadyRenamedDeclaration(@TempDir Path root) throws IOException {
        write(root, "rs/Again.java", """
                package rs;

                public class Again {
                    void m(int[] xs) {
                        int n112 = 1;
                        for (int n112 : xs) {
                            System.out.println(n112);
                        }
                        System.out.println(n112);
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "n112");
        var first = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));
        assertEquals(1, first.fixes());
        String renamed = Files.readString(root.resolve("rs/Again.java"));

        var clean = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(clean.success(), "expected clean compile after rename, got: " + messages(clean));
        assertTrue(clean.diagnostics().isEmpty(), "no diagnostics expected, got: " + messages(clean));

        var second = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(clean.diagnostics()));
        assertEquals(0, second.fixes(), "already-renamed declaration must not be touched again");
        assertEquals(renamed, Files.readString(root.resolve("rs/Again.java")),
                "second pass must be a no-op");
    }

    @Test
    void leavesTheSameNameInDifferentMethodsAlone(@TempDir Path root) throws IOException {
        write(root, "rs/Pair.java", """
                package rs;

                public class Pair {
                    int first(int seed) {
                        int n112 = seed;
                        return n112;
                    }

                    int second(int seed) {
                        int n112 = seed;
                        return n112;
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/Pair.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.success(), "fixture must compile cleanly, got: " + messages(outcome));

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes());
        assertEquals(original, Files.readString(root.resolve("rs/Pair.java")),
                "sibling methods with a shared name must not be edited");
    }

    @Test
    void leavesACleanFileUntouched(@TempDir Path root) throws IOException {
        write(root, "rs/Clean.java", """
                package rs;

                public class Clean {
                    int m(int seed) {
                        int n112 = seed;
                        for (int element : new int[]{1, 2}) {
                            seed += element;
                        }
                        return n112 + seed;
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/Clean.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.success(), "fixture must compile cleanly, got: " + messages(outcome));

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes());
        assertEquals(original, Files.readString(root.resolve("rs/Clean.java")),
                "a file with no matching diagnostic must not be edited");
    }

    @Test
    void unhandledDropsHandledDiagnosticsAndKeepsTheRest(@TempDir Path root) throws IOException {
        write(root, "rs/Mixed.java", """
                package rs;

                public class Mixed {
                    Missing field1;

                    void m(int[] xs) {
                        int n112 = 1;
                        for (int n112 : xs) {
                            System.out.println(n112);
                        }
                        System.out.println(n112);
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var all = bucketer.categorize(outcome.diagnostics());
        assertTrue(all.stream().anyMatch(b ->
                b.category() == DiagnosticBucketer.Category.DUPLICATE_METHOD), messages(outcome));
        assertTrue(all.stream().anyMatch(b ->
                b.category() == DiagnosticBucketer.Category.UNRESOLVED_SYMBOL), messages(outcome));

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(root, all);

        assertEquals(1, result.fixes());
        assertEquals(1, result.handled().size());
        var left = result.unhandled(all);
        assertEquals(all.size() - 1, left.size(), "only handled diagnostics are withheld");
        assertFalse(left.stream().anyMatch(b -> result.handled().contains(b.diagnostic())));
        assertTrue(left.stream().anyMatch(b ->
                b.category() == DiagnosticBucketer.Category.UNRESOLVED_SYMBOL),
                "unrelated diagnostics must remain available to later fixers");
        assertTrue(Files.readString(root.resolve("rs/Mixed.java")).contains("for (int n112_2 : xs) {"),
                "the duplicate fix must still be applied");
    }

    @Test
    void braceLessForEachRenamesOnlyItsOwnStatement(@TempDir Path root) throws IOException {
        write(root, "rs/BraceLess.java", """
                package rs;

                public class BraceLess {
                    int m(int[] xs, boolean flag) {
                        int o = xs.length;
                        if (flag)
                            for (int o : xs) System.out.println(o);
                        return o;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "o");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/BraceLess.java"));
        assertTrue(updated.contains("for (int o_2 : xs) System.out.println(o_2);"),
                "brace-less for-each statement not renamed as a unit:\n" + updated);
        assertTrue(updated.contains("int o = xs.length;"), "outer declaration must survive:\n" + updated);
        assertTrue(updated.contains("return o;"), "outer use must survive:\n" + updated);
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void braceLessCStyleForRenamesOnlyItsOwnStatement(@TempDir Path root) throws IOException {
        write(root, "rs/BraceLessFor.java", """
                package rs;

                public class BraceLessFor {
                    int m(boolean flag) {
                        int i = 0;
                        if (flag)
                            for (int i = 0; i < 3; ++i) System.out.println(i);
                        return i;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "i");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/BraceLessFor.java"));
        assertTrue(updated.contains("for (int i_2 = 0; i_2 < 3; ++i_2) System.out.println(i_2);"),
                "C-style for header and body not renamed as a unit:\n" + updated);
        assertTrue(updated.contains("int i = 0;"), "outer declaration must survive:\n" + updated);
        assertTrue(updated.contains("return i;"), "outer use must survive:\n" + updated);
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void plainDeclarationInsideANestedIfBlockIsStillRenamed(@TempDir Path root) throws IOException {
        write(root, "rs/NestedIf.java", """
                package rs;

                public class NestedIf {
                    int m(int seed, boolean flag) {
                        int o = seed;
                        if (flag) {
                            int o = seed + 1;
                            System.out.println(o);
                        }
                        return o;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "o");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/NestedIf.java"));
        assertTrue(updated.contains("int o_2 = seed + 1;"), "nested declaration not renamed:\n" + updated);
        assertTrue(updated.contains("System.out.println(o_2);"), "nested use not renamed:\n" + updated);
        assertTrue(updated.contains("int o = seed;"), "outer declaration must survive:\n" + updated);
        assertTrue(updated.contains("return o;"), "outer use must survive:\n" + updated);
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void lambdaParameterRedeclarationIsDeclined(@TempDir Path root) throws IOException {
        write(root, "rs/LambdaParam.java", """
                package rs;

                public class LambdaParam {
                    void m(int[] xs) {
                        int o = 1;
                        Runnable r = (o) -> System.out.println(o);
                        System.out.println(o);
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/LambdaParam.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "o");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(), "a lambda parameter is not a shape this fixer renames");
        assertEquals(original, Files.readString(root.resolve("rs/LambdaParam.java")),
                "a declined region must be left byte-identical");
    }

    @Test
    void overlongMethodExceedingTheSharedHelperWindowIsStillRenamed(@TempDir Path root)
            throws IOException {
        StringBuilder source = new StringBuilder("""
                package rs;

                public class Huge {
                    int m(int seed) {
                        int o = seed;
                """);
        for (int i = 0; i < 2100; i++) {
            source.append("        seed = f(seed);\n");
        }
        source.append("""
                        for (int o : new int[]{1, 2}) {
                            System.out.println(o);
                        }
                        return o;
                    }

                    private int f(int v) {
                        return v + 1;
                    }
                }
                """);
        write(root, "rs/Huge.java", source.toString());

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "o");
        assertTrue(Files.readAllLines(root.resolve("rs/Huge.java")).size() > 2000,
                "fixture must exceed the shared helper's 2000-line window");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/Huge.java"));
        assertTrue(updated.contains("for (int o_2 : new int[]{1, 2}) {"),
                "over-long method was skipped:\n" + updated);
        assertTrue(updated.contains("System.out.println(o_2);"),
                "loop use not renamed:\n" + updated);
        assertTrue(updated.contains("int o = seed;"), "earlier declaration must survive:\n" + updated);
        assertTrue(updated.contains("return o;"), "outer use must survive:\n" + updated);
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void skipsFreshNameCandidatesAlreadyInUse(@TempDir Path root) throws IOException {
        write(root, "rs/Collision.java", """
                package rs;

                public class Collision {
                    int m(int[] xs) {
                        int o = 1;
                        int o_2 = 2;
                        for (int o : xs) {
                            System.out.println(o);
                        }
                        return o + o_2;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "o");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/Collision.java"));
        assertTrue(updated.contains("for (int o_3 : xs) {"),
                "taken fresh name must be skipped:\n" + updated);
        assertTrue(updated.contains("System.out.println(o_3);"), "loop use not renamed:\n" + updated);
        assertTrue(updated.contains("int o_2 = 2;"), "pre-existing o_2 must survive:\n" + updated);
        assertTrue(updated.contains("return o + o_2;"), "outer uses must survive:\n" + updated);
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void tripleDeclarationOfTheSameNameIsDeclined(@TempDir Path root) throws IOException {
        write(root, "rs/Triple.java", """
                package rs;

                public class Triple {
                    void m() {
                        int o = 1;
                        System.out.println(o);
                        int o = 2;
                        System.out.println(o);
                        int o = 3;
                        System.out.println(o);
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/Triple.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().filter(d ->
                        d.getMessage(Locale.ENGLISH).startsWith("variable o is already defined in method "))
                .count() >= 2, "fixture must report both redeclarations, got: " + messages(outcome));

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(), "an ambiguous region must be declined, not guessed");
        assertEquals(original, Files.readString(root.resolve("rs/Triple.java")),
                "a declined region must be left byte-identical");
    }

    @Test
    void renamesASecondSameBlockDeclarationOfTheSameName(@TempDir Path root) throws IOException {
        write(root, "rs/SameBlock.java", """
                package rs;

                public class SameBlock {
                    void m() {
                        int o = 1;
                        System.out.println(o);
                        int o = 2;
                        System.out.println(o);
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "o");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/SameBlock.java"));
        assertTrue(updated.contains("int o_2 = 2;"), "second declaration not renamed:\n" + updated);
        assertTrue(updated.indexOf("int o = 1;") < updated.indexOf("int o_2 = 2;"),
                "first declaration must come first and survive:\n" + updated);
        assertEquals(1, countOccurrences(updated, "int o = 1;"), "first declaration must survive:\n" + updated);
        assertEquals(1, countOccurrences(updated, "println(o);"), "first use must survive:\n" + updated);
        assertEquals(1, countOccurrences(updated, "println(o_2);"), "second use must be renamed:\n" + updated);
        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void declinesWhenTheRegionHoldsADeeperRedeclaration(@TempDir Path root) throws IOException {
        write(root, "rs/Deeper.java", """
                package rs;

                public class Deeper {
                    void m(boolean flag) {
                        int o = 1;
                        if (flag) {
                            int o = 2;
                            if (flag) {
                                int o = 3;
                                System.out.println(o);
                            }
                        }
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "o");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/Deeper.java"));
        assertTrue(updated.contains("int o = 2;"),
                "the outer redeclaration spans a deeper one and must be declined:\n" + updated);
        assertTrue(updated.contains("int o_2 = 3;"),
                "the deeper redeclaration is unambiguous and must be renamed:\n" + updated);
        assertTrue(updated.contains("System.out.println(o_2);"), "deeper use not renamed:\n" + updated);
        assertEquals(1, countOccurrences(updated, "int o = 1;"), "outermost declaration must survive:\n" + updated);
    }

    @Test
    void declinesWhenTheLoopBodyIsAnInlineBlockOnTheDeclarationLine(@TempDir Path root)
            throws IOException {
        write(root, "rs/Inline.java", """
                package rs;

                public class Inline {
                    int m(int[] xs) {
                        int o = xs.length;
                        for (int o : xs) { System.out.println(o); }
                        return o;
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/Inline.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "o");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(), "an inline loop body must be declined, not over-collected");
        assertEquals(original, Files.readString(root.resolve("rs/Inline.java")),
                "the statement after an inline loop body must not be swept into the region");
    }

    @Test
    void renamesABracedReceiverPrefixedLambdaParameter(@TempDir Path root) throws IOException {
        write(root, "rs/Lambdas.java", """
                package rs;

                import java.util.List;

                public class Lambdas {
                    void sameLine(List<String> xs) {
                        int o = 1;
                        xs.forEach(o -> {
                            System.out.println(o);
                        });
                        xs.forEach(o -> o.length());
                        System.out.println(o);
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().filter(d ->
                        d.getMessage(Locale.ENGLISH).startsWith("variable o is already defined in method "))
                .count() >= 2, "fixture must report both lambda parameters, got: " + messages(outcome));

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes(), "only the braced lambda is a bounded rename");
        String updated = Files.readString(root.resolve("rs/Lambdas.java"));
        assertTrue(updated.contains("xs.forEach(o_2 -> {"),
                "lambda parameter not renamed:\n" + updated);
        assertTrue(updated.contains("System.out.println(o_2);"),
                "lambda body use not renamed:\n" + updated);
        assertTrue(updated.contains("xs.forEach(o -> o.length());"),
                "the unbraced lambda is not a bounded rename and must stay untouched:\n" + updated);
        assertTrue(updated.contains("int o = 1;"), "outer declaration must survive:\n" + updated);
        assertTrue(updated.contains("System.out.println(o);"),
                "outer use after the lambda must survive:\n" + updated);
        assertEquals(1, countOccurrences(updated, "int o = 1;"), "outer declaration must survive:\n" + updated);
    }

    @Test
    void declinesWhenTheRegionHoldsALambdaParameterDeclaration(@TempDir Path root) throws IOException {
        write(root, "rs/RegionLambda.java", """
                package rs;

                import java.util.List;

                public class RegionLambda {
                    void m(List<String> xs, boolean flag) {
                        int o = 0;
                        if (flag) {
                            int o = 1;
                            System.out.println(o);
                            xs.forEach(o -> System.out.println(o));
                        }
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/RegionLambda.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().filter(d ->
                        d.getMessage(Locale.ENGLISH).startsWith("variable o is already defined in method "))
                .count() >= 2, "fixture must report both redeclarations, got: " + messages(outcome));

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "a lambda parameter inside the region is a strict subrange and must block the rename");
        assertEquals(original, Files.readString(root.resolve("rs/RegionLambda.java")),
                "renaming across the lambda parameter would make it shadow the renamed local");
    }

    @Test
    void declinesUnbracedReceiverPrefixedLambda(@TempDir Path root) throws IOException {
        write(root, "rs/Unbraced.java", """
                package rs;

                import java.util.List;

                public class Unbraced {
                    void m(List<String> xs) {
                        int o = 1;
                        xs.forEach(o -> o.length());
                        System.out.println(o);
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/Unbraced.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "o");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(), "an unbraced lambda is not a bounded rename");
        assertEquals(original, Files.readString(root.resolve("rs/Unbraced.java")),
                "the outer use after an unbraced lambda must not be renamed");
    }

    @Test
    void declinesBraceLessForWhoseBodyIsALambda(@TempDir Path root) throws IOException {
        write(root, "rs/BraceLessLambda.java", """
                package rs;

                import java.util.List;
                import java.util.function.Consumer;

                public class BraceLessLambda {
                    void m(List<String> xs, boolean flag) {
                        int o = 1;
                        if (flag)
                            for (int o : xs) consume(o -> o.length());
                        System.out.println(o);
                    }

                    private static void consume(Consumer<String> c) {
                        c.accept("");
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/BraceLessLambda.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "o");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "a lambda in the loop's own statement is not a bounded rename");
        assertEquals(original, Files.readString(root.resolve("rs/BraceLessLambda.java")),
                "the outer use after a brace-less loop with a lambda body must not be renamed");
    }

    @Test
    void methodHeaderSplitAcrossLinesIsDeclined(@TempDir Path root) throws IOException {
        write(root, "rs/SplitHeader.java", """
                package rs;

                public class SplitHeader {
                    void m(
                            int[] xs) {
                        int o = 1;
                        for (int o : xs) {
                            System.out.println(o);
                        }
                        System.out.println(o);
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/SplitHeader.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReportsDuplicate(outcome, "o");

        var result = CompileFixLoop.DuplicateLocalFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(), "an unlocatable method must be declined");
        assertEquals(original, Files.readString(root.resolve("rs/SplitHeader.java")),
                "a declined file must be left byte-identical");
    }
}
