package nativecanvas.ui;

import nativecanvas.Canvas;

/** A component with bounds assigned by its parent layout. */
public abstract class Widget {
    Container parent;
    boolean uiRoot;
    private double x, y, width, height;

    public final double x() { return x; }
    public final double y() { return y; }
    public final double width() { return width; }
    public final double height() { return height; }

    /** Natural width used by horizontal layouts. */
    public double preferredWidth() { return 0; }

    static double textWidth(String text) {
        return 10.0 * text.codePointCount(0, text.length());
    }

    public abstract double preferredHeight(double width);

    protected abstract void paint(Canvas canvas);

    final void layout(double x, double y, double width, double height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        layoutChildren();
    }

    protected void layoutChildren() {}

    final boolean contains(double px, double py) {
        return px >= x && px < x + width && py >= y && py < y + height;
    }

    Widget hit(double x, double y) { return contains(x, y) ? this : null; }
}
