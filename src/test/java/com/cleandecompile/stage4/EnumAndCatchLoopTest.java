package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cleandecompile.PipelineConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The second manifest remainder, end to end through {@link CompileFixLoop#run}
 * at {@code --release 11}: one unsugared enum whose {@code switch}es in other
 * files each cost an error per case label (Class275 / Class272 / DevCommands),
 * plus a dead catch (Class735). Everything must converge together.
 */
class EnumAndCatchLoopTest {

    private static void write(Path root, String rel, String source) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
    }

    @Test
    void unsugaredEnumAndItsSwitchCascadeConvergeWithADeadCatch(@TempDir Path temp) throws IOException {
        Path out = temp.resolve("out");
        Path src = out.resolve("src-generated/src/main/java");
        // Class275: the enum, with a switch on itself.
        write(src, "rs/pkg/Class275.java", """
                package rs.pkg;

                public class Class275 extends Enum<Class275> {
                    public static final Class275 field1 = new Class275("a", 1);
                    public static final Class275 field2 = new Class275("b", 2);
                    public static final Class275 field3 = new Class275("c", 3);
                    private final String code;
                    private final int weight;

                    private Class275(String var1, int var2) {
                        this.code = var1;
                        this.weight = var2;
                    }

                    public int method1() {
                        switch (this) {
                            case field1:
                                return this.weight;
                            case field2:
                                return this.weight * 2;
                            default:
                                return 0;
                        }
                    }
                }
                """);
        // Class272 and DevCommands: switches on it from other files.
        write(src, "rs/pkg/Class272.java", """
                package rs.pkg;

                public class Class272 {
                    static String name(Class275 var0) {
                        switch (var0) {
                            case field3:
                                return "c";
                            default:
                                return "?";
                        }
                    }
                }
                """);
        write(src, "rs/DevCommands.java", """
                package rs;

                import rs.pkg.Class275;

                public class DevCommands {
                    static int run(Class275 var0) {
                        switch (var0) {
                            case field1:
                                return 1;
                            case field2:
                                return 2;
                            default:
                                return 0;
                        }
                    }
                }
                """);
        // Class735: catch of an exception the (stripped) callee declaration no longer mentions.
        write(src, "rs/Class735.java", """
                package rs;

                import java.io.FileNotFoundException;

                public class Class735 {
                    static void method1() { }

                    static int method2() {
                        try {
                            method1();
                            return 1;
                        } catch (FileNotFoundException var0) {
                            return 2;
                        }
                    }
                }
                """);

        PipelineConfig config = new PipelineConfig(temp.resolve("in.jar"), out, List.of("rs"),
                PipelineConfig.DEFAULT_TIMEOUT_MS, 30, null, false, "11", null, null, null, null);
        CompileFixLoop.LoopReport report = new CompileFixLoop().run(config);

        assertTrue(report.converged(), "remaining: " + report.remainingErrorSummaries());
        assertEquals(List.of(), report.remainingErrorSummaries());
        assertTrue(Files.readString(src.resolve("rs/pkg/Class275.java")).contains("enum Class275"));
        assertTrue(Files.readString(src.resolve("rs/Class735.java")).contains("catch (FileNotFoundException var0)"));
    }
}
