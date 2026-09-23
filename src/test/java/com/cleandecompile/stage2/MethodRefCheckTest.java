package com.cleandecompile.stage2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MethodRefCheckTest {

    @Test
    void flagsCollapsedCapturingLambda() {
        String source = """
                package rs.pkg;
                import java.util.List;
                public class Class991<T extends java.awt.Shape> {
                   private final List<T> field6365;
                   public boolean contains(double var1, double var3) {
                      return this.field6365.stream().anyMatch(Class991::method2029);
                   }
                   private static boolean method2029(double var0, double var2, java.awt.Shape var4) {
                      return true;
                   }
                }
                """;
        assertTrue(OutputSelector.MethodRefCheck.mismatchedCount(source) >= 1);
    }

    @Test
    void acceptsValidBoundAndUnboundRefs() {
        String source = """
                package rs.pkg;
                import java.util.List;
                public class Holder {
                   public boolean any(java.util.function.Predicate<String> p, String v) {
                      return p.test(v);
                   }
                   public boolean test1(List<String> items) {
                      return items.stream().anyMatch(this::isLong);
                   }
                   private boolean isLong(String s) {
                      return s.length() > 3;
                   }
                   public boolean test2(List<String> items) {
                      return items.stream().anyMatch(String::isEmpty);
                   }
                }
                """;
        assertEquals(0, OutputSelector.MethodRefCheck.mismatchedCount(source));
    }

    @Test
    void acceptsCrossFileRefsWithoutPenalty() {
        String source = """
                package rs.pkg;
                import rs.eventbus.Class175;
                public class Bus {
                   public void sort(java.util.List<Class175> items) {
                      items.sort(java.util.Comparator.comparingDouble(Class175::getPriority));
                   }
                }
                """;
        assertEquals(0, OutputSelector.MethodRefCheck.mismatchedCount(source));
    }

    @Test
    void flagsTreeMapComparatorMismatch() {
        String source = """
                package rs.pkg;
                import java.util.TreeMap;
                public class Holder {
                   private final java.util.Map<String, Integer> field = new TreeMap(Holder::method2851);
                   private static int method2851(String var0) {
                      return 0;
                   }
                }
                """;
        assertTrue(OutputSelector.MethodRefCheck.mismatchedCount(source) >= 1);
    }
}
