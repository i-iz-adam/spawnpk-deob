package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoidAbstractStubFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private static final String CLIENT = """
            package rs.pkg46.pkg163;

            public class Client {
            }
            """;

    private static final String CLASS192 = """
            package rs.pkg46.pkg163;

            public class Class192 {
            }
            """;

    private static final String OWNER = """
            package rs.pkg46.pkg163;

            public abstract class Class193 {
               public abstract void method1694();
               public abstract boolean method3618(Client var1);
               public abstract void method1868(Client var1, Class192 var2);
               public abstract void method1046();
            }
            """;

    private static final String SUBCLASS_MISSING_METHOD1046 = """
            package rs.pkg46.pkg163.pkg233;

            import rs.pkg46.pkg163.Class192;
            import rs.pkg46.pkg163.Client;
            import rs.pkg46.pkg163.Class193;

            public class Class217 extends Class193 {
               public int field1;

               @Override
               public void method1694() {
               }

               @Override
               public boolean method3618(Client var1) {
                  return true;
               }

               @Override
               public void method1868(Client var1, Class192 var2) {
               }
            }
            """;

    private static final String SUBCLASS_MISSING_METHOD1868 = """
            package rs.pkg46.pkg163.pkg233;

            import rs.pkg46.pkg163.Class192;
            import rs.pkg46.pkg163.Client;
            import rs.pkg46.pkg163.Class193;

            public class Class217 extends Class193 {
               public int field1;

               @Override
               public void method1694() {
               }

               @Override
               public boolean method3618(Client var1) {
                  return true;
               }

               @Override
               public void method1046() {
               }
            }
            """;

    private static final String SUBCLASS_MISSING_METHOD3618 = """
            package rs.pkg46.pkg163.pkg233;

            import rs.pkg46.pkg163.Class192;
            import rs.pkg46.pkg163.Client;
            import rs.pkg46.pkg163.Class193;

            public class Class217 extends Class193 {
               public int field1;

               @Override
               public void method1694() {
               }

               @Override
               public void method1046() {
               }

               @Override
               public void method1868(Client var1, Class192 var2) {
               }
            }
            """;

    private static final String SUBCLASS_REL = "rs/pkg46/pkg163/pkg233/Class217.java";
    private static final String OWNER_REL = "rs/pkg46/pkg163/Class193.java";

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private static String read(Path root, String rel) throws IOException {
        return Files.readString(root.resolve(rel)).replace("\r\n", "\n");
    }

    private JavacRunner.CompileOutcome compile(Path root) throws IOException {
        return javac.compile(root, root.resolveSibling("classes-" + root.getFileName()), List.of(), "17");
    }

    private void seedOwner(Path root) throws IOException {
        write(root, OWNER_REL, OWNER);
        write(root, "rs/pkg46/pkg163/Client.java", CLIENT);
        write(root, "rs/pkg46/pkg163/Class192.java", CLASS192);
    }

    private void seed(Path root, String subclass) throws IOException {
        seedOwner(root);
        write(root, SUBCLASS_REL, subclass);
    }

    private List<DiagnosticBucketer.Bucketed> bucketed(Path root) throws IOException {
        return bucketer.categorize(compile(root).diagnostics());
    }

    private CompileFixLoop.VoidAbstractStubFixer.Result fix(Path root,
                                                            List<DiagnosticBucketer.Bucketed> diagnostics) throws IOException {
        return CompileFixLoop.VoidAbstractStubFixer.tryFixAll(root, diagnostics);
    }

    @Test
    void insertsTheMissingVoidStubAndTheTreeCompiles(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                        .contains("is not abstract and does not override abstract method method1046()"
                                + " in rs.pkg46.pkg163.Class193"),
                diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertEquals(1, result.handled().size());
        assertTrue(read(root, SUBCLASS_REL).contains(
                "   @Override\n   public void method1046() {\n   }\n}"),
                read(root, SUBCLASS_REL));
        assertEquals(SUBCLASS_MISSING_METHOD1046.lines().count() + 3,
                read(root, SUBCLASS_REL).lines().count(),
                "the stub is three inserted lines");
        assertTrue(compile(root).success(), read(root, SUBCLASS_REL));
    }

    @Test
    void derivesTheStubIndentFromAnExistingMember(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046.replace("\n   ", "\n        "));

        var result = fix(root, bucketed(root));

        assertEquals(1, result.fixes());
        assertTrue(read(root, SUBCLASS_REL).contains(
                "        @Override\n        public void method1046() {\n        }\n}"),
                read(root, SUBCLASS_REL));
        assertTrue(compile(root).success(), read(root, SUBCLASS_REL));
    }

    @Test
    void derivesTheStubIndentFromAMemberRatherThanANestedStatement(@TempDir Path root) throws IOException {
        seed(root, """
                package rs.pkg46.pkg163.pkg233;

                import rs.pkg46.pkg163.Class192;
                import rs.pkg46.pkg163.Client;
                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1 = 1;

                   void method1() {
                      if (field1 > 0) {
                         field1 = 2;
                      }
                   }

                   @Override
                   public void method1694() {
                   }

                   @Override
                   public boolean method3618(Client var1) {
                      return true;
                   }

                   @Override
                   public void method1868(Client var1, Class192 var2) {
                   }
                }
                """);

        var result = fix(root, bucketed(root));

        assertEquals(1, result.fixes());
        assertTrue(read(root, SUBCLASS_REL).contains(
                "   @Override\n   public void method1046() {\n   }\n}"),
                read(root, SUBCLASS_REL));
        assertTrue(compile(root).success(), read(root, SUBCLASS_REL));
    }

    @Test
    void fallsBackToTheClassIndentWhenTheBodyHasNoMembers(@TempDir Path root) throws IOException {
        seed(root, """
                package rs.pkg46.pkg163.pkg233;

                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                }
                """);

        var result = fix(root, bucketed(root));

        assertEquals(1, result.fixes());
        assertTrue(read(root, SUBCLASS_REL).contains(
                "public class Class217 extends Class193 {\n   @Override\n"
                        + "   public void method1046() {\n   }\n}"),
                read(root, SUBCLASS_REL));
    }

    @Test
    void convergesOnTheVoidMembersAndStallsOnTheNonVoidOne(@TempDir Path root) throws IOException {
        seed(root, """
                package rs.pkg46.pkg163.pkg233;

                import rs.pkg46.pkg163.Class192;
                import rs.pkg46.pkg163.Client;
                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                }
                """);

        for (int pass = 0; pass < 10 && !compile(root).success(); pass++) {
            fix(root, bucketed(root));
        }

        String updated = read(root, SUBCLASS_REL);
        assertTrue(!updated.contains("method3618"), "a non-void method is never stubbed: " + updated);
        var stalled = compile(root);
        assertEquals(1, stalled.diagnostics().size(), updated);
        assertTrue(stalled.diagnostics().get(0).getMessage(java.util.Locale.ENGLISH)
                        .contains("does not override abstract method method3618"),
                stalled.diagnostics().get(0).getMessage(java.util.Locale.ENGLISH));
        var fixed = fix(root, bucketer.categorize(stalled.diagnostics()));
        assertEquals(0, fixed.fixes());
        assertEquals(updated, read(root, SUBCLASS_REL));
    }

    @Test
    void theInsertedStubIsTheOnlyChangeToTheOwnersFile(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);

        fix(root, bucketed(root));

        assertEquals(OWNER, read(root, OWNER_REL));
    }

    @Test
    void copiesTheOwnersParameterNamesAndTypesVerbatim(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1868);

        var diagnostics = bucketed(root);
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                        .contains("does not override abstract method method1868("
                                + "rs.pkg46.pkg163.Client,rs.pkg46.pkg163.Class192) in rs.pkg46.pkg163.Class193"),
                diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, SUBCLASS_REL).contains(
                "   @Override\n   public void method1868(Client var1, Class192 var2) {\n   }\n}"),
                read(root, SUBCLASS_REL));
        assertTrue(compile(root).success(), read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenTheAbstractMethodIsNotVoid(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD3618);

        var diagnostics = bucketed(root);
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                        .contains("does not override abstract method method3618("
                                + "rs.pkg46.pkg163.Client) in rs.pkg46.pkg163.Class193"),
                diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(SUBCLASS_MISSING_METHOD3618, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenTheClassAlreadyDeclaresThatNameAtTheSameArity(@TempDir Path root)
            throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        var diagnostics = bucketed(root);
        String patched = read(root, SUBCLASS_REL).replace(
                "   public int field1;",
                "   public void method1046() {\n      field1 = 1;\n   }\n\n   public int field1;");
        write(root, SUBCLASS_REL, patched);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(patched, read(root, SUBCLASS_REL));
        assertTrue(compile(root).success(), patched);
    }

    @Test
    void leavesTheFileAloneWhenTheClassDeclaresThatNameAtAnotherArity(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        var diagnostics = bucketed(root);
        String patched = read(root, SUBCLASS_REL).replace(
                "   public int field1;",
                "   public void method1046(int var2) {\n      field1 = var2;\n   }\n\n   public int field1;");
        write(root, SUBCLASS_REL, patched);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(patched, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenTheClassAlreadyDeclaresAGenericMethodWithThatName(@TempDir Path root)
            throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        var diagnostics = bucketed(root);
        String patched = read(root, SUBCLASS_REL).replace(
                "   public int field1;",
                "   public <T> void method1046(T var2) {\n      field1 = 1;\n   }\n\n   public int field1;");
        write(root, SUBCLASS_REL, patched);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(patched, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenTheExistingDeclarationWrapsItsParameterList(@TempDir Path root)
            throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        var diagnostics = bucketed(root);
        String patched = read(root, SUBCLASS_REL).replace(
                "   public int field1;",
                "   public void method1046(\n      int var2) {\n      field1 = var2;\n   }\n\n   public int field1;");
        write(root, SUBCLASS_REL, patched);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(patched, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenAParameterTypeIsNotVisibleInTheTargetFile(@TempDir Path root) throws IOException {
        write(root, OWNER_REL, """
                package rs.pkg46.pkg163;

                public abstract class Class193 {
                   public abstract void method1694();
                   public abstract void method1868(Client var1, Class192 var2);
                   public abstract void method1046();
                }
                """);
        write(root, "rs/pkg46/pkg163/Client.java", """
                package rs.pkg46.pkg163;

                class Client {
                }
                """);
        write(root, "rs/pkg46/pkg163/Class192.java", CLASS192);
        write(root, SUBCLASS_REL, """
                package rs.pkg46.pkg163.pkg233;

                import rs.pkg46.pkg163.Class192;
                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1;

                   @Override
                   public void method1694() {
                   }

                   @Override
                   public void method1046() {
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                        .contains("does not override abstract method method1868("),
                diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));
        String before = read(root, SUBCLASS_REL);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(before, read(root, SUBCLASS_REL));
        assertTrue(!read(root, SUBCLASS_REL).contains("Client var1"), read(root, SUBCLASS_REL));
    }

    @Test
    void insertsTheStubWhenTheOwnerSharesTheTargetsPackage(@TempDir Path root) throws IOException {
        write(root, "rs/pkg46/Client.java", """
                package rs.pkg46;

                class Client {
                }
                """);
        write(root, "rs/pkg46/Class193.java", """
                package rs.pkg46;

                public abstract class Class193 {
                   public abstract void method1868(Client var1);
                }
                """);
        write(root, "rs/pkg46/Class217.java", """
                package rs.pkg46;

                public class Class217 extends Class193 {
                   public int field1;
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, "rs/pkg46/Class217.java").contains(
                "   @Override\n   public void method1868(Client var1) {\n   }\n}"),
                read(root, "rs/pkg46/Class217.java"));
        assertTrue(compile(root).success(), read(root, "rs/pkg46/Class217.java"));
    }

    @Test
    void leavesTheFileAloneWhenOnlyAWildcardImportCoversTheType(@TempDir Path root) throws IOException {
        write(root, OWNER_REL, """
                package rs.pkg46.pkg163;

                import rs.other.Gadget;

                public abstract class Class193 {
                   public abstract void method1868(Gadget var1);
                }
                """);
        write(root, "rs/other/Gadget.java", """
                package rs.other;

                public class Gadget {
                }
                """);
        write(root, SUBCLASS_REL, """
                package rs.pkg46.pkg163.pkg233;

                import rs.other.*;
                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1;
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        String before = read(root, SUBCLASS_REL);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(before, read(root, SUBCLASS_REL));
    }

    @Test
    void insertsTheStubWhenAParameterTypeIsInheritedFromTheOwner(@TempDir Path root) throws IOException {
        write(root, OWNER_REL, """
                package rs.pkg46.pkg163;

                public abstract class Class193 {
                   public static class Inner {
                   }

                   public abstract void method1868(Inner var1);
                }
                """);
        write(root, SUBCLASS_REL, """
                package rs.pkg46.pkg163.pkg233;

                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1;
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, SUBCLASS_REL).contains(
                "   @Override\n   public void method1868(Inner var1) {\n   }\n}"),
                read(root, SUBCLASS_REL));
        assertTrue(compile(root).success(), read(root, SUBCLASS_REL));
    }

    @Test
    void insertsTheStubWhenAParameterListCommasSitInsideGenerics(@TempDir Path root) throws IOException {
        write(root, OWNER_REL, """
                package rs.pkg46.pkg163;

                import java.util.Map;

                public abstract class Class193 {
                   public abstract void method1868(Map<String, Integer> var1);
                }
                """);
        write(root, SUBCLASS_REL, """
                package rs.pkg46.pkg163.pkg233;

                import java.util.Map;
                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1;
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                        .contains("does not override abstract method method1868("
                                + "java.util.Map<java.lang.String,java.lang.Integer>)"),
                diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, SUBCLASS_REL).contains(
                "   @Override\n   public void method1868(Map<String, Integer> var1) {\n   }\n}"),
                read(root, SUBCLASS_REL));
        assertTrue(compile(root).success(), read(root, SUBCLASS_REL));
    }

    @Test
    void resolvesTheOwnerByTheQualifiedNameJavacReported(@TempDir Path root) throws IOException {
        write(root, "rs/a/Class193.java", """
                package rs.a;

                public abstract class Class193 {
                   public abstract void method1046();

                   public abstract void method1868(String var1);
                }
                """);
        write(root, "rs/b/Class193.java", """
                package rs.b;

                public abstract class Class193 {
                   public abstract void method1046();

                   public abstract void method1868(String var1);
                }
                """);
        write(root, "rs/c/Class217.java", """
                package rs.c;

                import rs.b.Class193;

                public class Class217 extends Class193 {
                   public int field1;

                   @Override
                   public void method1046() {
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                        .contains("in rs.b.Class193"),
                diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, "rs/c/Class217.java").contains(
                "   @Override\n   public void method1868(String var1) {\n   }\n}"),
                read(root, "rs/c/Class217.java"));
        assertTrue(compile(root).success(), read(root, "rs/c/Class217.java"));
    }

    @Test
    void leavesTheFileAloneWhenTheOwnersDeclarationArityDoesNotMatchTheDiagnostic(@TempDir Path root)
            throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        var diagnostics = bucketed(root);
        write(root, OWNER_REL, OWNER.replace(
                "public abstract void method1046();", "public abstract void method1046(Client var1);"));

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(SUBCLASS_MISSING_METHOD1046, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenTheOwnersDeclarationIsNotAbstract(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        var diagnostics = bucketed(root);
        write(root, OWNER_REL, OWNER.replace("public abstract void method1046();", "public void method1046() {\n   }"));

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(SUBCLASS_MISSING_METHOD1046, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenTheOwnerSourceIsNotInTheTree(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        var diagnostics = bucketed(root);
        Files.delete(root.resolve(OWNER_REL));

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(SUBCLASS_MISSING_METHOD1046, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenTheClassNoLongerExtendsTheOwner(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        var diagnostics = bucketed(root);
        String patched = read(root, SUBCLASS_REL).replace(" extends Class193 {", " {");
        write(root, SUBCLASS_REL, patched);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(patched, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenTheFlaggedClassIsNotInTheFlaggedFile(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        var diagnostics = bucketed(root);
        String patched = read(root, SUBCLASS_REL)
                .replace("public class Class217", "public class Class218");
        write(root, SUBCLASS_REL, patched);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(patched, read(root, SUBCLASS_REL));
    }

    @Test
    void reRunningWithTheOriginalDiagnosticsFindsNothingLeftToDo(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        var diagnostics = bucketed(root);
        assertEquals(1, fix(root, diagnostics).fixes());
        String afterFirst = read(root, SUBCLASS_REL);

        var second = fix(root, diagnostics);

        assertEquals(0, second.fixes());
        assertEquals(0, second.handled().size());
        assertEquals(afterFirst, read(root, SUBCLASS_REL));
        assertTrue(compile(root).success(), afterFirst);
    }

    @Test
    void leavesACleanTreeUntouched(@TempDir Path root) throws IOException {
        seedOwner(root);
        String alreadyStubbed = SUBCLASS_MISSING_METHOD1046.replace(
                "   public void method1868(Client var1, Class192 var2) {\n   }\n}",
                "   public void method1868(Client var1, Class192 var2) {\n   }\n"
                        + "\n   @Override\n   public void method1046() {\n   }\n}");
        write(root, SUBCLASS_REL, alreadyStubbed);

        var outcome = compile(root);
        assertTrue(outcome.success(), "fixture must compile cleanly");

        var result = fix(root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes());
        assertEquals(alreadyStubbed, read(root, SUBCLASS_REL));
        assertEquals(OWNER, read(root, OWNER_REL));
    }

    @Test
    void noOpWhenNoDiagnosticMatches(@TempDir Path root) throws IOException {
        write(root, "rs/Solo.java", """
                package rs;

                public class Solo {
                   public int field1;
                }
                """);

        var result = fix(root, bucketed(root));

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
    }

    @Test
    void unhandledWithholdsOnlyWhatThisFixerAddressed(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        write(root, "rs/Broken.java", """
                package rs;

                public class Broken {
                   Missing value;
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(2, diagnostics.size());

        var result = fix(root, diagnostics);
        var remaining = result.unhandled(diagnostics);

        assertEquals(1, result.fixes());
        assertEquals(1, result.handled().size());
        assertEquals(1, remaining.size(), "the missing-class diagnostic survives for ImportInserter");
        assertTrue(remaining.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH).contains("class Missing"),
                remaining.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));
    }

    @Test
    void insertsTheStubForAnAbstractMethodTakingAPrimitiveArray(@TempDir Path root) throws IOException {
        write(root, OWNER_REL, """
                package rs.pkg46.pkg163;

                public abstract class Class193 {
                   public abstract void method1868(byte[] var1);
                }
                """);
        write(root, SUBCLASS_REL, """
                package rs.pkg46.pkg163.pkg233;

                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1;
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, SUBCLASS_REL).contains(
                "   @Override\n   public void method1868(byte[] var1) {\n   }\n}"),
                read(root, SUBCLASS_REL));
        assertTrue(compile(root).success(), read(root, SUBCLASS_REL));
    }

    @Test
    void insertsTheStubForAnAbstractMethodTakingAPrimitiveVarargs(@TempDir Path root) throws IOException {
        write(root, OWNER_REL, """
                package rs.pkg46.pkg163;

                public abstract class Class193 {
                   public abstract void method1868(int... var1);
                }
                """);
        write(root, SUBCLASS_REL, """
                package rs.pkg46.pkg163.pkg233;

                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1;
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, SUBCLASS_REL).contains(
                "   @Override\n   public void method1868(int... var1) {\n   }\n}"),
                read(root, SUBCLASS_REL));
        assertTrue(compile(root).success(), read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenAnUnnamedParameterTypeIsNotVisible(@TempDir Path root) throws IOException {
        write(root, OWNER_REL, """
                package rs.pkg46.pkg163;

                public abstract class Class193 {
                   public abstract void method1868(Client var1);
                }
                """);
        write(root, "rs/pkg46/pkg163/Client.java", """
                package rs.pkg46.pkg163;

                class Client {
                }
                """);
        write(root, SUBCLASS_REL, """
                package rs.pkg46.pkg163.pkg233;

                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1;
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        String before = read(root, SUBCLASS_REL);
        write(root, OWNER_REL, read(root, OWNER_REL).replace(
                "method1868(Client var1);", "method1868(Client);"));

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(before, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenTheTypeNameAppearsOnlyInsideAStringLiteral(@TempDir Path root)
            throws IOException {
        write(root, OWNER_REL, """
                package rs.pkg46.pkg163;

                import rs.other.Gadget;

                public abstract class Class193 {
                   public abstract void method1868(Gadget var1);
                }
                """);
        write(root, "rs/other/Gadget.java", """
                package rs.other;

                public class Gadget {
                }
                """);
        write(root, SUBCLASS_REL, """
                package rs.pkg46.pkg163.pkg233;

                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1;
                   String decoy = "class Gadget {";
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        String before = read(root, SUBCLASS_REL);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(before, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenTheTypeNameAppearsOnlyInsideAComment(@TempDir Path root) throws IOException {
        write(root, OWNER_REL, """
                package rs.pkg46.pkg163;

                import rs.other.Gadget;

                public abstract class Class193 {
                   public abstract void method1868(Gadget var1);
                }
                """);
        write(root, "rs/other/Gadget.java", """
                package rs.other;

                public class Gadget {
                }
                """);
        write(root, SUBCLASS_REL, """
                package rs.pkg46.pkg163.pkg233;

                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1;
                   // class Gadget {
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        String before = read(root, SUBCLASS_REL);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(before, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenOnlyACommentFakesTheExtendsClause(@TempDir Path root) throws IOException {
        seed(root, SUBCLASS_MISSING_METHOD1046);
        var diagnostics = bucketed(root);
        String patched = read(root, SUBCLASS_REL)
                .replace(" extends Class193 {", " {")
                .replace("   public int field1;",
                        "   public int field1;\n   String decoy = \"extends Class193 {\";");
        write(root, SUBCLASS_REL, patched);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(patched, read(root, SUBCLASS_REL));
    }

    @Test
    void leavesTheFileAloneWhenOnlyACommentFakesATypeInTheOwner(@TempDir Path root) throws IOException {
        write(root, OWNER_REL, """
                package rs.pkg46.pkg163;

                import rs.other.Gadget;

                public abstract class Class193 {
                   public abstract void method1868(Gadget var1);
                   // class Gadget {
                }
                """);
        write(root, "rs/other/Gadget.java", """
                package rs.other;

                public class Gadget {
                }
                """);
        write(root, SUBCLASS_REL, """
                package rs.pkg46.pkg163.pkg233;

                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1;
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());
        String before = read(root, SUBCLASS_REL);

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertEquals(before, read(root, SUBCLASS_REL));
    }

    @Test
    void insertsTheStubWhenTheTypeIsASiblingInTheTargetsOwnPackage(@TempDir Path root) throws IOException {
        write(root, OWNER_REL, """
                package rs.pkg46.pkg163;

                import rs.pkg46.pkg163.pkg233.Widget;

                public abstract class Class193 {
                   public abstract void method1868(Widget var1);
                }
                """);
        write(root, "rs/pkg46/pkg163/pkg233/Widget.java", """
                package rs.pkg46.pkg163.pkg233;

                public class Widget {
                }
                """);
        write(root, "rs/other/Spare.java", """
                package rs.other;

                public class Spare {
                }
                """);
        write(root, SUBCLASS_REL, """
                package rs.pkg46.pkg163.pkg233;

                import rs.other.*;
                import rs.pkg46.pkg163.Class193;

                public class Class217 extends Class193 {
                   public int field1;
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size(), diagnostics.toString());

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, SUBCLASS_REL).contains(
                "   @Override\n   public void method1868(Widget var1) {\n   }\n}"),
                read(root, SUBCLASS_REL));
        assertTrue(compile(root).success(), read(root, SUBCLASS_REL));
    }
}
