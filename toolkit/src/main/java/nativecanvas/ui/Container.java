package nativecanvas.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import nativecanvas.Canvas;

/** Base class for layouts that own a list of children. */
public abstract class Container extends Widget {
    private final List<Widget> children = new ArrayList<>();
    private final List<Widget> childView = Collections.unmodifiableList(children);

    public final List<Widget> children() { return childView; }

    protected final void addChild(Widget child) {
        Objects.requireNonNull(child);
        if (child.parent != null || child.uiRoot) throw new IllegalArgumentException("Widget already has a parent");
        for (Widget ancestor = this; ancestor != null; ancestor = ancestor.parent)
            if (ancestor == child) throw new IllegalArgumentException("Widget hierarchy contains a cycle");
        child.parent = this;
        children.add(child);
    }

    protected final void layoutChild(Widget child, double x, double y, double width, double height) {
        if (child.parent != this) throw new IllegalArgumentException("Widget is not a child");
        child.layout(x, y, width, height);
    }

    @Override protected void paint(Canvas canvas) {
        for (Widget child : children) child.paint(canvas);
    }

    @Override Widget hit(double x, double y) {
        if (!contains(x, y)) return null;
        for (int i = children.size() - 1; i >= 0; i--) {
            Widget hit = children.get(i).hit(x, y);
            if (hit != null) return hit;
        }
        return this;
    }
}
