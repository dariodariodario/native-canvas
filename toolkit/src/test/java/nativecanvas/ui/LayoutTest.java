package nativecanvas.ui;

import nativecanvas.Canvas;
import org.junit.jupiter.api.Test;

import static nativecanvas.ui.Track.AUTO;
import static nativecanvas.ui.Track.FILL;
import static nativecanvas.ui.Track.fill;
import static nativecanvas.ui.Track.size;
import static org.junit.jupiter.api.Assertions.*;

class LayoutTest {
    private static final class Probe extends Widget {
        private final double naturalWidth;

        Probe(double naturalWidth) { this.naturalWidth = naturalWidth; }
        @Override public double preferredWidth() { return naturalWidth; }
        @Override public double preferredHeight(double width) { return 10 + width / 10; }
        @Override protected void paint(Canvas canvas) {}
    }

    private static void bounds(Widget widget, double x, double y, double width, double height) {
        assertAll(
                () -> assertEquals(x, widget.x(), 1e-9),
                () -> assertEquals(y, widget.y(), 1e-9),
                () -> assertEquals(width, widget.width(), 1e-9),
                () -> assertEquals(height, widget.height(), 1e-9));
    }

    @Test void rowUsesFixedIntrinsicAndWeightedWidths() {
        Probe fixed = new Probe(90), auto = new Probe(30), first = new Probe(10), second = new Probe(20);
        Row row = new Row(5, 3).add(fixed, size(20)).add(auto).add(first, FILL).add(second, fill(2));
        new Ui(row, 159, 50);
        bounds(fixed, 5, 5, 20, 40);
        bounds(auto, 28, 5, 30, 40);
        bounds(first, 61, 5, 30, 40);
        bounds(second, 94, 5, 60, 40);
        assertEquals(99, row.preferredWidth());
        assertEquals(26, row.preferredHeight(159));
    }

    @Test void rowRetainsTrackMinimumsWhenNarrowAndHandlesNoChildren() {
        Probe fixed = new Probe(20), grow = new Probe(30);
        Row row = new Row(4, 2).add(fixed, size(10)).add(grow, FILL);
        new Ui(row, 20, 5);
        bounds(fixed, 4, 4, 10, 0);
        bounds(grow, 16, 4, 30, 0);
        Row empty = new Row(3, 99);
        assertEquals(6, empty.preferredWidth());
        assertEquals(6, empty.preferredHeight(100));
        new Ui(empty, 20, 20);
    }

    @Test void gridMeasuresRowsAfterResolvingColumnsAndFillsCells() {
        Probe topLeft = new Probe(30), bottomLeft = new Probe(40), bottomRight = new Probe(12);
        Grid grid = new Grid(new Track[]{AUTO, FILL}, new Track[]{AUTO, FILL}, 5, 4, 6)
                .add(0, 0, topLeft).add(1, 0, bottomLeft).add(1, 1, bottomRight);
        assertEquals(66, grid.preferredWidth());
        assertEquals(46.6, grid.preferredHeight(120), 1e-9);
        new Ui(grid, 120, 100);
        bounds(topLeft, 5, 5, 40, 14);
        bounds(bottomLeft, 5, 25, 40, 70);
        bounds(bottomRight, 49, 25, 66, 70);
    }

    @Test void gridReservesEmptyFixedTracksAndCopiesSizingArrays() {
        Track[] columns = {size(25), AUTO, FILL};
        Track[] rows = {size(20), AUTO};
        Grid grid = new Grid(columns, rows, 0, 2, 3);
        columns[0] = size(99);
        rows[0] = size(99);
        Probe child = new Probe(10);
        grid.add(1, 2, child);
        new Ui(grid, 79, 60);
        bounds(child, 29, 23, 50, 15);
        assertEquals(39, grid.preferredWidth());
        assertEquals(38, grid.preferredHeight(79));
    }

    @Test void gridAndRowComposeAndDispatchByLaidOutBounds() {
        int[] calls = {0};
        Button button = new Button("Go", () -> calls[0]++);
        Row row = new Row(2, 2).add(new Label(""), size(20)).add(button, FILL);
        Grid grid = new Grid(new Track[]{size(25), FILL}, new Track[]{AUTO}, 4, 3, 0)
                .add(0, 1, row);
        Ui ui = new Ui(grid, 150, 60);
        bounds(button, 56, 6, 88, 38);
        ui.pointerPressed(50, 15);
        ui.pointerReleased(50, 15);
        assertEquals(0, calls[0]);
        ui.pointerPressed(60, 15);
        ui.pointerReleased(60, 15);
        assertEquals(1, calls[0]);
        assertSame(button, ui.focusedControl());
    }

    @Test void rejectsInvalidTracksAndCellsWithoutTakingChildOwnership() {
        assertThrows(IllegalArgumentException.class, () -> size(-1));
        assertThrows(IllegalArgumentException.class, () -> size(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> fill(0));
        assertThrows(IllegalArgumentException.class, () -> fill(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new Row(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Grid(new Track[0], new Track[]{AUTO}, 0, 0, 0));
        assertThrows(NullPointerException.class, () -> new Grid(new Track[]{null}, new Track[]{AUTO}, 0, 0, 0));
        Grid grid = new Grid(new Track[]{AUTO}, new Track[]{AUTO}, 0, 0, 0).add(0, 0, new Probe(10));
        Probe probe = new Probe(10);
        assertThrows(IndexOutOfBoundsException.class, () -> grid.add(-1, 0, probe));
        assertThrows(IndexOutOfBoundsException.class, () -> grid.add(0, 1, probe));
        assertThrows(IllegalArgumentException.class, () -> grid.add(0, 0, probe));
        assertThrows(NullPointerException.class, () -> new Row(0, 0).add(probe, null));
        assertEquals(1, new Row(0, 0).add(probe).children().size());
    }

    @Test void labelChangesAffectAutoSizeAfterRelayout() {
        Label label = new Label("A");
        Row row = new Row(0, 0).add(label);
        Ui ui = new Ui(row, 200, 30);
        assertEquals(30, label.width());
        label.setText("ABC");
        ui.layout();
        assertEquals("ABC", label.text());
        assertEquals(50, label.width());
    }
}
