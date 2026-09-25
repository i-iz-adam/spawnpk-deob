package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemberResolutionFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

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

    private CompileFixLoop.MemberResolutionFixer.Result fix(Path root) throws IOException {
        return fix(root, compile(root));
    }

    private CompileFixLoop.MemberResolutionFixer.Result fix(Path root,
                                                            JavacRunner.CompileOutcome outcome) throws IOException {
        return CompileFixLoop.MemberResolutionFixer.tryFixAll(root, bucketer.categorize(outcome.diagnostics()));
    }

    private static void writeHierarchy(Path root) throws IOException {
        write(root, "rs/Class33.java", """
                package rs;

                public class Class33 {
                    public int field125;
                }
                """);
        write(root, "rs/Class36.java", """
                package rs;

                public class Class36 extends Class33 {
                }
                """);
        write(root, "rs/Class43.java", """
                package rs;

                public final class Class43 extends Class36 {
                    public String field323;
                }
                """);
    }


    @Test
    void castsAnObjectReceiverToTheUniqueMethodOwner(@TempDir Path root) throws IOException {
        write(root, "rs/Class305.java", "package rs;\npublic class Class305 {\n}\n");
        write(root, "rs/Class303.java", """
                package rs;

                public class Class303 {
                    public void method1030(Class305 var1) {
                    }
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    void run() {
                        Object var13 = new Class303();
                        var13.method1030(new Class305());
                    }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        assertTrue(read(root, "rs/Client.java").contains("((Class303) var13).method1030(new Class305());"),
                read(root, "rs/Client.java"));
        assertTrue(compile(root).success(), read(root, "rs/Client.java"));
    }

    @Test
    void arityDisambiguatesOverloadedMethodOwners(@TempDir Path root) throws IOException {
        write(root, "rs/Class305.java", "package rs;\npublic class Class305 {\n}\n");
        write(root, "rs/Class303.java", """
                package rs;

                public class Class303 {
                    public void method1030() {
                    }
                }
                """);
        write(root, "rs/Class404.java", """
                package rs;

                public class Class404 {
                    public void method1030(Class305 var1) {
                    }
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    void run() {
                        Object var13 = new Class404();
                        var13.method1030(new Class305());
                    }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        assertTrue(read(root, "rs/Client.java").contains("((Class404) var13).method1030(new Class305());"),
                read(root, "rs/Client.java"));
        assertTrue(compile(root).success(), read(root, "rs/Client.java"));
    }


    @Test
    void castsATypedReceiverToTheUniqueFieldOwner(@TempDir Path root) throws IOException {
        writeHierarchy(root);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    int run(Class36 class36) {
                        return class36.field323.length();
                    }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        assertTrue(read(root, "rs/Client.java").contains("((Class43) class36).field323.length()"),
                read(root, "rs/Client.java"));
        assertTrue(compile(root).success(), read(root, "rs/Client.java"));
    }

    @Test
    void repeatedMemberOnOneLineIsOneReportedFix(@TempDir Path root) throws IOException {
        writeHierarchy(root);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    boolean run(Class36 class36) {
                        return class36.field323 == null || class36.field323.length() == 0;
                    }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes(), "one line rewritten is one fix");
        String out = read(root, "rs/Client.java");
        assertTrue(out.contains("((Class43) class36).field323 == null || ((Class43) class36).field323.length() == 0"),
                out);
        assertTrue(compile(root).success(), out);
    }


    @Test
    void qualifiesAnUnqualifiedUniqueStaticFieldInItsOwnPackage(@TempDir Path root) throws IOException {
        write(root, "rs/pkg46/pkg164/pkg275/pkg297/Class275.java", """
                package rs.pkg46.pkg164.pkg275.pkg297;

                public final class Class275 {
                    public static final Class275 field2831 = new Class275();
                    public static final Class275 field2832 = new Class275();
                }
                """);
        write(root, "rs/pkg46/pkg164/pkg275/pkg297/Class272.java", """
                package rs.pkg46.pkg164.pkg275.pkg297;

                public class Class272 {
                    static int field2835;

                    int run(Class275 class275) {
                        if (class275 == field2832) {
                            field2835 = 1;
                        }
                        return field2835;
                    }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        assertTrue(read(root, "rs/pkg46/pkg164/pkg275/pkg297/Class272.java")
                .contains("if (class275 == Class275.field2832) {"),
                read(root, "rs/pkg46/pkg164/pkg275/pkg297/Class272.java"));
        assertTrue(compile(root).success());
    }

    @Test
    void qualifiesAnUnqualifiedUniqueStaticFieldWithAFullyQualifiedOwner(@TempDir Path root) throws IOException {
        write(root, "rs/pkg46/pkg164/pkg275/pkg297/Class275.java", """
                package rs.pkg46.pkg164.pkg275.pkg297;

                public final class Class275 {
                    public static final Class275 field2831 = new Class275();
                    public static final Class275 field2832 = new Class275();
                }
                """);
        write(root, "rs/interfaces/DevCommands.java", """
                package rs.interfaces;

                public class DevCommands {
                    static int field2835;

                    int run(Object class275) {
                        if (class275 == field2832) {
                            field2835 = 1;
                        }
                        return field2835;
                    }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        assertTrue(read(root, "rs/interfaces/DevCommands.java")
                        .contains("if (class275 == rs.pkg46.pkg164.pkg275.pkg297.Class275.field2832) {"),
                read(root, "rs/interfaces/DevCommands.java"));
        assertTrue(compile(root).success());
    }

    @Test
    void usesTheSimpleNameWhenTheOwnerIsAlreadyImported(@TempDir Path root) throws IOException {
        write(root, "rs/pkg46/pkg164/pkg275/pkg297/Class275.java", """
                package rs.pkg46.pkg164.pkg275.pkg297;

                public final class Class275 {
                    public static final Class275 field2832 = new Class275();
                }
                """);
        write(root, "rs/interfaces/DevCommands.java", """
                package rs.interfaces;

                import rs.pkg46.pkg164.pkg275.pkg297.Class275;

                public class DevCommands {
                    int run(Object class275) {
                        return class275 == field2832 ? 1 : 0;
                    }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        assertTrue(read(root, "rs/interfaces/DevCommands.java").contains("class275 == Class275.field2832"),
                read(root, "rs/interfaces/DevCommands.java"));
        assertTrue(compile(root).success());
    }

    @Test
    void neverQualifiesAnUnqualifiedInstanceField(@TempDir Path root) throws IOException {
        writeHierarchy(root);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    int run(Object class36) {
                        return ((Class36) class36).field323.length();
                    }
                }
                """);
        String before = read(root, "rs/Client.java");

        assertEquals(0, fix(root).fixes(), "an instance field has no static owner to qualify through");
        assertEquals(before, read(root, "rs/Client.java"));
    }

    @Test
    void neverQualifiesAnUnqualifiedInstanceFieldReportedInClassScope(@TempDir Path root) throws IOException {
        write(root, "rs/pkg34/Class43.java", """
                package rs.pkg34;

                public class Class43 {
                    public String field323;
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    int run() {
                        return field323.length();
                    }
                }
                """);
        String before = read(root, "rs/Client.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("location: class rs.Client")),
                "the fixture must produce the location: class shape");

        assertEquals(0, fix(root).fixes(), "field323 is an instance field of Class43");
        assertEquals(before, read(root, "rs/Client.java"));
    }


    @Test
    void refusesToCastBetweenUnrelatedSiblingTypes(@TempDir Path root) throws IOException {
        write(root, "rs/Class33.java", """
                package rs;

                public class Class33 {
                    public int field125;
                }
                """);
        write(root, "rs/Class36.java", """
                package rs;

                public class Class36 extends Class33 {
                }
                """);
        write(root, "rs/Class43.java", """
                package rs;

                public final class Class43 extends Class33 {
                    public String field323;
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    int run(Class36 class36) {
                        return class36.field323.length();
                    }
                }
                """);
        String before = read(root, "rs/Client.java");

        assertEquals(0, fix(root).fixes(),
                "Class43 is a sibling of Class36, not a subtype: the cast would not even compile");
        assertEquals(before, read(root, "rs/Client.java"));
    }

    @Test
    void castsWhenTheOwnerIsASubtypeFurtherUpTheDeclaredTypeChain(@TempDir Path root) throws IOException {
        write(root, "rs/Class33.java", """
                package rs;

                public class Class33 {
                    public int field125;
                }
                """);
        write(root, "rs/Class36.java", """
                package rs;

                public class Class36 extends Class33 {
                }
                """);
        write(root, "rs/Class43.java", """
                package rs;

                public final class Class43 extends Class36 {
                    public String field323;
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    int run(Class33 class33) {
                        return class33.field323.length();
                    }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes(), "Class43 is a transitive subtype of the declared type Class33");
        assertTrue(read(root, "rs/Client.java").contains("((Class43) class33).field323.length()"),
                read(root, "rs/Client.java"));
        assertTrue(compile(root).success(), read(root, "rs/Client.java"));
    }

    @Test
    void castsAReceiverOnALineThatClosesATextBlock(@TempDir Path root) throws IOException {
        write(root, "rs/Class303.java", """
                package rs;

                public class Class303 {
                    public void method1030() {
                    }
                }
                """);
        String source = String.join("\n",
                "package rs;",
                "",
                "public class Client {",
                "    void run() {",
                "        Object var13 = new Object();",
                "        String banner = \"\"\"",
                "            hello",
                "            \"\"\"; var13.method1030();",
                "    }",
                "}",
                "");
        write(root, "rs/Client.java", source);

        var result = fix(root);

        assertEquals(1, result.fixes(),
                "the text block opened on an earlier line must not mask the code after its terminator");
        String updated = read(root, "rs/Client.java");
        assertTrue(updated.contains("\"\"\"; ((Class303) var13).method1030();"), updated);
        assertTrue(compile(root).success(), updated);
    }

    @Test
    void neverAssumesAWildcardImportSupertypeIsInJavaLang(@TempDir Path root) throws IOException {
        write(root, "other/pkg/Thread.java", """
                package other.pkg;

                public class Thread {
                }
                """);
        write(root, "rs/Sub.java", """
                package rs;

                import other.pkg.*;

                public class Sub extends Thread {
                    public int method1234() {
                        return 1;
                    }
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                import java.lang.Thread;

                public class Client {
                    int run(Thread var1) {
                        return var1.method1234();
                    }
                }
                """);
        String before = read(root, "rs/Client.java");

        assertEquals(0, fix(root).fixes(),
                "Sub extends the wildcard-imported other.pkg.Thread, not java.lang.Thread: the cast would not compile");
        assertEquals(before, read(root, "rs/Client.java"));
    }

    @Test
    void keepsDifferentAritiesOfOneMemberApartOnOneLine(@TempDir Path root) throws IOException {
        write(root, "rs/Class305.java", "package rs;\npublic class Class305 {\n}\n");
        write(root, "rs/Class303.java", """
                package rs;

                public class Class303 {
                    public void method1030(Class305 var1) {
                    }
                }
                """);
        write(root, "rs/Class404.java", """
                package rs;

                public class Class404 {
                    public void method1030() {
                    }
                }
                """);
        write(root, "rs/Class405.java", """
                package rs;

                public class Class405 {
                    public void method1031() {
                    }
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    void run() {
                        Object var13 = new Object();
                        var13.method1030(new Class305()); var13.method1030(); var13.method1031();
                    }
                }
                """);

        var result = fix(root);

        assertEquals(3, result.fixes(), "arity is part of the key: three distinct rewrites, not two");
        String out = read(root, "rs/Client.java");
        assertTrue(out.contains("((Class303) var13).method1030(new Class305()); "
                + "((Class404) var13).method1030(); ((Class405) var13).method1031();"), out);
        assertTrue(compile(root).success(), out);
    }

    @Test
    void resolvesTheEnclosingClassByQualifiedNameNotSimpleName(@TempDir Path root) throws IOException {
        write(root, "rs/pkg46/pkg164/pkg275/pkg297/Class275.java", """
                package rs.pkg46.pkg164.pkg275.pkg297;

                public final class Class275 {
                    public static final Class275 field2832 = new Class275();
                }
                """);
        write(root, "rs/other/Class275.java", """
                package rs.other;

                public class Class275 {
                    int run(Object class275) {
                        return class275 == field2832 ? 1 : 0;
                    }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        assertTrue(read(root, "rs/other/Class275.java")
                        .contains("class275 == rs.pkg46.pkg164.pkg275.pkg297.Class275.field2832"),
                read(root, "rs/other/Class275.java"));
        assertTrue(compile(root).success(), read(root, "rs/other/Class275.java"));
    }

    @Test
    void leavesAnAmbiguousMethodOwnerAlone(@TempDir Path root) throws IOException {
        write(root, "rs/Class305.java", "package rs;\npublic class Class305 {\n}\n");
        write(root, "rs/Class303.java", """
                package rs;

                public class Class303 {
                    public void method1030(Class305 var1) {
                    }
                }
                """);
        write(root, "rs/Class404.java", """
                package rs;

                public class Class404 {
                    public void method1030(Class305 var1) {
                    }
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    void run() {
                        Object var13 = new Class303();
                        var13.method1030(new Class305());
                    }
                }
                """);
        String before = read(root, "rs/Client.java");

        assertEquals(0, fix(root).fixes());
        assertEquals(before, read(root, "rs/Client.java"));
    }

    @Test
    void leavesAnAmbiguousFieldOwnerAlone(@TempDir Path root) throws IOException {
        write(root, "rs/Class43.java", "package rs;\npublic class Class43 {\n    public String field323;\n}\n");
        write(root, "rs/Class44.java", "package rs;\npublic class Class44 {\n    public String field323;\n}\n");
        write(root, "rs/Class36.java", "package rs;\npublic class Class36 {\n}\n");
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    int run(Object class36) {
                        return ((Class36) class36).field323.length();
                    }
                }
                """);
        String before = read(root, "rs/Client.java");

        assertEquals(0, fix(root).fixes());
        assertEquals(before, read(root, "rs/Client.java"));
    }

    @Test
    void leavesJdkMemberNamesAlone(@TempDir Path root) throws IOException {
        write(root, "rs/Widget.java", """
                package rs;

                public class Widget {
                    public String name() {
                        return "";
                    }

                    public int position() {
                        return 0;
                    }

                    public void setText(String var1) {
                    }

                    public void setBorder(int var1) {
                    }
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                import java.util.ArrayList;
                import java.util.List;

                public class Client {
                    @SuppressWarnings("rawtypes")
                    int run(List raw) {
                        Object var5 = raw.get(0);
                        String text = var5.name();
                        var5.setText(text);
                        var5.setBorder(1);
                        return var5.position();
                    }
                }
                """);
        String before = read(root, "rs/Client.java");
        assertTrue(compile(root).diagnostics().size() >= 4, "the fixture must actually fail to compile");

        assertEquals(0, fix(root).fixes(), "name/position/setText/setBorder are not obfuscated members");
        assertEquals(before, read(root, "rs/Client.java"));
    }

    @Test
    void castsOnlyTheCodeOccurrenceWhenAStringAndACommentRepeatTheText(@TempDir Path root) throws IOException {
        writeHierarchy(root);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    int run(Class36 class36) { return class36.field323.length() + "class36.field323".length(); } // class36.field323
                }
                """);
        var result = fix(root);
        String out = read(root, "rs/Client.java");

        assertEquals(1, result.fixes());
        assertEquals("    int run(Class36 class36) { return ((Class43) class36).field323.length()"
                        + " + \"class36.field323\".length(); } // class36.field323",
                out.lines().filter(line -> line.contains("int run(")).findFirst().orElseThrow());
        assertTrue(compile(root).success(), out);
    }


    @Test
    void aSecondPassOverTheSameDiagnosticsChangesNothing(@TempDir Path root) throws IOException {
        writeHierarchy(root);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    int run(Class36 class36) {
                        return class36.field323.length();
                    }
                }
                """);

        var outcome = compile(root);
        var diagnostics = bucketer.categorize(outcome.diagnostics());
        assertEquals(1, CompileFixLoop.MemberResolutionFixer.tryFixAll(root, diagnostics).fixes());
        String after = read(root, "rs/Client.java");
        assertEquals(0, CompileFixLoop.MemberResolutionFixer.tryFixAll(root, diagnostics).fixes(),
                "the receiver is already cast");
        assertEquals(after, read(root, "rs/Client.java"));
        assertEquals(1, read(root, "rs/Client.java").lines()
                .filter(line -> line.contains("((Class43) class36).field323")).count());
    }

    @Test
    void unhandledWithholdsOnlyWhatThisFixerAddressed(@TempDir Path root) throws IOException {
        writeHierarchy(root);
        write(root, "rs/Widget.java", "package rs;\npublic class Widget {\n}\n");
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    int run(Class36 class36) {
                        return class36.field323.length();
                    }
                }
                """);
        write(root, "rs/Other.java", """
                package rs;

                public class Other {
                    Missing value;
                }
                """);

        var outcome = compile(root);
        var diagnostics = bucketer.categorize(outcome.diagnostics());
        var result = fix(root, outcome);
        var remaining = result.unhandled(diagnostics);

        assertEquals(1, result.fixes());
        assertEquals(1, result.handled().size());
        assertEquals(1, remaining.size(), "the missing-class diagnostic survives for ImportInserter");
        assertTrue(remaining.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH).contains("class Missing"),
                remaining.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));
    }

    @Test
    void doesNotTouchADiagnosticFreeFile(@TempDir Path root) throws IOException {
        writeHierarchy(root);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                    int run(Class43 class43) {
                        return class43.field323.length();
                    }
                }
                """);
        String before = read(root, "rs/Client.java");
        var outcome = compile(root);
        assertTrue(outcome.success());

        assertEquals(0, fix(root).fixes());
        assertEquals(before, read(root, "rs/Client.java"));
    }
}
