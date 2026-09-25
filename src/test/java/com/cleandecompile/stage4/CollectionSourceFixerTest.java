package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CollectionSourceFixerTest {

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

    private CompileFixLoop.CollectionSourceFixer.Result fix(Path root) throws IOException {
        return fix(root, compile(root));
    }

    private CompileFixLoop.CollectionSourceFixer.Result fix(Path root,
                                                            JavacRunner.CompileOutcome outcome) throws IOException {
        return CompileFixLoop.CollectionSourceFixer.tryFixAll(root, bucketer.categorize(outcome.diagnostics()));
    }

    @Test
    void removesTheObjectCastWrappingAForEachSourceArray(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                   int[] field620;
                   void method1() {
                      for (Object object3 : (Object)field620) {
                         System.out.println(object3);
                      }
                   }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        assertTrue(read(root, "rs/Client.java").contains("for (Object object3 : field620) {"),
                read(root, "rs/Client.java"));
        assertTrue(compile(root).success(), read(root, "rs/Client.java"));
    }

    @Test
    void removesTheObjectCastWrappingAForEachSourceArrayExpression(@TempDir Path root) throws IOException {
        write(root, "rs/gui/loadouts/LoadoutFolders.java", """
                package rs.gui.loadouts;

                public class LoadoutFolders {
                   void method1(String string) {
                      for (Object object : (Object)string.toLowerCase().toCharArray()) {
                         System.out.println(object);
                      }
                   }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        assertTrue(read(root, "rs/gui/loadouts/LoadoutFolders.java")
                        .contains("for (Object object : string.toLowerCase().toCharArray()) {"),
                read(root, "rs/gui/loadouts/LoadoutFolders.java"));
        assertTrue(compile(root).success(), read(root, "rs/gui/loadouts/LoadoutFolders.java"));
    }

    @Test
    void rewritesOnlyTheCodeCastWhenAStringAndACommentRepeatIt(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                   int[] field620;
                   void method1() {
                      for (Object object3 : (Object)field620) { String s = "(Object)field620"; } // (Object)field620
                   }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        String out = read(root, "rs/Client.java");
        assertTrue(out.contains("for (Object object3 : field620) { String s = \"(Object)field620\"; } // (Object)field620"),
                out);
        assertTrue(compile(root).success(), out);
    }

    @Test
    void parameterizesTheRawListCastBehindAnUnboxingMethodReference(@TempDir Path root) throws IOException {
        write(root, "rs/Class735.java", """
                package rs;

                import java.util.List;

                public class Class735 {
                   protected int[] method1247(Object var1) {
                      return var1 != null && ((List)var1).size() != 0 ? ((List)var1).stream().mapToInt(Integer::intValue).toArray() : null;
                   }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        String out = read(root, "rs/Class735.java");
        assertTrue(out.contains("((List<Integer>)var1).stream().mapToInt(Integer::intValue).toArray()"), out);
        assertTrue(out.contains("((List)var1).size() != 0"), "only the stream receiver's cast is rewritten: " + out);
        assertTrue(compile(root).success(), out);
    }

    @Test
    void parameterizesTheRawListCastBehindAnUnboxingMethodReferenceAndCompilesCleanly(@TempDir Path root) throws IOException {
        write(root, "rs/Class737.java", """
                package rs;

                import java.util.List;

                public class Class737 {
                   protected int[] method1247(Object var1) {
                      return ((List)var1).stream().mapToInt(Integer::intValue).toArray();
                   }
                }
                """);
        String before = read(root, "rs/Class737.java");
        var broken = compile(root);
        assertTrue(!broken.success(), "the fixture must fail to compile before the fix: " + broken.diagnostics());

        var result = fix(root);

        assertEquals(1, result.fixes());
        String out = read(root, "rs/Class737.java");
        assertTrue(out.contains("((List<Integer>)var1).stream().mapToInt(Integer::intValue).toArray()"), out);
        assertTrue(!before.equals(out), "the file must change");
        assertTrue(compile(root).success(), out);
    }

    @Test
    void parameterizesAFullyQualifiedCollectionCastBehindAnUnboxingMethodReference(@TempDir Path root) throws IOException {
        write(root, "rs/Class737.java", """
                package rs;

                public class Class737 {
                   protected int[] method1247(Object var1) {
                      return ((java.util.Collection)var1).stream().mapToInt(Integer::intValue).toArray();
                   }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        String out = read(root, "rs/Class737.java");
        assertTrue(out.contains("((java.util.Collection<Integer>)var1).stream().mapToInt(Integer::intValue).toArray()"),
                out);
        assertTrue(compile(root).success(), out);
    }

    @Test
    void leavesAMapToLongOrMapMethodReferenceLineAlone(@TempDir Path root) throws IOException {
        write(root, "rs/Class738.java", """
                package rs;

                import java.util.List;

                public class Class738 {
                   protected long[] method1247(Object var1) {
                      return ((List)var1).stream().mapToLong(Integer::longValue).toArray();
                   }
                   protected Object method1248(Object var1) {
                      return ((List)var1).stream().map(Integer::intValue).toList();
                   }
                }
                """);
        String before = read(root, "rs/Class738.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("longValue in class java.lang.Integer")),
                "the fixture must produce the method-reference diagnostics");
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("intValue in class java.lang.Integer")),
                "the fixture must produce the method-reference diagnostics");

        assertEquals(0, fix(root, outcome).fixes(), "only mapToInt is this fixer's business");
        assertEquals(before, read(root, "rs/Class738.java"));
    }

    @Test
    void declinesARawSimpleNameCollectionCastThatDoesNotBindToJavaUtil(@TempDir Path root) throws IOException {
        write(root, "rs/List.java", """
                package rs;

                import java.util.stream.Stream;

                public class List<T> {
                   public Stream<T> stream() {
                      return null;
                   }
                }
                """);
        write(root, "rs/Class737.java", """
                package rs;

                public class Class737 {
                   protected int[] method1247(Object var1) {
                      return ((List)var1).stream().mapToInt(Integer::intValue).toArray();
                   }
                }
                """);
        String before = read(root, "rs/Class737.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("invalid method reference")),
                "the fixture must produce the method-reference diagnostic");

        assertEquals(0, fix(root, outcome).fixes(),
                "List is rs.List here, not java.util.List: we only know how to parameterize the JDK one");
        assertEquals(before, read(root, "rs/Class737.java"));
    }

    @Test
    void declinesAWrapperMethodReferenceThatIsNotAnUnboxingCall(@TempDir Path root) throws IOException {
        write(root, "rs/Class739.java", """
                package rs;

                import java.util.List;

                public class Class739 {
                   protected int[] method1247(Object var1) {
                      return ((List)var1).stream().mapToInt(Integer::sum).toArray();
                   }
                   protected int[] method1248(Object var1) {
                      return ((List)var1).stream().mapToInt(String::length).toArray();
                   }
                }
                """);
        String before = read(root, "rs/Class739.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("method sum in class java.lang.Integer")),
                "the fixture must produce the method-reference diagnostics");
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("method length in class java.lang.String")),
                "the fixture must produce the method-reference diagnostics");

        assertEquals(0, fix(root, outcome).fixes(),
                "only an unboxing reference on a wrapper proves what the raw collection holds");
        assertEquals(before, read(root, "rs/Class739.java"));
    }

    @Test
    void declinesAMethodReferenceWhoseOwnerIsNotAWrapper(@TempDir Path root) throws IOException {
        write(root, "rs/util/Box.java", """
                package rs.util;

                public class Box {
                   public int count() {
                      return 0;
                   }
                }
                """);
        write(root, "rs/Class737.java", """
                package rs;

                import java.util.List;
                import rs.util.Box;

                public class Class737 {
                   protected int[] method1247(Object var1) {
                      return ((List)var1).stream().mapToInt(Box::count).toArray();
                   }
                }
                """);
        String before = read(root, "rs/Class737.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("method count in class")),
                "the fixture must produce the method-reference diagnostic");

        assertEquals(0, fix(root, outcome).fixes(),
                "Box::count is not an unboxing reference: only a wrapper proves the element type");
        assertEquals(before, read(root, "rs/Class737.java"));
    }

    @Test
    void retypesTheStreamCastWhoseTargetJavacNames(@TempDir Path root) throws IOException {
        write(root, "rs/runelite/pkg819/Class781.java", """
                package rs.runelite.pkg819;

                public final class Class781 {
                }
                """);
        write(root, "rs/plugins/groundmarkers/GroundMarkersPlugin.java", """
                package rs.plugins.groundmarkers;

                import java.util.Collection;
                import java.util.stream.Stream;

                public class GroundMarkersPlugin {
                   Stream<rs.runelite.pkg819.Class781> method1(Collection var1) {
                      return var1.stream().flatMap(var0 -> { Collection var1x = (Collection) var0; return (rs.runelite.pkg819.Class781) var1x.stream().map(var1xx -> new rs.runelite.pkg819.Class781()); });
                   }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        String out = read(root, "rs/plugins/groundmarkers/GroundMarkersPlugin.java");
        assertTrue(out.contains("return (java.util.stream.Stream<rs.runelite.pkg819.Class781>) var1x.stream()"), out);
        assertTrue(compile(root).success(), out);
    }

    @Test
    void retypeAStreamCastWhoseSourceCastIsNamedByItsSimpleName(@TempDir Path root) throws IOException {
        write(root, "rs/List.java", """
                package rs;

                public final class List {
                }
                """);
        write(root, "rs/Class737.java", """
                package rs;

                import java.util.stream.Stream;

                public class Class737 {
                   Stream<rs.List> method1(java.util.Collection<java.util.List<rs.List>> var1) {
                      return var1.stream().flatMap(v -> (List) v.stream().map(x -> x));
                   }
                }
                """);
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("java.util.stream.Stream<rs.List> cannot be converted to rs.List")),
                "the fixture must produce the stream-cast diagnostic");

        var result = fix(root, outcome);

        assertEquals(1, result.fixes(), "the file's own List is the element type here");
        String out = read(root, "rs/Class737.java");
        assertTrue(out.contains("(java.util.stream.Stream<List>) v.stream().map(x -> x)"), out);
        assertTrue(compile(root).success(), out);
    }

    @Test
    void rewritesTheQualifiedCastTheDiagnosticNamesAndLeavesTheDecoySimpleCastAlone(@TempDir Path root) throws IOException {
        write(root, "rs/List.java", """
                package rs;

                public final class List {
                }
                """);
        write(root, "rs/Class737.java", """
                package rs;

                import java.util.List;
                import java.util.stream.Stream;

                public class Class737 {
                   Stream<rs.List> method1(java.util.Collection<java.util.List<rs.List>> var1, List other) { System.out.println((List) other.stream()); return var1.stream().flatMap(v -> (rs.List) v.stream().map(x -> x)); }
                }
                """);
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("java.util.stream.Stream<rs.List> cannot be converted to rs.List")),
                "the fixture must produce the stream-cast diagnostic");

        var result = fix(root, outcome);

        assertEquals(1, result.fixes(), "the decoy binds to java.util.List, not rs.List");
        String out = read(root, "rs/Class737.java");
        assertTrue(out.contains("System.out.println((List) other.stream());"), out);
        assertTrue(out.contains("(java.util.stream.Stream<rs.List>) v.stream()"), out);
        assertTrue(compile(root).success(), out);
    }

    @Test
    void retypeATypedStreamCastWhoseTargetJavacNames(@TempDir Path root) throws IOException {
        write(root, "rs/Widget.java", """
                package rs;

                public final class Widget {
                }
                """);
        write(root, "rs/Class737.java", """
                package rs;

                import java.util.stream.Stream;

                public class Class737 {
                   Stream<rs.Widget> method1(java.util.Collection<java.util.List<rs.Widget>> var1) {
                      return var1.stream().flatMap(v -> (rs.Widget) v.stream().map(x -> x));
                   }
                }
                """);
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("java.util.stream.Stream<rs.Widget> cannot be converted to rs.Widget")),
                "the fixture must produce the typed stream-cast diagnostic");

        var result = fix(root, outcome);

        assertEquals(1, result.fixes());
        String out = read(root, "rs/Class737.java");
        assertTrue(out.contains("(java.util.stream.Stream<rs.Widget>) v.stream().map(x -> x)"), out);
        assertTrue(compile(root).success(), out);
    }

    @Test
    void leavesAnIntStreamCastAlone(@TempDir Path root) throws IOException {
        write(root, "rs/Widget.java", """
                package rs;

                public final class Widget {
                }
                """);
        write(root, "rs/Class737.java", """
                package rs;

                public class Class737 {
                   rs.Widget method1(java.util.List<String> other) {
                      return (rs.Widget) (java.util.stream.IntStream) other.stream().mapToInt(String::length);
                   }
                }
                """);
        String before = read(root, "rs/Class737.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("java.util.stream.IntStream cannot be converted to rs.Widget")),
                "the fixture must produce the IntStream diagnostic");

        assertEquals(0, fix(root, outcome).fixes(), "an IntStream is not a Stream<Widget>");
        assertEquals(before, read(root, "rs/Class737.java"));
    }

    @Test
    void retypeAStreamCastWhoseTargetIsAnUnrelatedUserType(@TempDir Path root) throws IOException {
        write(root, "rs/Widget.java", """
                package rs;

                public final class Widget {
                }
                """);
        write(root, "rs/Class737.java", """
                package rs;

                import java.util.stream.Stream;

                public class Class737 {
                   Stream<rs.Widget> method1(java.util.List<Object> var1) {
                      return var1.stream().flatMap(v -> { java.util.Collection var1x = (java.util.Collection) v; return (rs.Widget) var1x.stream().map(x -> x); });
                   }
                }
                """);
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("java.util.stream.Stream cannot be converted to rs.Widget")),
                "the fixture must produce the stream-cast diagnostic");

        var result = fix(root, outcome);

        assertEquals(1, result.fixes(), "a user-domain type is a legitimate stream element type");
        String out = read(root, "rs/Class737.java");
        assertTrue(out.contains("(java.util.stream.Stream<rs.Widget>) var1x.stream()"), out);
        assertTrue(compile(root).success(), out);
    }

    @Test
    void leavesAStreamCastToAJavaUtilQueueTargetAlone(@TempDir Path root) throws IOException {
        write(root, "rs/Class737.java", """
                package rs;

                public class Class737 {
                   java.util.Queue<String> method1247(java.util.List<String> var1) { System.out.println((java.util.Queue) var1.stream()); return var1.stream(); }
                }
                """);
        String before = read(root, "rs/Class737.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("cannot be converted to java.util.Queue")),
                "the fixture must produce the stream-cast diagnostic");

        assertEquals(0, fix(root, outcome).fixes(), "a stream really cannot be a Queue");
        assertEquals(before, read(root, "rs/Class737.java"));
    }

    @Test
    void leavesAStreamCastToAFinalContainerTypeAlone(@TempDir Path root) throws IOException {
        write(root, "rs/Class737.java", """
                package rs;

                public class Class737 {
                   java.util.Optional method1247(java.util.List someList) {
                      return (java.util.Optional) someList.stream();
                   }
                }
                """);
        String before = read(root, "rs/Class737.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("java.util.stream.Stream cannot be converted to java.util.Optional")),
                "the fixture must produce the stream-cast diagnostic");

        assertEquals(0, fix(root, outcome).fixes(), "Optional is a container, not a stream element type");
        assertEquals(before, read(root, "rs/Class737.java"));
    }

    @Test
    void declinesWhenOneLineCarriesTwoForEachObjectCasts(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                   void method1(int[] field620, int[] field621) {
                      for (Object object3 : (Object)field620) { for (Object object4 : (Object)field621) { } }
                   }
                }
                """);
        String before = read(root, "rs/Client.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("for-each not applicable")),
                "the fixture must produce the for-each diagnostic");

        assertEquals(0, fix(root, outcome).fixes(), "two candidate casts on one line is ambiguous");
        assertEquals(before, read(root, "rs/Client.java"));
    }

    @Test
    void rewritesOnlyTheRealCastWhenABlockCommentSpansLines(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                   int[] field620;
                   /*
                    * for (Object object3 : (Object)field620) {
                    * ((List)field620).stream().mapToInt(Integer::intValue)
                    * (java.util.stream.Stream<rs.Client>) field620
                    */
                   void method1() {
                      for (Object object3 : (Object)field620) {
                         System.out.println(object3);
                      }
                   }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        String out = read(root, "rs/Client.java");
        assertTrue(out.contains("   /*"), out);
        assertTrue(out.contains("    * for (Object object3 : (Object)field620) {"), out);
        assertTrue(out.contains("    * ((List)field620).stream().mapToInt(Integer::intValue)"), out);
        assertTrue(out.contains("    * (java.util.stream.Stream<rs.Client>) field620"), out);
        assertTrue(out.contains("    */"), out);
        assertTrue(out.contains("for (Object object3 : field620) {"), out);
        assertTrue(compile(root).success(), out);
    }

    @Test
    void skipsACStyleForHeaderOnTheFlaggedLine(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                   int[] field620;
                   void method1() {
                      for (int i = 0; i < 2; i++) { for (Object object3 : (Object)field620) { } }
                   }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        String out = read(root, "rs/Client.java");
        assertTrue(out.contains("for (int i = 0; i < 2; i++) { for (Object object3 : field620) { } }"), out);
        assertTrue(compile(root).success(), out);
    }

    @Test
    void leavesAForEachAloneWhenTheOnlyObjectCastTextIsInsideAStringOrComment(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                   void method1(Object field620) {
                      for (Object object3 : field620) { String s = "(Object)field620"; } // (Object)field620
                   }
                }
                """);
        String before = read(root, "rs/Client.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("for-each not applicable")),
                "the fixture must produce the for-each diagnostic");

        assertEquals(0, fix(root, outcome).fixes());
        assertEquals(before, read(root, "rs/Client.java"));
    }

    @Test
    void leavesAForEachAloneWhenTheSourceIsCastToSomethingOtherThanObject(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                   void method1(Object field620) {
                      for (Object object3 : (Integer)field620) {
                         System.out.println(object3);
                      }
                   }
                }
                """);
        String before = read(root, "rs/Client.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("for-each not applicable")),
                "the fixture must produce the for-each diagnostic");

        assertEquals(0, fix(root, outcome).fixes(), "only the (Object) cast is this fixer's business");
        assertEquals(before, read(root, "rs/Client.java"));
    }

    @Test
    void leavesAnAmbiguousMethodReferenceLineAlone(@TempDir Path root) throws IOException {
        write(root, "rs/Class735.java", """
                package rs;

                import java.util.List;

                public class Class735 {
                   protected int[] method1247(Object var1) {
                      return ((List)var1).stream().mapToInt(Integer::intValue).toArray();
                   }
                   protected long method1248(Object var1) {
                      return ((List)var1).stream().mapToInt(Integer::intValue).count() + ((List)var1).stream().mapToInt(Integer::intValue).count();
                   }
                }
                """);

        var result = fix(root);
        String out = read(root, "rs/Class735.java");

        assertEquals(1, result.fixes(), "only the single-reference line is unambiguous");
        assertTrue(out.contains("((List<Integer>)var1).stream().mapToInt(Integer::intValue).toArray()"), out);
        assertTrue(out.contains("return ((List)var1).stream().mapToInt(Integer::intValue).count()"
                        + " + ((List)var1).stream().mapToInt(Integer::intValue).count();"),
                "two method references on one line is ambiguous: " + out);
    }

    @Test
    void leavesALineWithTwoCandidateStreamCastsAlone(@TempDir Path root) throws IOException {
        write(root, "rs/runelite/pkg819/Class781.java", """
                package rs.runelite.pkg819;

                public final class Class781 {
                }
                """);
                write(root, "rs/plugins/groundmarkers/GroundMarkersPlugin.java", """
                package rs.plugins.groundmarkers;

                import java.util.Collection;

                public class GroundMarkersPlugin {
                   void method1(Collection var1, Collection var2) {
                      System.out.println((rs.runelite.pkg819.Class781) var1.stream().map(var1x -> var1x) + "|" + (rs.runelite.pkg819.Class781) var2.stream().map(var2x -> var2x));
                   }
                }
                """);
        String before = read(root, "rs/plugins/groundmarkers/GroundMarkersPlugin.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("java.util.stream.Stream cannot be converted to rs.runelite.pkg819.Class781")),
                "the fixture must produce the stream-cast diagnostic");

        assertEquals(0, fix(root, outcome).fixes(), "two casts naming the same target on the flagged line");
        assertEquals(before, read(root, "rs/plugins/groundmarkers/GroundMarkersPlugin.java"));
    }

    @Test
    void leavesACastThatAlreadyCarriesATypeArgumentAlone(@TempDir Path root) throws IOException {
        write(root, "rs/Class737.java", """
                package rs;

                import java.util.List;

                public class Class737 {
                   protected int[] method1247(Object var1) {
                      return ((List<Object>)var1).stream().mapToInt(Integer::intValue).toArray();
                   }
                }
                """);
        String before = read(root, "rs/Class737.java");
        var outcome = compile(root);
        assertTrue(outcome.diagnostics().stream().anyMatch(d -> d.getMessage(java.util.Locale.ENGLISH)
                        .contains("invalid method reference")),
                "the fixture must produce the method-reference diagnostic");

        assertEquals(0, fix(root, outcome).fixes(), "no stacking onto an existing type argument");
        assertEquals(before, read(root, "rs/Class737.java"));
    }

    @Test
    void aSecondPassOverTheSameDiagnosticsChangesNothing(@TempDir Path root) throws IOException {
        write(root, "rs/runelite/pkg819/Class781.java", """
                package rs.runelite.pkg819;

                public final class Class781 {
                }
                """);
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                   int[] field620;
                   void method1() {
                      for (Object object3 : (Object)field620) {
                         System.out.println(object3);
                      }
                   }
                }
                """);
        write(root, "rs/Class735.java", """
                package rs;

                import java.util.List;

                public class Class735 {
                   protected int[] method1247(Object var1) {
                      return ((List)var1).stream().mapToInt(Integer::intValue).toArray();
                   }
                }
                """);
        write(root, "rs/plugins/groundmarkers/GroundMarkersPlugin.java", """
                package rs.plugins.groundmarkers;

                import java.util.Collection;
                import java.util.stream.Stream;

                public class GroundMarkersPlugin {
                   Stream<rs.runelite.pkg819.Class781> method1(Collection var1) {
                      return var1.stream().flatMap(var0 -> { Collection var1x = (Collection) var0; return (rs.runelite.pkg819.Class781) var1x.stream().map(var1xx -> new rs.runelite.pkg819.Class781()); });
                   }
                }
                """);

        var diagnostics = bucketer.categorize(compile(root).diagnostics());
        assertEquals(3, CompileFixLoop.CollectionSourceFixer.tryFixAll(root, diagnostics).fixes());
        String after = Files.readString(root.resolve("rs/Client.java"))
                + Files.readString(root.resolve("rs/Class735.java"))
                + Files.readString(root.resolve("rs/plugins/groundmarkers/GroundMarkersPlugin.java"));
        assertEquals(0, CompileFixLoop.CollectionSourceFixer.tryFixAll(root, diagnostics).fixes(),
                "every rewrite is already in place");
        assertEquals(after, Files.readString(root.resolve("rs/Client.java"))
                + Files.readString(root.resolve("rs/Class735.java"))
                + Files.readString(root.resolve("rs/plugins/groundmarkers/GroundMarkersPlugin.java")));
        assertTrue(compile(root).success(), after);
    }

    @Test
    void countsAnIdenticalRepeatedDiagnosticOnOneLineOnce(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                   int[] field620;
                   void method1() {
                      for (Object object3 : (Object)field620) {
                         System.out.println(object3);
                      }
                   }
                }
                """);

        var diagnostics = bucketer.categorize(compile(root).diagnostics());
        var repeated = new java.util.ArrayList<DiagnosticBucketer.Bucketed>(diagnostics);
        repeated.addAll(diagnostics);
        var result = CompileFixLoop.CollectionSourceFixer.tryFixAll(root, repeated);

        assertEquals(1, result.fixes());
        assertEquals(1, result.handled().size());
        assertEquals(1, read(root, "rs/Client.java").lines()
                .filter(line -> line.contains("for (Object object3 : field620)")).count());
    }

    @Test
    void unhandledWithholdsOnlyWhatThisFixerAddressed(@TempDir Path root) throws IOException {
        write(root, "rs/Client.java", """
                package rs;

                public class Client {
                   int[] field620;
                   void method1() {
                      for (Object object3 : (Object)field620) {
                         System.out.println(object3);
                      }
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
        write(root, "rs/Client.java", """
                package rs;

                import java.util.List;

                public class Client {
                   int[] method1(List<String> var1) {
                      for (Object object3 : var1) {
                         System.out.println(object3);
                      }
                      return null;
                   }
                }
                """);
        String before = read(root, "rs/Client.java");
        var outcome = compile(root);
        assertTrue(outcome.success(), "the fixture must compile as-is");

        assertEquals(0, fix(root, outcome).fixes());
        assertEquals(before, read(root, "rs/Client.java"));
    }
}
