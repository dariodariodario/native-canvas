package nativecanvas.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A fixed number of rows and columns with one widget per occupied cell. */
public final class Grid extends Container {
    private final Track[] columns, rows;
    private final double padding, columnGap, rowGap;
    private final List<Cell> cells = new ArrayList<>();

    public Grid(Track[] columns, Track[] rows, double padding, double columnGap, double rowGap) {
        this.columns = copyTracks(columns);
        this.rows = copyTracks(rows);
        this.padding = Track.spacing(padding);
        this.columnGap = Track.spacing(columnGap);
        this.rowGap = Track.spacing(rowGap);
    }

    private static Track[] copyTracks(Track[] tracks) {
        if (tracks.length == 0) throw new IllegalArgumentException("Grid must have rows and columns");
        Track[] copy = tracks.clone();
        for (Track track : copy) Objects.requireNonNull(track);
        return copy;
    }

    /** Adds a child at a zero-based row and column. */
    public Grid add(int row, int column, Widget widget) {
        if (row < 0 || row >= rows.length || column < 0 || column >= columns.length)
            throw new IndexOutOfBoundsException("Grid cell out of bounds");
        for (Cell cell : cells)
            if (cell.row == row && cell.column == column)
                throw new IllegalArgumentException("Grid cell already occupied");
        addChild(widget);
        cells.add(new Cell(row, column, widget));
        return this;
    }

    private double[] widths(double available) {
        double[] preferred = new double[columns.length];
        for (Cell cell : cells)
            preferred[cell.column] = Math.max(preferred[cell.column], cell.widget.preferredWidth());
        return Track.resolve(columns, preferred, available - overhead(columns, columnGap));
    }

    private double[] heights(double[] widths, double available) {
        double[] preferred = new double[rows.length];
        for (Cell cell : cells)
            preferred[cell.row] = Math.max(preferred[cell.row], cell.widget.preferredHeight(widths[cell.column]));
        return Track.resolve(rows, preferred, available - overhead(rows, rowGap));
    }

    private double overhead(Track[] tracks, double gap) {
        return 2 * padding + Track.gaps(tracks.length, gap);
    }

    @Override public double preferredWidth() {
        double total = overhead(columns, columnGap);
        for (double width : widths(0)) total += width;
        return total;
    }

    @Override public double preferredHeight(double width) {
        double total = overhead(rows, rowGap);
        for (double height : heights(widths(width), 0)) total += height;
        return total;
    }

    @Override protected void layoutChildren() {
        double[] widths = widths(width());
        double[] heights = heights(widths, height());
        double[] xs = offsets(widths, x() + padding, columnGap);
        double[] ys = offsets(heights, y() + padding, rowGap);
        for (Cell cell : cells)
            layoutChild(cell.widget, xs[cell.column], ys[cell.row], widths[cell.column], heights[cell.row]);
    }

    private static double[] offsets(double[] sizes, double start, double gap) {
        double[] offsets = new double[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            offsets[i] = start;
            start += sizes[i] + gap;
        }
        return offsets;
    }

    private static final class Cell {
        final int row, column;
        final Widget widget;

        Cell(int row, int column, Widget widget) {
            this.row = row;
            this.column = column;
            this.widget = widget;
        }
    }
}
