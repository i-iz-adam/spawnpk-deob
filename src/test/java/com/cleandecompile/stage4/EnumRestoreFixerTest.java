package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * An enum printed as {@code class X extends Enum<X>} (the Class275 shape).
 * The fixtures are real decompiler output for one enum, captured from
 * Vineflower 1.10.1 and CFR 0.152, plus the files that {@code switch} on it:
 * javac blames every case label there too, and those errors must disappear
 * with the one fix. Compiled at release 11 like the real run.
 */
class EnumRestoreFixerTest {

    private static final String RELEASE = "11";
    private final JavacRunner javac = new JavacRunner();
    private final DiagnosticBucketer bucketer = new DiagnosticBucketer();

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    private long errors(Path root, String tag) throws IOException {
        return javac.compile(root, root.resolveSibling("classes-" + tag), List.of(), RELEASE).diagnostics().size();
    }

    private SpanFixers.Result fix(Path root) throws IOException {
        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), RELEASE);
        assertTrue(outcome.diagnostics().size() > 0, "fixture must fail to compile");
        return SpanFixers.run(root, bucketer.categorize(outcome.diagnostics()), List.of(new EnumRestoreFixer()));
    }

    /** Files switching on the enum, one per way a decompiler spells that. */
    private static void writeSwitchUsers(Path root) throws IOException {
        write(root, "rs/Users.java", """
                package rs;

                public class Users {
                    public static String describe(Kind k) {
                        switch (k) {
                            case ALPHA: return "first";
                            case BETA: return "second";
                            default: return "other";
                        }
                    }
                }
                """);
    }

    @Test
    void vineflowerUnsugaredHiddenParameterForm(@TempDir Path root) throws IOException {
        // Vineflower with enum sugar off: values()/valueOf() as printed, no $VALUES field at all,
        // a super(name, ordinal) call and hidden constructor parameters.
        write(root, "rs/Kind.java", """
                package rs;

                public final class Kind extends Enum<Kind> {
                   public static final Kind ALPHA = new Kind("ALPHA", 0, "a", 1);
                   public static final Kind BETA = new Kind("BETA", 1, "b", 2);
                   public static final Kind GAMMA = new Kind("GAMMA", 2, "c", 3);
                   private final String code;
                   private final int weight;

                   public static Kind[] values() {
                      return (Kind[])$VALUES.clone();
                   }

                   public static Kind valueOf(String param0) {
                      return Enum.valueOf(Kind.class, var0);
                   }

                   private Kind(String param1, int nullx, String nullxx, int nullxxx) {
                      super(var1, nullx);
                      this.code = nullxx;
                      this.weight = nullxxx;
                   }

                   public int score() {
                      switch (this) {
                         case ALPHA:
                            return this.weight * 2;
                         case BETA:
                            return this.weight * 3;
                         default:
                            return this.weight;
                      }
                   }

                   public String code() {
                      return this.code;
                   }
                }
                """);
        writeSwitchUsers(root);
        String before = Files.readString(root.resolve("rs/Kind.java"));

        var result = fix(root);

        assertEquals(1, result.fixes());
        String after = Files.readString(root.resolve("rs/Kind.java"));
        assertEquals(before.lines().count(), after.lines().count(), "must be line-neutral:\n" + after);
        assertTrue(after.contains("public enum Kind"), after);
        assertTrue(after.contains("ALPHA(\"a\", 1),"), after);
        assertTrue(after.contains("GAMMA(\"c\", 3);"), after);
        assertFalse(after.contains("values()"), after);
        assertFalse(after.contains("super("), after);
        assertEquals(0, errors(root, "vf"), after);
    }

    @Test
    void cfrUnsugaredFormWithStaticBlockAndValuesHelper(@TempDir Path root) throws IOException {
        // CFR --sugarenums false, verbatim: $VALUES filled by a static block through $values().
        write(root, "rs/Kind.java", """
                package rs;

                public final class Kind
                extends Enum<Kind> {
                    public static final /* enum */ Kind ALPHA = new Kind("ALPHA", 0, "a", 1);
                    public static final /* enum */ Kind BETA = new Kind("BETA", 1, "b", 2);
                    public static final /* enum */ Kind GAMMA = new Kind("GAMMA", 2, "c", 3);
                    private final String code;
                    private final int weight;
                    private static final /* synthetic */ Kind[] $VALUES;

                    public static Kind[] values() {
                        return (Kind[])$VALUES.clone();
                    }

                    public static Kind valueOf(String string) {
                        return Enum.valueOf(Kind.class, string);
                    }

                    private Kind(String string, int n, String string2, int n2) {
                        super(string, n);
                        this.code = string2;
                        this.weight = n2;
                    }

                    public int score() {
                        switch (this.ordinal()) {
                            case 0: {
                                return this.weight * 2;
                            }
                            case 1: {
                                return this.weight * 3;
                            }
                        }
                        return this.weight;
                    }

                    public String code() {
                        return this.code;
                    }

                    private static /* synthetic */ Kind[] $values() {
                        return new Kind[]{ALPHA, BETA, GAMMA};
                    }

                    static {
                        $VALUES = Kind.$values();
                    }
                }
                """);
        writeSwitchUsers(root);
        String before = Files.readString(root.resolve("rs/Kind.java"));

        var result = fix(root);

        assertEquals(1, result.fixes());
        String after = Files.readString(root.resolve("rs/Kind.java"));
        assertEquals(before.lines().count(), after.lines().count(), after);
        assertTrue(after.contains("public enum Kind"), after);
        assertFalse(after.contains("$VALUES"), "helpers the enum regenerates must go:\n" + after);
        assertFalse(after.contains("$values"), after);
        assertEquals(0, errors(root, "cfr"), after);
    }

    @Test
    void cfrPlainFormWithRenamedHelpersKeepsThemAndInitialisesTheArray(@TempDir Path root) throws IOException {
        // CFR's output for an enum whose values()/valueOf()/$VALUES an obfuscator renamed:
        // no hidden parameters, no super call, the backing array `d` has lost its initialiser.
        write(root, "rs/Kind.java", """
                package rs;

                public final class Kind
                extends Enum<Kind> {
                    public static final /* enum */ Kind a1 = new Kind("a", 1);
                    public static final /* enum */ Kind a2 = new Kind("b", 2);
                    public static final /* enum */ Kind a3 = new Kind("c", 3);
                    private final String code;
                    private final int weight;
                    private static final /* synthetic */ Kind[] d;

                    public static Kind[] b() {
                        return (Kind[])d.clone();
                    }

                    public static Kind c(String string) {
                        return Enum.valueOf(Kind.class, string);
                    }

                    private Kind(String string2, int n2) {
                        this.code = string2;
                        this.weight = n2;
                    }

                    public int score() {
                        switch (this.ordinal()) {
                            case 0: {
                                return this.weight * 2;
                            }
                        }
                        return this.weight;
                    }
                }
                """);
        write(root, "rs/Users.java", """
                package rs;

                public class Users {
                    static int total() {
                        int sum = 0;
                        for (Kind k : Kind.b()) sum += k.score();
                        return sum + Kind.c("a1").score();
                    }
                    static String name(Kind k) {
                        switch (k) {
                            case a1: return "x";
                            case a2: return "y";
                            default: return "z";
                        }
                    }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        String after = Files.readString(root.resolve("rs/Kind.java"));
        assertTrue(after.contains("a1(\"a\", 1),"), after);
        assertTrue(after.contains("Kind[] b()"), "renamed values() is used elsewhere and must stay:\n" + after);
        assertTrue(after.contains("d = new Kind[]{a1, a2, a3}"), after);
        assertEquals(0, errors(root, "plain"), after);
    }

    @Test
    void chainedThisConstructorAndPublicConstructorModifier(@TempDir Path root) throws IOException {
        write(root, "rs/Kind.java", """
                package rs;

                public class Kind extends Enum<Kind> {
                    public static final Kind A = new Kind("A", 0);
                    public static final Kind B = new Kind("B", 1, 5);
                    private final int n;

                    public Kind(String name, int ordinal) {
                        this(name, ordinal, 0);
                    }

                    protected Kind(String name, int ordinal, int n) {
                        super(name, ordinal);
                        this.n = n;
                    }

                    int n() { return n; }
                }
                """);

        var result = fix(root);

        assertEquals(1, result.fixes());
        String after = Files.readString(root.resolve("rs/Kind.java"));
        assertTrue(after.contains("enum Kind"), after);
        assertEquals(0, errors(root, "chain"), after);
    }

    @Test
    void declinesShapesItCannotConvertSafely(@TempDir Path root) throws IOException {
        // A constant with its own body: enum syntax exists for it, but this planner does not guess.
        write(root, "rs/Bodied.java", """
                package rs;

                public class Bodied extends Enum<Bodied> {
                    public static final Bodied A = new Bodied("A", 0) { int f() { return 1; } };
                    private Bodied(String n, int o) { super(n, o); }
                }
                """);
        // Ordinals that do not match declaration order: converting would silently renumber.
        write(root, "rs/Shuffled.java", """
                package rs;

                public class Shuffled extends Enum<Shuffled> {
                    public static final Shuffled A = new Shuffled("A", 1);
                    public static final Shuffled B = new Shuffled("B", 0);
                    private Shuffled(String n, int o) { super(n, o); }
                }
                """);
        // A values() that is not the canonical clone-of-the-array body.
        write(root, "rs/Odd.java", """
                package rs;

                public class Odd extends Enum<Odd> {
                    public static final Odd A = new Odd();
                    public static Odd[] values() { return new Odd[0]; }
                }
                """);

        var result = fix(root);

        assertEquals(0, result.fixes());
        assertTrue(Files.readString(root.resolve("rs/Bodied.java")).contains("extends Enum<Bodied>"));
        assertTrue(Files.readString(root.resolve("rs/Shuffled.java")).contains("extends Enum<Shuffled>"));
        assertTrue(Files.readString(root.resolve("rs/Odd.java")).contains("extends Enum<Odd>"));
    }
    @Test
    void unqualifiesCaseLabelsTheEarlierFixersQualified(@TempDir Path root) throws IOException {
        // Once the class is a real enum, release 11 rejects `case Kind.ALPHA:`.
        write(root, "rs/Kind.java", """
                package rs;

                public enum Kind { ALPHA, BETA }
                """);
        write(root, "rs/Users.java", """
                package rs;

                public class Users {
                    static int f(Kind k) {
                        switch (k) {
                            case Kind.ALPHA: return 1;
                            case rs.Kind.BETA:
                                return 2;
                            default: return 0;
                        }
                    }
                }
                """);
        var outcome = javac.compile(root, root.resolveSibling("classes"), List.of(), RELEASE);
        var result = SpanFixers.run(root, bucketer.categorize(outcome.diagnostics()),
                List.of(new EnumCaseLabelFixer()));

        assertEquals(2, result.fixes());
        String after = Files.readString(root.resolve("rs/Users.java"));
        assertTrue(after.contains("case ALPHA: return 1;") && after.contains("case BETA:"), after);
        assertEquals(0, errors(root, "labels"), after);
    }
}
