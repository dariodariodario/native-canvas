package nativecanvas.ui;

/** Stacks children vertically at their preferred heights. */
public final class Column extends Container {
    private final double padding, gap;

    public Column(double padding, double gap) {
        if (!Double.isFinite(padding) || !Double.isFinite(gap) || padding < 0 || gap < 0)
            throw new IllegalArgumentException("Invalid layout spacing");
        this.padding = padding;
        this.gap = gap;
    }

    public Column add(Widget child) {
        addChild(child);
        return this;
    }

    @Override public double preferredWidth() {
        double widest = 0;
        for (Widget child : children()) widest = Math.max(widest, child.preferredWidth());
        return widest + 2 * padding;
    }

    @Override public double preferredHeight(double width) {
        double total = 2 * padding + Math.max(0, children().size() - 1) * gap;
        for (Widget child : children()) total += child.preferredHeight(innerWidth(width));
        return total;
    }

    private double innerWidth(double width) { return Math.max(0, width - 2 * padding); }

    @Override protected void layoutChildren() {
        double width = innerWidth(width());
        double top = y() + padding;
        for (Widget child : children()) {
            double height = child.preferredHeight(width);
            layoutChild(child, x() + padding, top, width, height);
            top += height + gap;
        }
    }
}
