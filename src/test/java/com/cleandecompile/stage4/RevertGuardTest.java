package com.cleandecompile.stage4;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * The per-file revert guard must only fire on certain regressions (a clean
 * file gaining errors). Reverting broken files whose count rose punishes
 * progressive fixes that legitimately reveal deeper errors.
 */
class RevertGuardTest {

    private static boolean shouldRevert(int before, int after) throws Exception {
        Method m = CompileFixLoop.class.getDeclaredMethod("shouldRevert", int.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, before, after);
    }

    @Test
    void revertsOnlyFilesThatWereClean() throws Exception {
        assertTrue(shouldRevert(0, 1), "clean file gaining an error is a certain regression");
        assertTrue(shouldRevert(0, 5), "clean file gaining errors is a certain regression");
        assertFalse(shouldRevert(0, 0), "untouched counts are not a regression");
        assertFalse(shouldRevert(3, 5), "rising count on a broken file is expected reveal, not regress");
        assertFalse(shouldRevert(3, 1), "falling count is progress");
        assertFalse(shouldRevert(3, 3), "steady count is not a regression");
    }
}
