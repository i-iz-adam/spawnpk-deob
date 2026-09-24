package com.cleandecompile.stage2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Selection regression fixtures: real Vineflower/CFR outputs for
 * {@code rs/runelite/pkg1037/Class991} (captured 2026-09-23), where
 * Vineflower collapses capturing lambdas into wrong-arity static refs
 * ({@code anyMatch(Class991::method2029)}) and CFR renders them correctly
 * ({@code anyMatch(arg -> Class991.method2029(d, d2, arg))}).
 *
 * <p>The Vineflower fixture contains eight such refs: six {@code anyMatch}
 * predicates (3-, 5- and 2-arg statics where the SAM takes one argument) and
 * two {@code map(Class991::method91 / method4424)} in
 * {@code getPathIterator} (2- and 3-arg statics, likewise collapsed from
 * {@code arg -> Class991.method91(affineTransform, arg)}).
 */
class OutputSelectorTest {

    private static String fixture(String name) throws Exception {
        return Files.readString(Path.of("src/test/resources/selection", name));
    }

    @Test
    void cfrWinsCollapsedLambda() throws Exception {
        String vineflower = fixture("vf991.java");
        String cfr = fixture("cfr991.java");
        int vineflowerBadRefs = OutputSelector.MethodRefCheck.mismatchedCount(vineflower);
        int cfrBadRefs = OutputSelector.MethodRefCheck.mismatchedCount(cfr);
        System.out.println("badRefs vineflower=" + vineflowerBadRefs + " cfr=" + cfrBadRefs);
        assertEquals(8, vineflowerBadRefs,
                "expected all eight collapsed refs flagged (six anyMatch + two map)");
        assertEquals(0, cfrBadRefs);
        var best = new OutputSelector().pickBest(Map.of("vineflower", vineflower, "cfr", cfr));
        assertEquals("cfr", best.decompilerName());
    }
}