package nativecanvas.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Places children left to right. A child fills the row height. */
public final class Row extends Container {
    private final double padding, gap;
    private final List<Track> tracks = new ArrayList<>();

    public Row(double padding, double gap) {
        this.padding = Track.spacing(padding);
        this.gap = Track.spacing(gap);
    }

    public Row add(Widget child) { return add(child, Track.AUTO); }

    public Row add(Widget child, Track width) {
        Objects.requireNonNull(width);
        addChild(child);
        tracks.add(width);
        return this;
    }

    private double overhead() { return 2 * padding + Track.gaps(tracks.size(), gap); }

    private double[] widths(double available) {
        double[] preferred = new double[tracks.size()];
        for (int i = 0; i < preferred.length; i++) preferred[i] = children().get(i).preferredWidth();
        return Track.resolve(tracks.toArray(new Track[0]), preferred, available - overhead());
    }

    @Override public double preferredWidth() {
        double width = overhead();
        for (double childWidth : widths(0)) width += childWidth;
        return width;
    }

    @Override public double preferredHeight(double width) {
        double[] widths = widths(width);
        double tallest = 0;
        for (int i = 0; i < widths.length; i++)
            tallest = Math.max(tallest, children().get(i).preferredHeight(widths[i]));
        return tallest + 2 * padding;
    }

    @Override protected void layoutChildren() {
        double[] widths = widths(width());
        double x = x() + padding;
        for (int i = 0; i < widths.length; i++) {
            layoutChild(children().get(i), x, y() + padding, widths[i], Math.max(0, height() - 2 * padding));
            x += widths[i] + gap;
        }
    }
}
