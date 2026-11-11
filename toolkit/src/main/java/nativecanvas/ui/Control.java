package nativecanvas.ui;

import nativecanvas.Canvas;

/** A keyboard-focusable widget with a shared interaction style. */
public abstract class Control extends Widget {
    boolean focused, hovered, pressed;

    public abstract void activate();

    /** Pointer coordinates are relative to this control. */
    protected void onPointerPressed(double x, double y) {}
    protected void onPointerDragged(double x, double y) {}
    protected void onPointerReleased(double x, double y) {}
    @Override public double preferredHeight(double width) { return 38; }

    @Override protected void paint(Canvas canvas) {
        canvas.setFillStyle(pressed && hovered ? 0x415875ff : hovered ? 0x34465cff : 0x26364aff);
        canvas.fillRect(x(), y(), width(), height());
        if (focused && width() > 2 && height() > 2) {
            canvas.setLineWidth(2);
            canvas.setStrokeStyle(0x7acffaff);
            canvas.strokeRect(x() + 1, y() + 1, width() - 2, height() - 2);
        }
    }

    protected final void paintText(Canvas canvas, String text, double inset) {
        canvas.setFillStyle(0xf3f6faff);
        canvas.fillText(text, x() + inset, y() + 24);
    }
}
