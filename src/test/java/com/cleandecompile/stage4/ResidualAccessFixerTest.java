package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResidualAccessFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static String messages(JavacRunner.CompileOutcome outcome) {
        return outcome.diagnostics().stream()
                .map(d -> d.getLineNumber() + ": " + d.getMessage(Locale.ENGLISH))
                .reduce((a, b) -> a + " | " + b)
                .orElse("<none>");
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }

    private static void assertReports(JavacRunner.CompileOutcome outcome, String fragment) {
        assertTrue(outcome.diagnostics().stream()
                        .anyMatch(d -> d.getMessage(Locale.ENGLISH).contains(fragment)),
                "expected a diagnostic containing '" + fragment + "', got: " + messages(outcome));
    }

    @Test
    void widensAPackagePrivateMethodSoTheCrossPackageCallCompiles(@TempDir Path root) throws IOException {
        write(root, "rs/pkg41/Class87.java", """
                package rs.pkg41;

                public class Class87 {
                    static Object method3420(Object obj) {
                        return obj;
                    }
                }
                """);
        write(root, "rs/Configuration.java", """
                package rs;

                import rs.pkg41.Class87;

                public class Configuration {
                    public String method(int seed) {
                        return String.valueOf(Class87.method3420(seed));
                    }
                }
                """);
        int before = Files.readAllLines(root.resolve("rs/pkg41/Class87.java")).size();

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReports(outcome, "method3420");

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/pkg41/Class87.java"));
        assertTrue(updated.contains("public static Object method3420(Object obj) {"),
                "package-private method not widened:\n" + updated);
        assertEquals(before, Files.readAllLines(root.resolve("rs/pkg41/Class87.java")).size(),
                "line count must be preserved");
        assertTrue(Files.readString(root.resolve("rs/Configuration.java"))
                        .contains("Class87.method3420(seed)"),
                "the call site must not be rewritten");

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void widensAPackagePrivateFieldAndAPackagePrivateMethod(@TempDir Path root) throws IOException {
        write(root, "rs/pkg41/Class88.java", """
                package rs.pkg41;

                public class Class88 {
                    static String field1626 = "s";

                    static void method3420(int seed) {
                        System.out.println(seed);
                    }
                }
                """);
        write(root, "rs/User.java", """
                package rs;

                import rs.pkg41.Class88;

                public class User {
                    public int method() {
                        return Class88.field1626.length();
                    }

                    public void call() {
                        Class88.method3420(1);
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReports(outcome, "is not public in");
        assertReports(outcome, "field1626");
        assertReports(outcome, "method3420");

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(2, result.fixes(), "expected both members widened, handled: " + result.handled().size());
        String updated = Files.readString(root.resolve("rs/pkg41/Class88.java"));
        assertTrue(updated.contains("public static String field1626 = \"s\";"),
                "package-private field not widened:\n" + updated);
        assertTrue(updated.contains("public static void method3420(int seed) {"),
                "package-private method not widened:\n" + updated);
        assertTrue(Files.readString(root.resolve("rs/User.java")).contains("Class88.method3420(1);"),
                "the call site must not be rewritten");

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void widensFieldsNamedWithUnconventionalIdentifiers(@TempDir Path root) throws IOException {
        write(root, "rs/pkg41/Class87.java", """
                package rs.pkg41;

                public class Class87 {
                    static int DEFAULT_SIZE = 4;
                    static boolean _flag;
                    static String $x = "a";
                }
                """);
        write(root, "rs/Sizes.java", """
                package rs;

                import rs.pkg41.Class87;

                public class Sizes {
                    public int m() {
                        return Class87.DEFAULT_SIZE + (Class87._flag ? 1 : 0) + Class87.$x.length();
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReports(outcome, "DEFAULT_SIZE is not public in");
        assertReports(outcome, "_flag is not public in");
        assertReports(outcome, "$x is not public in");

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(3, result.fixes());
        String updated = Files.readString(root.resolve("rs/pkg41/Class87.java"));
        assertTrue(updated.contains("public static int DEFAULT_SIZE = 4;"),
                "ALL-CAPS field not widened:\n" + updated);
        assertTrue(updated.contains("public static boolean _flag;"),
                "leading-underscore field not widened:\n" + updated);
        assertTrue(updated.contains("public static String $x = \"a\";"),
                "dollar-prefixed field not widened:\n" + updated);

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void declinesANonPublicNestedTypeNamedLikeAField(@TempDir Path root) throws IOException {
        write(root, "rs/pkg42/Holder.java", """
                package rs.pkg42;

                public class Holder {
                    Holder() {
                    }

                    static class inner {
                        static int v;
                    }
                }
                """);
        write(root, "rs/Use.java", """
                package rs;

                import rs.pkg42.Holder;

                public class Use {
                    public int b() {
                        return new Holder.inner().v;
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/pkg42/Holder.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().anyMatch(d ->
                        d.getMessage(Locale.ENGLISH).contains("inner is not public in")),
                "fixture must report the nested type, got: " + messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "a lower-cased nested type is still a type, not a field declaration");
        assertEquals(original, Files.readString(root.resolve("rs/pkg42/Holder.java")),
                "a declined file must be left byte-identical");
    }

    @Test
    void widensOnlyTheClassTheCallSiteUsesWhenTwoShareASimpleName(@TempDir Path root)
            throws IOException {
        write(root, "rs/pkgA/Dup.java", """
                package rs.pkgA;

                public class Dup {
                    static int onlyA;
                }
                """);
        write(root, "rs/pkgB/Dup.java", """
                package rs.pkgB;

                public class Dup {
                    static int onlyA;
                }
                """);
        write(root, "rs/UsesA.java", """
                package rs;

                import rs.pkgA.Dup;

                public class UsesA {
                    int m() {
                        return Dup.onlyA;
                    }
                }
                """);
        String untouched = Files.readString(root.resolve("rs/pkgB/Dup.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReports(outcome, "onlyA is not public in");
        assertReports(outcome, "Dup");

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        assertTrue(Files.readString(root.resolve("rs/pkgA/Dup.java"))
                        .contains("public static int onlyA;"),
                "the class the call site uses was not widened");
        assertEquals(untouched, Files.readString(root.resolve("rs/pkgB/Dup.java")),
                "the same-named class the call site does not use must not be widened");

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void declinesASimpleOwnerNameTheCallSiteDoesNotPinToOneFile(@TempDir Path root) throws IOException {
        write(root, "rs/pkgA/Dup.java", """
                package rs.pkgA;

                public class Dup {
                    static int onlyA;
                }
                """);
        write(root, "rs/pkgB/Dup.java", """
                package rs.pkgB;

                public class Dup {
                    static int onlyA;
                }
                """);
        write(root, "rs/UsesSingle.java", """
                package rs;

                import rs.pkgA.Dup;

                public class UsesSingle {
                    int m() {
                        return Dup.onlyA;
                    }
                }
                """);
        write(root, "rs/UsesWild.java", """
                package rs;

                import rs.pkgA.*;

                public class UsesWild {
                    int m() {
                        return Dup.onlyA;
                    }
                }
                """);
        String first = Files.readString(root.resolve("rs/pkgA/Dup.java"));
        String second = Files.readString(root.resolve("rs/pkgB/Dup.java"));

        var resolver = CompileFixLoop.AccessWidenFixer.ownerResolver(root);
        Path single = root.resolve("rs/UsesSingle.java").toAbsolutePath().normalize();
        Path wild = root.resolve("rs/UsesWild.java").toAbsolutePath().normalize();

        assertEquals(root.resolve("rs/pkgA/Dup.java").toAbsolutePath().normalize(),
                resolver.resolve(single, "Dup"),
                "a single-type import must pin the simple owner name to one file");
        assertNull(resolver.resolve(wild, "Dup"),
                "a wildcard import plus two same-named classes is not a disambiguation");
        assertEquals(resolver.resolve(single, "Dup"), resolver.resolve(single, "Dup"),
                "memoized resolution must agree with the first answer");
        assertEquals(first, Files.readString(root.resolve("rs/pkgA/Dup.java")),
                "resolution alone must not edit any file");
        assertEquals(second, Files.readString(root.resolve("rs/pkgB/Dup.java")),
                "resolution alone must not edit any file");
    }

    @Test
    void handlesTwoNonPublicMembersAndThreeStaleOverridesInOnePass(@TempDir Path root)
            throws IOException {
        write(root, "rs/pkg41/Class87.java", """
                package rs.pkg41;

                public class Class87 {
                    static String field1626 = "s";

                    static Object method3420(Object obj) {
                        return obj;
                    }
                }
                """);
        write(root, "rs/Configuration.java", """
                package rs;

                import rs.pkg41.Class87;

                public class Configuration {
                    public String method(int seed) {
                        return Class87.field1626 + Class87.method3420(seed);
                    }
                }
                """);
        write(root, "rs/pkg46/pkg164/pkg275/pkg292/Class255.java", """
                package rs.pkg46.pkg164.pkg275.pkg292;

                public class Class255 {
                    @Override
                    public int method2150(int seed) {
                        return seed + 1;
                    }

                    @Override
                    public String method2151() {
                        return "x";
                    }

                    @Override
                    public void method2152(int seed, int extra) {
                        System.out.println(seed + extra);
                    }
                }
                """);
        List<String> overrides = Files.readAllLines(root.resolve(
                "rs/pkg46/pkg164/pkg275/pkg292/Class255.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().anyMatch(d ->
                        d.getMessage(Locale.ENGLISH).contains("is not public in")),
                "expected non-public access diagnostics, got: " + messages(outcome));
        assertTrue(outcome.diagnostics().stream().filter(d ->
                        d.getMessage(Locale.ENGLISH)
                                .contains("does not override or implement a method from a supertype"))
                .count() == 3, "expected three stale overrides, got: " + messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(5, result.fixes(), "expected two widenings and three blanked annotations");
        assertEquals(5, result.handled().size());

        String owner = Files.readString(root.resolve("rs/pkg41/Class87.java"));
        assertTrue(owner.contains("public static String field1626 = \"s\";"),
                "the field shape did not widen:\n" + owner);
        assertTrue(owner.contains("public static Object method3420(Object obj) {"),
                "the method shape did not widen:\n" + owner);

        List<String> updated = Files.readAllLines(root.resolve(
                "rs/pkg46/pkg164/pkg275/pkg292/Class255.java"));
        assertEquals(overrides.size(), updated.size(), "line count must be preserved exactly");
        for (int i = 0; i < overrides.size(); i++) {
            if (overrides.get(i).trim().equals("@Override")) {
                assertEquals("", updated.get(i).strip(), "@Override not blanked on line " + (i + 1));
                assertEquals(overrides.get(i).length(), updated.get(i).length(),
                        "line " + (i + 1) + " must keep its width");
            }
        }

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void theSharedRewriterReplacesAPrivateAndAProtectedModifierInPlace(@TempDir Path root)
            throws IOException {
        write(root, "rs/pkg41/Class88.java", """
                package rs.pkg41;

                public class Class88 {
                    private static int field1626;

                    protected static String field1627 = "s";

                    private void method1418(int seed) {
                        System.out.println(seed + field1626);
                    }
                }
                """);

        var shape = CompileFixLoop.AccessWidenFixer.Shape.class;
        assertEquals(CompileFixLoop.AccessWidenFixer.Shape.FIELD,
                CompileFixLoop.AccessWidenFixer.shapeOf(null));
        assertEquals(CompileFixLoop.AccessWidenFixer.Shape.METHOD,
                CompileFixLoop.AccessWidenFixer.shapeOf("(int)"));
        assertTrue(shape.getEnumConstants().length == 2);

        assertTrue(CompileFixLoop.AccessWidenFixer.widenMember(
                root.resolve("rs/pkg41/Class88.java"), "Class88", "field1626", -1,
                CompileFixLoop.AccessWidenFixer.Shape.FIELD));
        assertTrue(CompileFixLoop.AccessWidenFixer.widenMember(
                root.resolve("rs/pkg41/Class88.java"), "Class88", "field1627", -1,
                CompileFixLoop.AccessWidenFixer.Shape.FIELD));
        assertTrue(CompileFixLoop.AccessWidenFixer.widenMember(
                root.resolve("rs/pkg41/Class88.java"), "Class88", "method1418", 1,
                CompileFixLoop.AccessWidenFixer.Shape.METHOD));

        String updated = Files.readString(root.resolve("rs/pkg41/Class88.java"));
        assertTrue(updated.contains("public static int field1626;"),
                "private modifier not replaced in place:\n" + updated);
        assertTrue(updated.contains("public static String field1627 = \"s\";"),
                "protected modifier not replaced in place:\n" + updated);
        assertTrue(updated.contains("public void method1418(int seed) {"),
                "private method not widened:\n" + updated);
        assertFalse(updated.contains("private"), "no private modifier may survive:\n" + updated);
        assertFalse(updated.contains("protected"), "no protected modifier may survive:\n" + updated);

        var after = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void declinesANonPublicClassInAnotherPackage(@TempDir Path root) throws IOException {
        write(root, "rs/pkg42/Color.java", """
                package rs.pkg42;

                class Color {
                    public int f() {
                        return 1;
                    }
                }
                """);
        write(root, "rs/Use.java", """
                package rs;

                import rs.pkg42.Color;

                public class Use {
                    public int a() {
                        return new Color().f();
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/pkg42/Color.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().anyMatch(d ->
                        d.getMessage(Locale.ENGLISH).contains("is not public in rs.pkg42")),
                "fixture must report the class visibility, got: " + messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(), "a type's visibility is not a member widening");
        assertEquals(original, Files.readString(root.resolve("rs/pkg42/Color.java")),
                "a declined file must be left byte-identical");
    }

    @Test
    void declinesANonPublicNestedTypeInsteadOfWideningTheEnclosingConstructor(@TempDir Path root)
            throws IOException {
        write(root, "rs/pkg42/Holder.java", """
                package rs.pkg42;

                public class Holder {
                    Holder() {
                    }

                    static class Inner {
                        public int g() {
                            return 2;
                        }
                    }
                }
                """);
        write(root, "rs/Use.java", """
                package rs;

                import rs.pkg42.Holder;

                public class Use {
                    public int b() {
                        return new Holder.Inner().g();
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/pkg42/Holder.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().anyMatch(d ->
                        d.getMessage(Locale.ENGLISH).contains("Inner is not public in")),
                "fixture must report the nested type visibility, got: " + messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(), "a nested type's visibility is not a member widening");
        assertEquals(original, Files.readString(root.resolve("rs/pkg42/Holder.java")),
                "the enclosing type's constructor must not be widened instead");
    }

    @Test
    void widensAPrivateMethodNamedByHasPrivateAccessInAnotherPackage(@TempDir Path root)
            throws IOException {
        write(root, "rs/pkg42/Owner1.java", """
                package rs.pkg42;

                public class Owner1 {
                    private void privMethod() {
                        System.out.println(1);
                    }

                    public void pub() {
                        System.out.println(2);
                    }
                }
                """);
        write(root, "rs/Use.java", """
                package rs;

                import rs.pkg42.Owner1;

                public class Use {
                    public void c() {
                        Owner1 o = new Owner1();
                        o.privMethod();
                        o.pub();
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().anyMatch(d ->
                        d.getMessage(Locale.ENGLISH).contains("privMethod() has private access in")),
                "fixture must report the private access, got: " + messages(outcome));

        var fixed = CompileFixLoop.AccessWidenFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, fixed);
        String updated = Files.readString(root.resolve("rs/pkg42/Owner1.java"));
        assertTrue(updated.contains("public void privMethod() {"),
                "private method not widened:\n" + updated);
        assertTrue(updated.contains("public void pub() {"),
                "the already-public method must be left alone:\n" + updated);
        assertEquals(1, countOccurrences(updated, "void pub()"),
                "the public method must not be rewritten:\n" + updated);
        assertFalse(updated.contains("private"), "no private modifier may survive:\n" + updated);

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void resolvesASimpleOwnerNameToItsUniqueSourceFile(@TempDir Path root) throws IOException {
        write(root, "rs/pkg42/Owner1.java", """
                package rs.pkg42;

                public class Owner1 {
                }
                """);
        write(root, "rs/Use.java", """
                package rs;

                public class Use {
                }
                """);
        Path callSite = root.resolve("rs/Use.java").toAbsolutePath().normalize();
        var resolver = CompileFixLoop.AccessWidenFixer.ownerResolver(root);

        assertEquals(root.resolve("rs/pkg42/Owner1.java").toAbsolutePath().normalize(),
                resolver.resolve(callSite, "rs.pkg42.Owner1"),
                "a qualified owner name must resolve straight through");
        assertEquals(root.resolve("rs/pkg42/Owner1.java").toAbsolutePath().normalize(),
                resolver.resolve(callSite, "Owner1"),
                "a simple owner name must resolve by unique file");
        assertEquals(root.resolve("rs/pkg42/Owner1.java").toAbsolutePath().normalize(),
                resolver.resolve(callSite, "Owner1"),
                "a repeated resolution must agree with the first");
        assertNull(resolver.resolve(callSite, "rs.pkg42"),
                "a package name is not a type and must not resolve to a source file");
    }

    @Test
    void widensAPrivateFieldNamedByHasPrivateAccessInAnotherPackage(@TempDir Path root)
            throws IOException {
        write(root, "rs/pkg42/Owner1.java", """
                package rs.pkg42;

                public class Owner1 {
                    private static int privField;
                }
                """);
        write(root, "rs/Use.java", """
                package rs;

                import rs.pkg42.Owner1;

                public class Use {
                    public int c() {
                        return Owner1.privField;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().anyMatch(d ->
                        d.getMessage(Locale.ENGLISH).contains("privField has private access in")),
                "fixture must report the private access, got: " + messages(outcome));

        var fixed = CompileFixLoop.AccessWidenFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, fixed);
        String updated = Files.readString(root.resolve("rs/pkg42/Owner1.java"));
        assertTrue(updated.contains("public static int privField;"),
                "private field not widened:\n" + updated);

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void usesTheCapturedParameterListToPickTheRightOverload(@TempDir Path root) throws IOException {
        write(root, "rs/pkg42/Owner2.java", """
                package rs.pkg42;

                public class Owner2 {
                    static void dup(int seed) {
                        System.out.println(seed);
                    }

                    static void dup(String name, int extra) {
                        System.out.println(name + extra);
                    }
                }
                """);
        write(root, "rs/Use.java", """
                package rs;

                import rs.pkg42.Owner2;

                public class Use {
                    public void b() {
                        Owner2.dup(1);
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().anyMatch(d ->
                        d.getMessage(Locale.ENGLISH).contains("dup(int) is not public in")),
                "fixture must name the inapplicable overload, got: " + messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/pkg42/Owner2.java"));
        assertTrue(updated.contains("public static void dup(int seed) {"),
                "the named overload was not widened:\n" + updated);
        assertTrue(updated.contains("static void dup(String name, int extra) {"),
                "the other overload must be untouched:\n" + updated);

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void declinesWhenTwoOverloadsShareTheNamedParameterCount(@TempDir Path root) throws IOException {
        write(root, "rs/pkg42/Owner2.java", """
                package rs.pkg42;

                public class Owner2 {
                    static void pick(int seed) {
                        System.out.println(seed);
                    }

                    static void pick(long seed) {
                        System.out.println(seed);
                    }
                }
                """);
        write(root, "rs/Use.java", """
                package rs;

                import rs.pkg42.Owner2;

                public class Use {
                    public void a() {
                        Owner2.pick(1);
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/pkg42/Owner2.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().anyMatch(d ->
                        d.getMessage(Locale.ENGLISH).contains("pick(int) is not public in")),
                "fixture must name an inapplicable overload, got: " + messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "a parameter count matching two overloads is not a disambiguation");
        assertEquals(original, Files.readString(root.resolve("rs/pkg42/Owner2.java")),
                "a declined file must be left byte-identical");
    }

    @Test
    void widensAPackagePrivateConstructor(@TempDir Path root) throws IOException {
        write(root, "rs/pkg41/Class89.java", """
                package rs.pkg41;

                public class Class89 {
                    Class89() {
                    }
                }
                """);
        write(root, "rs/Factory.java", """
                package rs;

                import rs.pkg41.Class89;

                public class Factory {
                    public Object create() {
                        return new Class89();
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReports(outcome, "is not public in");

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        String updated = Files.readString(root.resolve("rs/pkg41/Class89.java"));
        assertTrue(updated.contains("public Class89() {"),
                "package-private constructor not widened:\n" + updated);

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void blanksAStaleOverrideAnnotationSoTheMethodCompiles(@TempDir Path root) throws IOException {
        write(root, "rs/pkg46/pkg164/pkg275/pkg292/Class255.java", """
                package rs.pkg46.pkg164.pkg275.pkg292;

                public class Class255 {
                    @Override
                    public int method2150(int seed) {
                        return seed + 1;
                    }

                    @Override
                    public String method2151() {
                        return "x";
                    }
                }
                """);
        List<String> original = Files.readAllLines(root.resolve(
                "rs/pkg46/pkg164/pkg275/pkg292/Class255.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().filter(d ->
                        d.getMessage(Locale.ENGLISH)
                                .contains("does not override or implement a method from a supertype"))
                .count() >= 2, "fixture must report both stale overrides, got: " + messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(2, result.fixes());
        List<String> updated = Files.readAllLines(root.resolve(
                "rs/pkg46/pkg164/pkg275/pkg292/Class255.java"));
        assertEquals(original.size(), updated.size(), "line count must be preserved exactly");
        for (int i = 0; i < original.size(); i++) {
            if (original.get(i).trim().equals("@Override")) {
                assertEquals("", updated.get(i).strip(), "@Override not blanked on line " + (i + 1));
                assertEquals(original.get(i).length(), updated.get(i).length(),
                        "line " + (i + 1) + " must keep its width");
            }
        }
        assertTrue(updated.get(4).contains("public int method2150(int seed) {"),
                "method declaration must survive:\n" + String.join("\n", updated));

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void declinesAStaleOverrideSeparatedFromItsDeclarationByABlankLine(@TempDir Path root)
            throws IOException {
        write(root, "rs/Class257.java", """
                package rs;

                public class Class257 {
                    @Override

                    public void method2150() {
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/Class257.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReports(outcome, "does not override or implement a method from a supertype");

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "a blank line ends the forward scan, so the annotation is not adjacent to a declaration");
        assertEquals(original, Files.readString(root.resolve("rs/Class257.java")),
                "a declined file must be left byte-identical");
    }

    @Test
    void blanksOnlyTheTargetAnnotationWhenAFollowingMethodIsBlankSeparated(@TempDir Path root)
            throws IOException {
        write(root, "rs/Class259.java", """
                package rs;

                public class Class259 extends Base {
                    @Override
                    public int method2150(int seed) {
                        return seed + 1;
                    }

                    @Override
                    public String toString() {
                        return "x";
                    }
                }

                class Base {
                    public String toString() {
                        return "";
                    }
                }
                """);
        List<String> original = Files.readAllLines(root.resolve("rs/Class259.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().filter(d ->
                        d.getMessage(Locale.ENGLISH)
                                .contains("does not override or implement a method from a supertype"))
                .count() == 1, "only method2150 is stale, got: " + messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(1, result.fixes());
        List<String> updated = Files.readAllLines(root.resolve("rs/Class259.java"));
        assertEquals(original.size(), updated.size(), "line count must be preserved exactly");
        assertEquals("", updated.get(3).strip(), "the stale @Override was not blanked");
        assertEquals("@Override", updated.get(8).strip(),
                "a live @Override on the following method must survive");
        assertTrue(updated.get(4).contains("public int method2150(int seed) {"),
                "the target declaration must survive");

        var after = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(after.success(), "expected clean recompile, got: " + messages(after));
    }

    @Test
    void declinesWhenTheDeclarationHasNoAdjacentOverrideAnnotation(@TempDir Path root) throws IOException {
        write(root, "rs/Class258.java", """
                package rs;

                public class Class258 {
                    @Override
                    @Deprecated
                    public int method2150(int seed) {
                        return seed + 1;
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/Class258.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertReports(outcome, "does not override or implement a method from a supertype");

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(),
                "a declaration whose immediately previous nonblank line is not @Override must be declined");
        assertEquals(original, Files.readString(root.resolve("rs/Class258.java")),
                "a declined file must be left byte-identical");
    }

    @Test
    void declinesWhenTheDiagnosticTextOnlyAppearsInsideAStringLiteral(@TempDir Path root)
            throws IOException {
        write(root, "rs/pkg41/Class87.java", """
                package rs.pkg41;

                public class Class87 {
                    public static final String NOTE =
                            "method3420(Object) is not public in Class87; cannot be accessed from outside package";
                }
                """);
        String original = Files.readString(root.resolve("rs/pkg41/Class87.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.success(), "fixture must compile cleanly, got: " + messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes());
        assertEquals(original, Files.readString(root.resolve("rs/pkg41/Class87.java")),
                "masked text must never be treated as a declaration");
    }

    @Test
    void isIdempotentForAnAlreadyWidenedMemberAndAnAlreadyBlankedAnnotation(@TempDir Path root)
            throws IOException {
        write(root, "rs/pkg41/Class87.java", """
                package rs.pkg41;

                public class Class87 {
                    static Object method3420(Object obj) {
                        return obj;
                    }
                }
                """);
        write(root, "rs/Configuration.java", """
                package rs;

                import rs.pkg41.Class87;

                public class Configuration {
                    public String method(int seed) {
                        return String.valueOf(Class87.method3420(seed));
                    }
                }
                """);
        write(root, "rs/Class255.java", """
                package rs;

                public class Class255 {
                    @Override
                    public int method2150(int seed) {
                        return seed + 1;
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var all = bucketer.categorize(outcome.diagnostics());

        var first = CompileFixLoop.ResidualAccessFixer.tryFixAll(root, all);
        assertEquals(2, first.fixes());
        String widened = Files.readString(root.resolve("rs/pkg41/Class87.java"));
        String blanked = Files.readString(root.resolve("rs/Class255.java"));

        var second = CompileFixLoop.ResidualAccessFixer.tryFixAll(root, all);
        assertEquals(0, second.fixes(), "a second pass over the same diagnostics must be a no-op");
        assertEquals(widened, Files.readString(root.resolve("rs/pkg41/Class87.java")));
        assertEquals(blanked, Files.readString(root.resolve("rs/Class255.java")));

        var clean = javac.compile(root, root.resolveSibling("classes2"), List.of(), "17");
        assertTrue(clean.success(), "expected clean compile, got: " + messages(clean));
        var third = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(clean.diagnostics()));
        assertEquals(0, third.fixes(), "a clean tree must produce no fixes");
        assertEquals(widened, Files.readString(root.resolve("rs/pkg41/Class87.java")));
        assertEquals(blanked, Files.readString(root.resolve("rs/Class255.java")));
    }

    @Test
    void leavesACleanFileUntouched(@TempDir Path root) throws IOException {
        write(root, "rs/Clean.java", """
                package rs;

                public class Clean {
                    @Override
                    public String toString() {
                        return "clean";
                    }
                }
                """);
        String original = Files.readString(root.resolve("rs/Clean.java"));

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.success(), "fixture must compile cleanly, got: " + messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes());
        assertTrue(result.handled().isEmpty());
        assertEquals(original, Files.readString(root.resolve("rs/Clean.java")),
                "a file with no matching diagnostic must not be edited");
    }

    @Test
    void unhandledDropsHandledDiagnosticsAndKeepsTheRest(@TempDir Path root) throws IOException {
        write(root, "rs/pkg41/Class87.java", """
                package rs.pkg41;

                public class Class87 {
                    static Object method3420(Object obj) {
                        return obj;
                    }
                }
                """);
        write(root, "rs/Mixed.java", """
                package rs;

                import rs.pkg41.Class87;

                public class Mixed {
                    Missing field1;

                    public String method(int seed) {
                        return String.valueOf(Class87.method3420(seed));
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        var all = bucketer.categorize(outcome.diagnostics());
        assertTrue(all.stream().anyMatch(b ->
                b.category() == DiagnosticBucketer.Category.UNRESOLVED_SYMBOL), messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(root, all);

        assertEquals(1, result.fixes());
        assertEquals(1, result.handled().size());
        var left = result.unhandled(all);
        assertEquals(all.size() - 1, left.size(), "only handled diagnostics are withheld");
        assertFalse(left.stream().anyMatch(b -> result.handled().contains(b.diagnostic())));
        assertTrue(left.stream().anyMatch(b ->
                        b.category() == DiagnosticBucketer.Category.UNRESOLVED_SYMBOL),
                "unrelated diagnostics must remain available to later fixers");
        assertTrue(Files.readString(root.resolve("rs/pkg41/Class87.java"))
                .contains("public static Object method3420(Object obj) {"),
                "the widen fix must still be applied");
    }

    @Test
    void declinesWhenTheOwnersSourceFileIsNotUnderTheSourceRoot(@TempDir Path root) throws IOException {
        write(root, "rs/Orphan.java", """
                package rs;

                public class Orphan {
                    public String method() {
                        return external.Gone.helper();
                    }
                }
                """);

        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), "17");
        assertTrue(outcome.diagnostics().stream().anyMatch(d ->
                        d.getMessage(Locale.ENGLISH).contains("external")),
                "fixture sanity, got: " + messages(outcome));

        var result = CompileFixLoop.ResidualAccessFixer.tryFixAll(
                root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes(), "an unresolvable owner must be declined, not guessed");
    }
}
