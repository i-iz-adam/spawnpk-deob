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
        assertEquals(6, vineflowerBadRefs, "expected all six collapsed refs flagged");
        assertEquals(0, cfrBadRefs);
        var best = new OutputSelector().pickBest(Map.of("vineflower", vineflower, "cfr", cfr));
        assertEquals("cfr", best.decompilerName());
    }
}
