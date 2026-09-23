/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  java.awt.Rectangle
 *  java.awt.Shape
 *  java.awt.geom.AffineTransform
 *  java.awt.geom.PathIterator
 *  java.awt.geom.Point2D
 *  java.awt.geom.Rectangle2D
 *  java.awt.geom.Rectangle2D$Double
 *  java.lang.Double
 *  java.lang.Integer
 *  java.lang.Math
 *  java.lang.Object
 *  java.util.Arrays
 *  java.util.Iterator
 *  java.util.List
 */
package rs.runelite.pkg1037;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import rs.runelite.pkg1037.Class990;

public class Class991<T extends Shape>
implements Shape {
    private final List<T> field6365;

    public Class991(T ... TArray) {
        this(Arrays.asList((Object[])TArray));
    }

    public Rectangle getBounds() {
        int n = Integer.MAX_VALUE;
        int n2 = Integer.MAX_VALUE;
        int n3 = Integer.MIN_VALUE;
        int n4 = Integer.MIN_VALUE;
        for (Shape shape : this.field6365) {
            Rectangle rectangle = shape.getBounds();
            n = Math.min((int)rectangle.x, (int)n);
            n2 = Math.min((int)rectangle.y, (int)n2);
            n3 = Math.max((int)(rectangle.x + rectangle.width), (int)n3);
            n4 = Math.max((int)(rectangle.y + rectangle.height), (int)n4);
        }
        return new Rectangle(n, n2, n3 - n, n4 - n2);
    }

    public Rectangle2D getBounds2D() {
        double d = Double.MAX_VALUE;
        double d2 = Double.MAX_VALUE;
        double d3 = Double.MIN_VALUE;
        double d4 = Double.MIN_VALUE;
        for (Shape shape : this.field6365) {
            Rectangle2D rectangle2D = shape.getBounds2D();
            d = Math.min((double)rectangle2D.getX(), (double)d);
            d2 = Math.min((double)rectangle2D.getY(), (double)d2);
            d3 = Math.max((double)rectangle2D.getMaxX(), (double)d3);
            d4 = Math.max((double)rectangle2D.getMaxY(), (double)d4);
        }
        return new Rectangle2D.Double(d, d2, d3 - d, d4 - d2);
    }

    public boolean contains(double d, double d2) {
        return this.field6365.stream().anyMatch(arg_0 -> Class991.method2029(d, d2, arg_0));
    }

    public boolean contains(Point2D point2D) {
        return this.field6365.stream().anyMatch(arg_0 -> Class991.method4644(point2D, arg_0));
    }

    public boolean intersects(double d, double d2, double d3, double d4) {
        return this.field6365.stream().anyMatch(arg_0 -> Class991.method704(d, d2, d3, d4, arg_0));
    }

    public boolean intersects(Rectangle2D rectangle2D) {
        return this.field6365.stream().anyMatch(arg_0 -> Class991.method1987(rectangle2D, arg_0));
    }

    public boolean contains(double d, double d2, double d3, double d4) {
        return this.field6365.stream().anyMatch(arg_0 -> Class991.method4882(d, d2, d3, d4, arg_0));
    }

    public boolean contains(Rectangle2D rectangle2D) {
        return this.field6365.stream().anyMatch(arg_0 -> Class991.method1502(rectangle2D, arg_0));
    }

    public PathIterator getPathIterator(AffineTransform affineTransform) {
        return new Class990((Iterator<PathIterator>)this.field6365.stream().map(arg_0 -> Class991.method91(affineTransform, arg_0)).iterator());
    }

    public PathIterator getPathIterator(AffineTransform affineTransform, double d) {
        return new Class990((Iterator<PathIterator>)this.field6365.stream().map(arg_0 -> Class991.method4424(affineTransform, d, arg_0)).iterator());
    }

    public Class991(List<T> list) {
        this.field6365 = list;
    }

    public List<T> method153() {
        return this.field6365;
    }

    private static PathIterator method4424(AffineTransform affineTransform, double d, Shape shape) {
        return shape.getPathIterator(affineTransform, d);
    }

    private static PathIterator method91(AffineTransform affineTransform, Shape shape) {
        return shape.getPathIterator(affineTransform);
    }

    private static boolean method1502(Rectangle2D rectangle2D, Shape shape) {
        return shape.contains(rectangle2D);
    }

    private static boolean method4882(double d, double d2, double d3, double d4, Shape shape) {
        return shape.contains(d, d2, d3, d4);
    }

    private static boolean method1987(Rectangle2D rectangle2D, Shape shape) {
        return shape.intersects(rectangle2D);
    }

    private static boolean method704(double d, double d2, double d3, double d4, Shape shape) {
        return shape.intersects(d, d2, d3, d4);
    }

    private static boolean method4644(Point2D point2D, Shape shape) {
        return shape.contains(point2D);
    }

    private static boolean method2029(double d, double d2, Shape shape) {
        return shape.contains(d, d2);
    }
}
