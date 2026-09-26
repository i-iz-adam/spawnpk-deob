package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResidualSyntaxFixerTest {

    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private static final String NARROWING = """
            package rs;

            public class BZip2Decompressor {
               byte[] var47 = new byte[8];
               byte field1;
               short field2;
               char field3;
               int var48 = 1;

               void method1() {
                  var47[var48] = var48++;
               }
            }
            """;

    private static final String NARROWING_REL = "rs/BZip2Decompressor.java";

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

    private List<DiagnosticBucketer.Bucketed> bucketed(Path root) throws IOException {
        return bucketer.categorize(compile(root).diagnostics());
    }

    private CompileFixLoop.ResidualSyntaxFixer.Result fix(Path root,
                                                         List<DiagnosticBucketer.Bucketed> diagnostics) throws IOException {
        return CompileFixLoop.ResidualSyntaxFixer.tryFixAll(root, diagnostics);
    }

    @Test
    void wrapsAnArrayElementAssignmentInTheNamedType(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, NARROWING);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                        .contains("incompatible types: possible lossy conversion from int to byte"),
                diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertEquals(1, result.handled().size());
        assertTrue(read(root, NARROWING_REL).contains("      var47[var48] = (byte) (var48++);"),
                read(root, NARROWING_REL));
        assertTrue(compile(root).success(), read(root, NARROWING_REL));
    }

    @Test
    void reRunningTheNarrowingRewriteFindsNothingLeftToDo(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, NARROWING);
        var diagnostics = bucketed(root);
        assertEquals(1, fix(root, diagnostics).fixes());
        String afterFirst = read(root, NARROWING_REL);

        var second = fix(root, diagnostics);

        assertEquals(0, second.fixes());
        assertEquals(0, second.handled().size());
        assertEquals(afterFirst, read(root, NARROWING_REL));
    }

    @Test
    void wrapsShortAndCharConversionsOnEveryAssignmentTargetShape(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   short field2;
                   char field3;
                   int var48 = 1;
                   byte[] var47 = new byte[8];

                   void method1() {
                      this.field2 = var48++;
                   }

                   void method2() {
                      field3 = var48++;
                   }

                   void method3() {
                      int local = var48;
                      local += 1;
                      this.field2 = local;
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(3, diagnostics.size(), diagnostics.toString());

        var result = fix(root, diagnostics);

        assertEquals(3, result.fixes());
        String updated = read(root, NARROWING_REL);
        assertTrue(updated.contains("      this.field2 = (short) (var48++);"), updated);
        assertTrue(updated.contains("      field3 = (char) (var48++);"), updated);
        assertTrue(updated.contains("      this.field2 = (short) (local);"), updated);
        assertTrue(compile(root).success(), updated);
    }

    @Test
    void parenthesizesABinaryRightHandSideSoTheCastBindsLoosely(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   char field3;
                   byte[] var47 = new byte[8];
                   int var48 = 1;

                   void method1() {
                      field3 = 'a' + var48;
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                        .contains("incompatible types: possible lossy conversion from int to char"),
                diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, NARROWING_REL).contains("      field3 = (char) ('a' + var48);"),
                read(root, NARROWING_REL));
        assertTrue(compile(root).success(), read(root, NARROWING_REL));
    }

    @Test
    void addsNoParenthesesWhenTheRightHandSideIsAlreadyWrapped(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   char field3;
                   int var48 = 1;

                   void method1() {
                      field3 = (var48 + 1);
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, NARROWING_REL).contains("      field3 = (char) (var48 + 1);"),
                read(root, NARROWING_REL));
        assertTrue(!read(root, NARROWING_REL).contains("((var48 + 1))"), read(root, NARROWING_REL));
        assertTrue(compile(root).success(), read(root, NARROWING_REL));
    }

    @Test
    void parenthesizesATernaryRightHandSide(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   byte field1;
                   int var48 = 1;

                   void method1() {
                      field1 = var48 > 0 ? var48 : 0;
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, NARROWING_REL).contains("      field1 = (byte) (var48 > 0 ? var48 : 0);"),
                read(root, NARROWING_REL));
        assertTrue(compile(root).success(), read(root, NARROWING_REL));
    }

    @Test
    void parenthesizesARightHandSideWhoseInnerParensCloseEarly(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   byte field1;
                   int var48 = 1;

                   void method1() {
                      field1 = (var48 + 1) + 2;
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                        .contains("possible lossy conversion from int to byte"),
                diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, NARROWING_REL).contains("      field1 = (byte) ((var48 + 1) + 2);"),
                read(root, NARROWING_REL));
        assertTrue(compile(root).success(), read(root, NARROWING_REL));
    }

    @Test
    void leavesCompoundAssignmentsAlone(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   byte[] var47 = new byte[8];
                   int var48 = 1;

                   void method1() {
                      var47[var48] = var48 += 1;
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                .contains("possible lossy conversion from int to byte"));

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertTrue(read(root, NARROWING_REL).contains("var47[var48] = var48 += 1;"), read(root, NARROWING_REL));
    }

    @Test
    void leavesARightHandSideThatAlreadyStartsWithACastAlone(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   byte field1;
                   int var48 = 1;

                   void method1() {
                      field1 = (int) (char) var48;
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                .contains("possible lossy conversion from int to byte"));

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertTrue(read(root, NARROWING_REL).contains("field1 = (int) (char) var48;"), read(root, NARROWING_REL));
    }

    @Test
    void leavesNonAssignmentLinesAlone(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   int var48 = 1;

                   void take(byte value) {
                   }

                   void method1() {
                      this.take(var48);
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                .contains("possible lossy conversion from int to byte"));

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertTrue(read(root, NARROWING_REL).contains("this.take(var48);"), read(root, NARROWING_REL));
    }

    @Test
    void leavesAReturnStatementAlone(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   int var48 = 1;

                   byte method1() {
                      return var48;
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                .contains("possible lossy conversion from int to byte"));

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertTrue(read(root, NARROWING_REL).contains("      return var48;"), read(root, NARROWING_REL));
    }

    @Test
    void leavesAnAssignmentToAComputedTargetAlone(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   static class Holder {
                      byte field;
                   }
                   int var48 = 1;

                   Holder get() {
                      return new Holder();
                   }

                   void method1() {
                      this.get().field = var48;
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                .contains("possible lossy conversion from int to byte"));

        var result = fix(root, diagnostics);

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
        assertTrue(read(root, NARROWING_REL).contains("this.get().field = var48;"), read(root, NARROWING_REL));
    }

    @Test
    void picksTheAssignmentEqualsAndNotTheOneInsideATrailingComment(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   byte field1;
                   int var48 = 1;

                   void method1() {
                      field1 = var48; // a = b; c = d
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, NARROWING_REL).contains("      field1 = (byte) (var48); // a = b; c = d"),
                read(root, NARROWING_REL));
        assertTrue(compile(root).success(), read(root, NARROWING_REL));
    }

    @Test
    void picksTheAssignmentEqualsAndNotTheOneInsideAStringLiteral(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   byte field1;
                   int var48 = 1;

                   void method1() {
                      field1 = "a=b".length();
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());
        assertTrue(diagnostics.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH)
                .contains("possible lossy conversion from int to byte"));

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        assertTrue(read(root, NARROWING_REL).contains(
                "      field1 = (byte) (\"a=b\".length());"), read(root, NARROWING_REL));
        assertTrue(compile(root).success(), read(root, NARROWING_REL));
    }

    @Test
    void decoysOnUnflaggedLinesAreNeverTouched(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   byte field1;
                   int var48 = 1;
                   String decoy = "a[i] = i;";
                   // a[i] = i;

                   void method1() {
                      field1 = var48;
                   }
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(1, diagnostics.size());

        var result = fix(root, diagnostics);

        assertEquals(1, result.fixes());
        String updated = read(root, NARROWING_REL);
        assertTrue(updated.contains("      field1 = (byte) (var48);"), updated);
        assertTrue(updated.contains("   String decoy = \"a[i] = i;\";"), updated);
        assertTrue(updated.contains("   // a[i] = i;"), updated);
        assertTrue(compile(root).success(), updated);
    }

    @Test
    void leavesACleanTreeUntouched(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, """
                package rs;

                public class BZip2Decompressor {
                   byte field1;
                   int var48 = 1;

                   void method1() {
                      field1 = (byte) (var48);
                   }
                }
                """);
        var outcome = compile(root);
        assertTrue(outcome.success(), "fixture must compile cleanly");

        var result = fix(root, bucketer.categorize(outcome.diagnostics()));

        assertEquals(0, result.fixes());
        assertTrue(read(root, NARROWING_REL).contains("field1 = (byte) (var48);"), read(root, NARROWING_REL));
    }

    @Test
    void noOpWhenNoDiagnosticMatches(@TempDir Path root) throws IOException {
        write(root, "rs/Solo.java", """
                package rs;

                public class Solo {
                   public int field1 = 1;
                }
                """);

        var result = fix(root, bucketed(root));

        assertEquals(0, result.fixes());
        assertEquals(0, result.handled().size());
    }

    @Test
    void unhandledWithholdsOnlyWhatThisFixerAddressed(@TempDir Path root) throws IOException {
        write(root, NARROWING_REL, NARROWING);
        write(root, "rs/Broken.java", """
                package rs;

                public class Broken {
                   Missing value;
                }
                """);

        var diagnostics = bucketed(root);
        assertEquals(2, diagnostics.size(), diagnostics.toString());

        var result = fix(root, diagnostics);
        var remaining = result.unhandled(diagnostics);

        assertEquals(1, result.fixes());
        assertEquals(1, result.handled().size());
        assertEquals(1, remaining.size(), "the missing-class diagnostic survives for ImportInserter");
        assertTrue(remaining.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH).contains("class Missing"),
                remaining.get(0).diagnostic().getMessage(java.util.Locale.ENGLISH));
    }
}
