package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cleandecompile.PipelineConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the fixer against REAL javac diagnostics (not hand-built ones): the
 * message shapes, line numbers and Object-typed locals are exactly what
 * Stage 4 sees on a decompiled tree.
 */
class ObjectTypedLocalFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    // ---- helpers ----

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static String read(Path root, String rel) throws IOException {
        return Files.readString(root.resolve(rel));
    }

    private JavacRunner.CompileOutcome compile(Path root) throws IOException {
        return javac.compile(root, root.resolveSibling("classes-" + root.getFileName()), List.of(), "17");
    }

    private ObjectTypedLocalFixer.Result fix(Path root) throws IOException {
        var outcome = compile(root);
        return ObjectTypedLocalFixer.tryFixAll(root, bucketer.categorize(outcome.diagnostics()), List.of(), "17");
    }

    // ---- retyping the declaration (sound case) ----

    @Test
    void retypesEveryFlaggedShapeToTheAssignedType(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                import java.util.ArrayList;
                import java.util.Iterator;
                import java.util.List;

                public class Client {
                    static class Node { int field1718; }

                    int[][] table = new int[4][4];
                    List<String> names = new ArrayList<>();
                    Node node = new Node();

                    int run() {
                        Object object = this.table[1];
                        object[0] = 5;
                        int x = object[1] + object.length;
                        Object it = names.iterator();
                        while (it.hasNext()) {
                            it.next();
                        }
                        Object list = new ArrayList<String>();
                        for (String s : list) {
                            System.out.println(s);
                        }
                        Object nd = this.node;
                        return x + nd.field1718;
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/Client.java");

        assertTrue(out.contains("int[] object = this.table[1];"), out);
        assertTrue(out.contains("Iterator<String> it = names.iterator();"), out);
        assertTrue(out.contains("ArrayList<String> list = new ArrayList<String>();"), out);
        assertTrue(out.contains("Client.Node nd = this.node;") || out.contains("Node nd = this.node;"), out);
        assertTrue(result.fixes() >= 4);
        assertTrue(compile(root).success(), "tree should compile after one pass");
    }

    @Test
    void retypesWhenEveryAssignmentIsAssignableEvenAcrossBranches(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.Iterator;
                import java.util.List;

                public class A {
                    int run(List<String> a, List<String> b, boolean flag) {
                        Object v = a.iterator();
                        if (flag) {
                            v = b.iterator();
                        }
                        int n = 0;
                        while (v.hasNext()) {
                            n++;
                            v.next();
                        }
                        return n;
                    }
                }
                """);
        fix(root);
        assertTrue(read(root, "rs/A.java").contains("Iterator<String> v = a.iterator();"));
        assertTrue(compile(root).success());
    }

    // ---- slot reuse: one variable, several unrelated types ----

    @Test
    void slotReuseGetsPerUseCastsFromTheReachingAssignment(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.Iterator;
                import java.util.List;

                public class A {
                    int run(String csv, List<String> names) {
                        Object v = csv.split(",");
                        int n = v.length;
                        Object first = v[0];
                        v = names.iterator();
                        int c = 0;
                        while (v.hasNext()) {
                            c++;
                            v.next();
                        }
                        return n + c + first.hashCode();
                    }
                }
                """);
        fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("Object v = csv.split(\",\");"), "declaration must stay Object: " + out);
        assertTrue(out.contains("((String[]) v).length"), out);
        assertTrue(out.contains("((String[]) v)[0]"), out);
        assertTrue(out.contains("((Iterator<String>) v).hasNext()"), out);
        assertTrue(compile(root).success());
    }

    // ---- soundness: cases where guessing would compile but be wrong ----

    @Test
    void refusesToCastAfterABranchMergeWithDifferentTypes(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.Iterator;
                import java.util.List;

                public class A {
                    int run(List<String> names, boolean flag) {
                        Object v = names;
                        if (flag) {
                            v = names.iterator();
                        }
                        return v.size();
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        var result = fix(root);
        assertEquals(0, result.fixes(), "v is a List OR an Iterator here; no single cast is sound");
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void refusesWhenALoopCarriedReassignmentCanReachTheUse(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.List;

                public class A {
                    int run(List<String> names) {
                        Object v = names.iterator();
                        int n = 0;
                        for (int i = 0; i < 3; i++) {
                            if (v.hasNext()) {
                                n++;
                            }
                            v = names.size();
                        }
                        return n;
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        assertEquals(0, fix(root).fixes());
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void doesNotTrustAnAssignmentBehindAShortCircuit(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.List;

                public class A {
                    int run(List<String> names, boolean flag) {
                        Object v = "seed";
                        boolean ok = flag && (v = names.iterator()) != null;
                        return v.hashCode() + (v.hasNext() ? 1 : 0);
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        assertEquals(0, fix(root).fixes(), "the Iterator assignment may not have executed");
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void leavesLegitimateObjectUsesAlone(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    boolean run(Object o, Object p) {
                        String s = o.toString();
                        return o.equals(p) && o.hashCode() != 0 && p != null && s != null;
                    }
                }
                """);
        assertTrue(compile(root).success());
        assertEquals(0, fix(root).fixes());
    }

    @Test
    void skipsTypesThatCannotBeWrittenAtTheSite(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    int run() {
                        Object o = new Object() {
                            public int foo() {
                                return 1;
                            }
                        };
                        return o.foo();
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        assertEquals(0, fix(root).fixes(), "an anonymous class has no name to cast to");
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void multiDeclaratorsAreNeverRetypedButStillGetCasts(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.List;

                public class A {
                    int run(List<String> names) {
                        Object a = names, b = names;
                        return a.size() + b.hashCode();
                    }
                }
                """);
        fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("Object a = names, b = names;"), "shared type node must stay Object: " + out);
        assertTrue(out.contains("((List<String>) a).size()"), out);
        assertTrue(compile(root).success());
    }

    // ---- passing an Object where the callee demands its own type ----

    @Test
    void retypesALocalPassedWhereTheCalleeDemandsTheAssignedType(@TempDir Path root) throws IOException {
        write(root, "rs/gui/loadouts/Loadout.java", """
                package rs.gui.loadouts;

                public class Loadout {
                }
                """);
        write(root, "rs/gui/loadouts/LoadoutList.java", """
                package rs.gui.loadouts;

                import java.util.ArrayList;

                public class LoadoutList extends ArrayList<Loadout> {
                }
                """);
        write(root, "rs/gui/loadouts/LoadoutFolders.java", """
                package rs.gui.loadouts;

                import java.util.ArrayList;
                import java.util.LinkedHashMap;
                import java.util.List;

                public class LoadoutFolders extends LinkedHashMap<String, LoadoutList> {
                    public void renameLoadout(String string, Loadout loadout, String string2) {
                        Object object2;
                        object2 = new LoadoutList();
                        for (Loadout loadout2 : (LoadoutList)this.get(string)) {
                            ((ArrayList)object2).add(loadout2);
                        }
                        this.put(string, object2);
                        save((List<Loadout>)object2);
                        this.log(object2);
                    }

                    private void log(Object o) {
                    }

                    private void save(List<Loadout> list) {
                    }
                }
                """);
        var result = fix(root);
        String out = read(root, "rs/gui/loadouts/LoadoutFolders.java");
        assertTrue(out.contains("LoadoutList object2;"), out);
        assertEquals(1, result.fixes());
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    // ---- the retype must be sound for EVERY use, not only the flagged one ----

    @Test
    void anInstanceOfThatOnlyCompiledBecauseTheDeclarationWasObjectBlocksTheRetype(@TempDir Path root)
            throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    static int sink(Integer n) {
                        return n;
                    }

                    static int hash(Object o) {
                        return o.hashCode();
                    }

                    int run() {
                        Object v = Integer.valueOf(7);
                        boolean b = v instanceof StringBuilder;
                        int n = this.hash(v);
                        n += this.sink(v);
                        return n + (b ? 1 : 0);
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("Object v = Integer.valueOf(7);"),
                "Integer is final, so 'v instanceof StringBuilder' would stop compiling: " + out);
        assertTrue(out.contains("this.sink(((Integer) v))"), "the per-use cast is the safe fallback: " + out);
        assertTrue(result.fixes() > 0);
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    @Test
    void aCastBetweenTwoFinalTypesBlocksTheRetype(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    static int sink(Integer n) {
                        return n;
                    }

                    static int hash(Object o) {
                        return o.hashCode();
                    }

                    int run() {
                        Object v = Integer.valueOf(7);
                        String s = (String) v;
                        int n = this.hash(v);
                        n += this.sink(v);
                        return n + s.length();
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("Object v = Integer.valueOf(7);"),
                "Integer and String are both final and unrelated, so the cast would stop compiling: " + out);
        assertTrue(out.contains("this.sink(((Integer) v))"), "the per-use cast is the safe fallback: " + out);
        assertTrue(result.fixes() > 0);
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    @Test
    void unflaggedUsesTheNewTypeStillSatisfiesDoNotBlockTheRetype(@TempDir Path root) throws IOException {
        write(root, "rs/gui/loadouts/Loadout.java", """
                package rs.gui.loadouts;

                public class Loadout {
                }
                """);
        write(root, "rs/gui/loadouts/LoadoutList.java", """
                package rs.gui.loadouts;

                import java.util.ArrayList;

                public class LoadoutList extends ArrayList<Loadout> {
                }
                """);
        write(root, "rs/gui/loadouts/LoadoutFolders.java", """
                package rs.gui.loadouts;

                import java.util.LinkedHashMap;

                public class LoadoutFolders extends LinkedHashMap<String, LoadoutList> {
                    public void renameLoadout(String string, Loadout loadout) {
                        Object object2;
                        object2 = new LoadoutList();
                        this.put(string, object2);
                        this.log(object2);
                    }

                    private void log(Object o) {
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/gui/loadouts/LoadoutFolders.java");
        assertTrue(out.contains("LoadoutList object2;"),
                "log(Object) and put(String, LoadoutList) both survive the narrowing: " + out);
        assertEquals(1, result.fixes());
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    @Test
    void thePassFixStillLandsWhenTheCallIsWrappedAcrossLines(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    int take(String s) {
                        return s.length();
                    }

                    int run() {
                        Object v = "seed";
                        int n = this
                                .take(v);
                        return n;
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("String v = \"seed\";"), out);
        assertTrue(result.fixes() > 0);
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    @Test
    void declaresAWildcardCaptureAsTheTypeVariableBoundingIt(@TempDir Path root) throws IOException {
        write(root, "rs/Class306.java", """
                package rs;

                import java.util.Collections;
                import java.util.Comparator;
                import java.util.List;

                public class Class306 {
                    private static <T> int method2594(List<? extends T> var0, T var1, Comparator<? super T> var2) {
                        int var3 = Collections.binarySearch(var0, var1, var2);
                        if (var3 < 0) {
                            return -var3 - 1;
                        } else {
                            for (int var4 = var3 + 1; var4 < var0.size(); var4++) {
                                Object var5 = var0.get(var4);
                                int var6 = var2.compare(var5, var1);
                                if (var6 > 0) {
                                    return var4;
                                }
                            }
                            return var0.size();
                        }
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/Class306.java");
        assertTrue(out.contains("T var5 = var0.get(var4);"), out);
        assertTrue(result.fixes() > 0);
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    @Test
    void refusesWhenTheInitializerHasNoSingleNameableType(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.List;

                public class A {
                    static int take(String s) {
                        return s.length();
                    }

                    int run(List<?> names) {
                        Object v = names.get(0);
                        return take(v);
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        assertEquals(0, fix(root).fixes(), "a capture of an unbounded wildcard has no name to write");
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void refusesASuperWildcardCaptureAsTheDefType(@TempDir Path root) throws IOException {
        write(root, "rs/Class306.java", """
                package rs;

                import java.util.Comparator;
                import java.util.List;

                public class Class306 {
                    private static <T> int method2594(List<? super T> var0, T var1, Comparator<? super T> var2) {
                        for (int var4 = 0; var4 < var0.size(); var4++) {
                            Object var5 = var0.get(var4);
                            int var6 = var2.compare(var5, var1);
                            if (var6 > 0) {
                                return var4;
                            }
                        }
                        return var0.size();
                    }
                }
                """);
        String before = read(root, "rs/Class306.java");
        assertEquals(0, fix(root).fixes(),
                "only '? extends T' bounds the capture by a type variable; '? super T' bounds it from below");
        assertEquals(before, read(root, "rs/Class306.java"));
    }

    @Test
    void aPrimitiveDemandedArgumentAddsNoConstraint(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    static int takeInt(int n) {
                        return n;
                    }

                    static int takeString(String s) {
                        return s.length();
                    }

                    int run() {
                        Object v = "seed";
                        int n = this.takeString(v);
                        n += this.takeInt(v);
                        return n;
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("String v = \"seed\";"),
                "an int parameter is not a reason to refuse the whole declaration: " + out);
        assertTrue(result.fixes() > 0);
        var remaining = compile(root).diagnostics();
        assertEquals(1, remaining.size(), "only the unboxable argument is left: " + remaining);
    }

    @Test
    void refusesATypeVariableThatWouldHaveToUnbox(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    static <T extends Integer> int run(T seed) {
                        Object v = seed;
                        boolean b = v == 1;
                        return b ? 1 : 0;
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        assertTrue(compile(root).diagnostics().stream()
                .anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH).contains("bad operand types")));
        assertEquals(0, fix(root).fixes(),
                "JLS 5.1.8 unboxes boxed types, not type variables: T cannot serve a primitive operand");
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void refusesWhenTheRetypedDeclarationWouldNotFitTheCallee(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    static int take(String s) {
                        return s.length();
                    }

                    int run() {
                        Object v = new StringBuilder();
                        return take(v);
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        assertEquals(0, fix(root).fixes(), "StringBuilder is not a String");
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void leavesPreciseAndGenuinelyObjectDeclarationsUntouched(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.List;

                public class A {
                    static int take(String s) {
                        return s.length();
                    }

                    static int any(Object o) {
                        return o.hashCode();
                    }

                    int run(List<String> names) {
                        String first = names.get(0);
                        Object any = new Object();
                        return take(first) + any(any);
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        assertTrue(compile(root).success());
        assertEquals(0, fix(root).fixes());
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void aFinalInstanceOfTargetBlocksTheRetype(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.ArrayList;

                public class A {
                    int run() {
                        Object v = new ArrayList<String>();
                        if (v instanceof String) {
                            return 1;
                        }
                        return v.size();
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("Object v = new ArrayList<String>();"),
                "String is final, so narrowing to ArrayList<String> makes the instanceof inconvertible: " + out);
        assertTrue(out.contains("((ArrayList<String>) v).size()"),
                "the per-use cast is the safe fallback: " + out);
        assertTrue(result.fixes() > 0);
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    @Test
    void keepsTheRetypeForAPrimitiveComparison(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    int run() {
                        Object v = Integer.valueOf(1);
                        boolean b = v == 1;
                        return b ? 1 : 0;
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("Integer v = Integer.valueOf(1);"),
                "unboxing to the comparison's int is exactly what Integer is for: " + out);
        assertTrue(result.fixes() > 0);
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    @Test
    void keepsTheRetypeWhenTheVariableIsAlsoReturned(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    static int sink(String s) {
                        return s.length();
                    }

                    String ret() {
                        Object v = "seed";
                        this.sink(v);
                        return v;
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("String v = \"seed\";"), out);
        assertTrue(result.fixes() > 0);
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    @Test
    void keepsTheRetypeWhenTheVariableIsAlsoConcatenated(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    static int sink(String s) {
                        return s.length();
                    }

                    String cat() {
                        Object v = "seed";
                        this.sink(v);
                        return "" + v;
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("String v = \"seed\";"), out);
        assertTrue(result.fixes() > 0);
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    @Test
    void keepsTheRetypeWhenTheVariableIsAlsoAnArrayIndex(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    static int sinkNumber(Number n) {
                        return n.intValue();
                    }

                    int idx(int[] arr) {
                        Object v = Integer.valueOf(1);
                        this.sinkNumber(v);
                        return arr[v];
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("Integer v = Integer.valueOf(1);"),
                "Integer is a Number and unboxes to an int index: " + out);
        assertTrue(result.fixes() > 0);
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    @Test
    void refusesToCastToAPrimitiveTheBoxedSlotCannotYield(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    int run() {
                        Object v = 1;
                        boolean b = v == 1;
                        return b ? 1 : 0;
                    }
                }
                """);
        String before = read(root, "rs/A.java");
        assertEquals(0, fix(root).fixes(),
                "the slot holds a boxed Integer: ((int) v) would compile and then throw");
        assertEquals(before, read(root, "rs/A.java"));
    }

    @Test
    void stillCastsToTheBoxedTypeReachingTheDefinition(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    int run(boolean flag) {
                        Object v;
                        if (flag) {
                            v = 1.5;
                        }
                        v = Integer.valueOf(1);
                        boolean b = v == 1;
                        return b ? 1 : 0;
                    }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("Object v;"), "two unrelated assignment types: no single type to declare");
        assertTrue(out.contains("((Integer) v) == 1"),
                "the reaching definition is a boxed Integer, so casting to Integer is safe: " + out);
        assertTrue(result.fixes() > 0);
        assertTrue(compile(root).success(), () -> "tree should compile after one pass: " + out);
    }

    // ---- inference from demand when no assignment has a usable type ----

    @Test
    void infersTheOwnerOfAUniqueFieldNameFromTheSourceTree(@TempDir Path root) throws IOException {
        write(root, "rs/pkg/Class53.java", """
                package rs.pkg;

                public class Class53 {
                    public int field1718;
                    public int field1719;
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                import java.util.List;

                public class Client {
                    @SuppressWarnings("rawtypes")
                    int run(List raw) {
                        Object o = raw.get(0);
                        return o.field1718 + o.field1719;
                    }
                }
                """);
        fix(root);
        String out = read(root, "rs/Client.java");
        assertTrue(out.contains("((rs.pkg.Class53) o).field1718"), out);
        assertTrue(compile(root).success());
    }

    @Test
    void ambiguousFieldNamesAreNotGuessed(@TempDir Path root) throws IOException {
        write(root, "rs/One.java", "package rs;\npublic class One { public int field9; }\n");
        write(root, "rs/Two.java", "package rs;\npublic class Two { public int field9; }\n");
        write(root, "rs/Client.java", """
                package rs;

                import java.util.List;

                public class Client {
                    @SuppressWarnings("rawtypes")
                    int run(List raw) {
                        Object o = raw.get(0);
                        return o.field9;
                    }
                }
                """);
        assertEquals(0, fix(root).fixes());
    }

    @Test
    void nameBasedGuessesNeedStringSpecificOrTwoDemands(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                import java.util.List;

                public class Client {
                    @SuppressWarnings("rawtypes")
                    int run(List raw) {
                        Object s = raw.get(0);
                        Object it = raw.get(1);
                        Object q = raw.get(2);
                        int n = 0;
                        if (s.startsWith("a")) {
                            n++;
                        }
                        while (it.hasNext()) {
                            it.next();
                        }
                        return n + q.size();
                    }
                }
                """);
        fix(root);
        String out = read(root, "rs/Client.java");
        assertTrue(out.contains("((String) s).startsWith(\"a\")"), out);
        assertTrue(out.contains("((java.util.Iterator) it).hasNext()")
                || out.contains("((Iterator) it).hasNext()"), out);
        // size() alone is declared by Collection, Map and library classes alike.
        assertTrue(out.contains("q.size()") && !out.contains("(q)"), out);
        var remaining = compile(root).diagnostics();
        assertEquals(1, remaining.size(), "only the ambiguous q.size() should remain");
    }

    @Test
    void parametersTypedObjectGetCastsNeverRetypes(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    boolean run(Object p) {
                        return p.startsWith("x");
                    }
                }
                """);
        fix(root);
        String out = read(root, "rs/A.java");
        assertTrue(out.contains("boolean run(Object p)"), "signature must not change: " + out);
        assertTrue(out.contains("((String) p).startsWith(\"x\")"), out);
    }

    // ---- loop-level behaviour ----

    @Test
    void isIdempotentAndWithholdsHandledDiagnosticsFromLaterFixers(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                import java.util.List;

                public class A {
                    int run(List<String> names) {
                        Object it = names.iterator();
                        int n = 0;
                        while (it.hasNext()) {
                            n++;
                        }
                        return n;
                    }
                }
                """);
        var outcome = compile(root);
        var bucketed = bucketer.categorize(outcome.diagnostics());
        var first = ObjectTypedLocalFixer.tryFixAll(root, bucketed, List.of(), "17");
        assertTrue(first.fixes() > 0);
        assertTrue(first.unhandled(bucketed).isEmpty(), "every diagnostic here was addressed");

        assertTrue(compile(root).success());
        assertEquals(0, fix(root).fixes(), "second pass over a fixed tree changes nothing");
    }

    @Test
    void unrelatedErrorsProduceNoWork(@TempDir Path root) throws IOException {
        write(root, "rs/A.java", """
                package rs;

                public class A {
                    int run() {
                        return missing;
                    }
                }
                """);
        var result = fix(root);
        assertEquals(0, result.fixes());
        assertTrue(result.handled().isEmpty());
    }

    @Test
    void fullFixLoopConvergesOnAnObjectTypedTree(@TempDir Path temp) throws IOException {
        Path out = temp.resolve("out");
        Path src = out.resolve("src-generated/src/main/java");
        write(src, "rs/Client.java", """
                package rs;

                import java.util.ArrayList;
                import java.util.List;

                public class Client {
                    List<String> names = new ArrayList<>();
                    int[] counts = new int[8];

                    String last() {
                        Object it = names.iterator();
                        String last = null;
                        while (it.hasNext()) {
                            last = it.next();
                        }
                        Object c = counts;
                        c[0] = 1;
                        return last;
                    }
                }
                """);
        var config = new PipelineConfig(null, out, List.of(), PipelineConfig.DEFAULT_TIMEOUT_MS,
                PipelineConfig.DEFAULT_MAX_FIX_ITERATIONS, null, false, "17", "", "", null, null);
        var report = new CompileFixLoop().run(config);
        assertTrue(report.converged(), () -> String.join("\n", report.remainingErrorSummaries()));
        assertFalse(read(src, "rs/Client.java").contains("Object it"));
    }
}
