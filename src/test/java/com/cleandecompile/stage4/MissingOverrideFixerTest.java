package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * "X is not abstract and does not override abstract method m(P) in Owner":
 * the Class447/Class286 (LayoutManager2) and Class810 (shaded Guava
 * predicate) remainder shapes.
 */
class MissingOverrideFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    /** Runs the fixer across rounds like the loop does (javac reports one missing method per class). */
    private int fixToFixpoint(Path root, int maxRounds) throws IOException {
        int total = 0;
        for (int i = 0; i < maxRounds; i++) {
            var outcome = javac.compile(root, root.resolveSibling("classes" + i), List.of(), "17");
            if (outcome.success()) break;
            var result = MissingOverrideFixer.tryFixAll(root, bucketer.categorize(outcome.diagnostics()), List.of());
            if (result.fixes() == 0) break;
            total += result.fixes();
        }
        return total;
    }

    @Test
    void delegatesToTheRenamedOverride(@TempDir Path root) throws IOException {
        // Class810 shape: the implementation survives as methodNNN because Stage 0 could not prove
        // it overrode the (generic) library method.
        write(root, "rs/Class810.java", """
                package rs;

                import java.util.function.Predicate;

                public class Class810 implements Predicate<Character> {
                    public boolean method2091(Character value) {
                        return value != null && Character.isLetter(value);
                    }
                }
                """);
        assertEquals(1, fixToFixpoint(root, 3));
        String updated = Files.readString(root.resolve("rs/Class810.java"));
        assertTrue(updated.contains("@Override"), updated);
        assertTrue(updated.contains("public boolean test(Character value) {"), updated);
        assertTrue(updated.contains("return method2091(value);"), updated);
        assertTrue(updated.contains("stage4: delegates to method2091"), updated);
        // The original body is untouched.
        assertTrue(updated.contains("Character.isLetter(value)"), updated);
        assertTrue(javac.compile(root, root.resolveSibling("final"), List.of(), "17").success(), updated);
    }

    @Test
    void delegatesForVoidMethods(@TempDir Path root) throws IOException {
        write(root, "rs/Job.java", """
                package rs;

                public class Job implements Runnable {
                    public int count;
                    public void method77() { count++; }
                }
                """);
        // A void junk method with no params matches Runnable.run().
        assertEquals(1, fixToFixpoint(root, 3));
        String updated = Files.readString(root.resolve("rs/Job.java"));
        assertTrue(updated.contains("public void run() {"), updated);
        assertTrue(updated.contains("method77();"), updated);
        assertTrue(javac.compile(root, root.resolveSibling("final"), List.of(), "17").success(), updated);
    }

    @Test
    void refusesWhenTheReturnTypeDisagreesWithTheOwner(@TempDir Path root) throws IOException {
        write(root, "rs/Wrong.java", """
                package rs;

                import java.util.function.Predicate;

                public class Wrong implements Predicate<Character> {
                    public int method5(Character value) { return 1; }
                }
                """);
        assertEquals(0, fixToFixpoint(root, 2), "int method cannot stand in for a boolean predicate");
    }

    @Test
    void refusesWhenTwoRenamedCandidatesFit(@TempDir Path root) throws IOException {
        write(root, "rs/Two.java", """
                package rs;

                import java.util.function.Predicate;

                public class Two implements Predicate<Character> {
                    public boolean method1(Character value) { return true; }
                    public boolean method2(Character value) { return false; }
                }
                """);
        assertEquals(0, fixToFixpoint(root, 2), "which candidate is the override would be a guess");
    }

    @Test
    void refusesWhenTheNameIsNotAnAutoRenamedOne(@TempDir Path root) throws IOException {
        write(root, "rs/Helper.java", """
                package rs;

                import java.util.function.Predicate;

                public class Helper implements Predicate<Character> {
                    public boolean isVowel(Character value) { return "aeiou".indexOf(value) >= 0; }
                }
                """);
        assertEquals(0, fixToFixpoint(root, 2), "a hand-named helper is not evidence of a renamed override");
    }

    @Test
    void synthesizesCanonicalLayoutManagerDefaultWithMarker(@TempDir Path root) throws IOException {
        // Class447/Class286 shape: everything but maximumLayoutSize is implemented.
        write(root, "rs/Class447.java", """
                package rs;

                import java.awt.Component;
                import java.awt.Container;
                import java.awt.Dimension;
                import java.awt.LayoutManager2;

                public class Class447 implements LayoutManager2 {
                    public void addLayoutComponent(Component c, Object o) { }
                    public void addLayoutComponent(String s, Component c) { }
                    public void removeLayoutComponent(Component c) { }
                    public Dimension preferredLayoutSize(Container c) { return new Dimension(1, 1); }
                    public Dimension minimumLayoutSize(Container c) { return new Dimension(1, 1); }
                    public void layoutContainer(Container c) { }
                    public float getLayoutAlignmentX(Container c) { return 0; }
                    public float getLayoutAlignmentY(Container c) { return 0; }
                    public void invalidateLayout(Container c) { }
                }
                """);
        assertEquals(1, fixToFixpoint(root, 3));
        String updated = Files.readString(root.resolve("rs/Class447.java"));
        assertTrue(updated.contains("TODO(stage4): synthesized"), updated);
        assertTrue(updated.contains("public java.awt.Dimension maximumLayoutSize(java.awt.Container stage4Container)"),
                updated);
        assertTrue(updated.contains("new java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE)"), updated);
        assertTrue(javac.compile(root, root.resolveSibling("final"), List.of(), "17").success(), updated);
    }

    @Test
    void prefersDelegationOverTheCuratedDefault(@TempDir Path root) throws IOException {
        write(root, "rs/Class286.java", """
                package rs;

                import java.awt.Component;
                import java.awt.Container;
                import java.awt.Dimension;
                import java.awt.LayoutManager2;

                public class Class286 implements LayoutManager2 {
                    public void addLayoutComponent(Component c, Object o) { }
                    public void addLayoutComponent(String s, Component c) { }
                    public void removeLayoutComponent(Component c) { }
                    public Dimension preferredLayoutSize(Container c) { return new Dimension(1, 1); }
                    public Dimension minimumLayoutSize(Container c) { return new Dimension(1, 1); }
                    public void layoutContainer(Container c) { }
                    public float getLayoutAlignmentX(Container c) { return 0; }
                    public float getLayoutAlignmentY(Container c) { return 0; }
                    public void invalidateLayout(Container c) { }
                    public Dimension method412(Container c) { return new Dimension(640, 480); }
                }
                """);
        assertEquals(1, fixToFixpoint(root, 3));
        String updated = Files.readString(root.resolve("rs/Class286.java"));
        assertTrue(updated.contains("return method412(c);"), updated);
        assertTrue(!updated.contains("TODO(stage4)"), "real body exists -- no invented default:\n" + updated);
    }

    @Test
    void doesNotInventStubsForUncuratedMethods(@TempDir Path root) throws IOException {
        write(root, "rs/Lazy.java", """
                package rs;

                import java.util.function.Supplier;

                public class Lazy implements Supplier<String> {
                }
                """);
        assertEquals(0, fixToFixpoint(root, 2), "no evidence of an implementation and no canonical default");
    }

    @Test
    void addsOneMethodPerClassPerRound(@TempDir Path root) throws IOException {
        // Inventing every missing hook at once could shadow an implementation inherited from a superclass.
        write(root, "rs/Bare.java", """
                package rs;

                import java.awt.Component;
                import java.awt.Container;
                import java.awt.Dimension;
                import java.awt.LayoutManager2;

                public class Bare implements LayoutManager2 {
                    public void addLayoutComponent(Component c, Object o) { }
                    public void addLayoutComponent(String s, Component c) { }
                    public void removeLayoutComponent(Component c) { }
                    public Dimension preferredLayoutSize(Container c) { return null; }
                    public Dimension minimumLayoutSize(Container c) { return null; }
                    public void layoutContainer(Container c) { }
                }
                """);
        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var result = MissingOverrideFixer.tryFixAll(root, bucketer.categorize(outcome.diagnostics()), List.of());
        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/Bare.java"));
        int stubs = updated.split("TODO\\(stage4\\)", -1).length - 1;
        assertEquals(1, stubs, updated);
        // The remaining hooks are added on later rounds, one at a time, until the class is complete.
        assertEquals(3, fixToFixpoint(root, 6));
        assertTrue(javac.compile(root, root.resolveSibling("final"), List.of(), "17").success());
    }

    @Test
    void handlesNestedClassesAndCrlf(@TempDir Path root) throws IOException {
        String source = "package rs;\r\n\r\npublic class Outer {\r\n    static class Inner implements Runnable {\r\n"
                + "        void method9() { }\r\n    }\r\n}\r\n";
        write(root, "rs/Outer.java", source);
        assertEquals(1, fixToFixpoint(root, 3));
        String updated = Files.readString(root.resolve("rs/Outer.java"));
        assertTrue(updated.contains("public void run() {"), updated);
        assertTrue(!updated.replace("\r\n", "").contains("\n"), "line endings must stay CRLF");
        assertTrue(javac.compile(root, root.resolveSibling("final"), List.of(), "17").success(), updated);
    }
}
