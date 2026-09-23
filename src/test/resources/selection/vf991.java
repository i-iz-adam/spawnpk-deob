package rs.runelite.pkg1037;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.List;

public class Class991<T extends Shape> implements Shape {
   private final List<T> field6365;

   public Class991(T... var1) {
      this(Arrays.asList(var1));
   }

   public Rectangle getBounds() {
      int var1 = Integer.MAX_VALUE;
      int var2 = Integer.MAX_VALUE;
      int var3 = Integer.MIN_VALUE;
      int var4 = Integer.MIN_VALUE;

      for (Shape var6 : this.field6365) {
         Rectangle var7 = var6.getBounds();
         var1 = Math.min(var7.x, var1);
         var2 = Math.min(var7.y, var2);
         var3 = Math.max(var7.x + var7.width, var3);
         var4 = Math.max(var7.y + var7.height, var4);
      }

      return new Rectangle(var1, var2, var3 - var1, var4 - var2);
   }

   public Rectangle2D getBounds2D() {
      double var1 = Double.MAX_VALUE;
      double var3 = Double.MAX_VALUE;
      double var5 = Double.MIN_VALUE;
      double var7 = Double.MIN_VALUE;

      for (Shape var10 : this.field6365) {
         Rectangle2D var11 = var10.getBounds2D();
         var1 = Math.min(var11.getX(), var1);
         var3 = Math.min(var11.getY(), var3);
         var5 = Math.max(var11.getMaxX(), var5);
         var7 = Math.max(var11.getMaxY(), var7);
      }

      return new java.awt.geom.Rectangle2D.Double(var1, var3, var5 - var1, var7 - var3);
   }

   public boolean contains(double var1, double var3) {
      return this.field6365.stream().anyMatch(Class991::method2029);
   }

   public boolean contains(Point2D var1) {
      return this.field6365.stream().anyMatch(Class991::method4644);
   }

   public boolean intersects(double var1, double var3, double var5, double var7) {
      return this.field6365.stream().anyMatch(Class991::method704);
   }

   public boolean intersects(Rectangle2D var1) {
      return this.field6365.stream().anyMatch(Class991::method1987);
   }

   public boolean contains(double var1, double var3, double var5, double var7) {
      return this.field6365.stream().anyMatch(Class991::method4882);
   }

   public boolean contains(Rectangle2D var1) {
      return this.field6365.stream().anyMatch(Class991::method1502);
   }

   public PathIterator getPathIterator(AffineTransform var1) {
      return new Class990(this.field6365.stream().map(Class991::method91).iterator());
   }

   public PathIterator getPathIterator(AffineTransform var1, double var2) {
      return new Class990(this.field6365.stream().map(Class991::method4424).iterator());
   }

   public Class991(List<T> var1) {
      this.field6365 = var1;
   }

   public List<T> method153() {
      return this.field6365;
   }

   private static PathIterator method4424(AffineTransform var0, double var1, Shape var3) {
      return var3.getPathIterator(var0, var1);
   }

   private static PathIterator method91(AffineTransform var0, Shape var1) {
      return var1.getPathIterator(var0);
   }

   private static boolean method1502(Rectangle2D var0, Shape var1) {
      return var1.contains(var0);
   }

   private static boolean method4882(double var0, double var2, double var4, double var6, Shape var8) {
      return var8.contains(var0, var2, var4, var6);
   }

   private static boolean method1987(Rectangle2D var0, Shape var1) {
      return var1.intersects(var0);
   }

   private static boolean method704(double var0, double var2, double var4, double var6, Shape var8) {
      return var8.intersects(var0, var2, var4, var6);
   }

   private static boolean method4644(Point2D var0, Shape var1) {
      return var1.contains(var0);
   }

   private static boolean method2029(double var0, double var2, Shape var4) {
      return var4.contains(var0, var2);
   }
}
