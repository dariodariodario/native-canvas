package nativecanvas.ui;

import nativecanvas.Canvas;

/** Clips one child to a viewport and scrolls it vertically. */
public final class ScrollPane extends Container {
    private final Widget content;
    private final double preferredHeight;
    private double scroll, limit;

    public ScrollPane(Widget content, double preferredHeight) {
        if (!Double.isFinite(preferredHeight) || preferredHeight <= 0)
            throw new IllegalArgumentException("Invalid viewport height");
        addChild(content);
        this.content = content;
        this.preferredHeight = preferredHeight;
    }

    @Override public double preferredWidth() { return content.preferredWidth() + 8; }

    @Override public double preferredHeight(double width) { return preferredHeight; }

    public double scrollPosition() { return scroll; }

    public void scrollBy(double delta) {
        if (!Double.isFinite(delta)) throw new IllegalArgumentException("Invalid scroll delta");
        scroll += delta;
        layoutChildren();
    }

    @Override protected void layoutChildren() {
        double width = Math.max(0, width() - 8);
        double contentHeight = content.preferredHeight(width);
        limit = Math.max(0, contentHeight - height());
        scroll = Math.max(0, Math.min(limit, scroll));
        layoutChild(content, x(), y() - scroll, width, contentHeight);
    }

    void reveal(Widget widget) {
        if (widget.y() < y()) scrollBy(widget.y() - y());
        else if (widget.y() + widget.height() > y() + height())
            scrollBy(widget.y() + widget.height() - y() - height());
    }

    @Override protected void paint(Canvas canvas) {
        canvas.save();
        canvas.clipRect(x(), y(), width(), height());
        super.paint(canvas);
        if (limit > 0 && height() > 0) {
            double thumb = Math.min(height(), Math.max(12, height() * height() / (limit + height())));
            canvas.setFillStyle(0x7a8ea6ff);
            canvas.fillRect(x() + width() - 5, y() + (height() - thumb) * scroll / limit, 3, thumb);
        }
        canvas.restore();
    }
}
